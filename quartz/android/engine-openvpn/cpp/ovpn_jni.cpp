// Quartz's JNI wrapper around the openvpn3 core's client API (client/ovpncli.hpp), FORK.md §3.
//
// openvpn3 is used unpatched: this file subclasses ClientAPI::OpenVPNClient and forwards its
// tun-builder, socket-protect, event and log callbacks to a Kotlin object
// (app.quartz.engine.openvpn.OpenVpnNative.Callback). The Kotlin side owns the VpnService: it
// builds the TUN from these callbacks and returns its fd from tunEstablish().

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <exception>
#include <string>

#include <client/ovpncli.hpp>

using namespace openvpn;

namespace {

JavaVM *g_vm = nullptr;

// Callback method IDs, looked up once in JNI_OnLoad.
struct Methods
{
    jmethodID tunNew;
    jmethodID tunSetRemoteAddress;
    jmethodID tunAddAddress;
    jmethodID tunRerouteGw;
    jmethodID tunAddRoute;
    jmethodID tunExcludeRoute;
    jmethodID tunAddDns;
    jmethodID tunAddSearchDomain;
    jmethodID tunSetMtu;
    jmethodID tunSetSessionName;
    jmethodID tunEstablish;
    jmethodID tunTeardown;
    jmethodID protect;
    jmethodID event;
    jmethodID log;
} g_m;

// The JNIEnv of the calling thread, attaching it if openvpn3 calls back from a thread of its own.
class Env
{
  public:
    Env()
    {
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED)
        {
#ifdef __ANDROID__
            const jint attached = g_vm->AttachCurrentThread(&env_, nullptr);
#else
            const jint attached = g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env_), nullptr);
#endif
            if (attached == JNI_OK)
                attached_ = true;
            else
                env_ = nullptr;
        }
    }
    ~Env()
    {
        if (attached_)
            g_vm->DetachCurrentThread();
    }
    JNIEnv *operator->() const
    {
        return env_;
    }
    explicit operator bool() const
    {
        return env_ != nullptr;
    }
    JNIEnv *get() const
    {
        return env_;
    }

  private:
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

// A Java string for the length of one call.
class JStr
{
  public:
    JStr(JNIEnv *env, const std::string &s)
        : env_(env), ref_(env->NewStringUTF(s.c_str()))
    {
    }
    ~JStr()
    {
        if (ref_)
            env_->DeleteLocalRef(ref_);
    }
    operator jstring() const
    {
        return ref_;
    }

  private:
    JNIEnv *env_;
    jstring ref_;
};

std::string toStd(JNIEnv *env, jstring s)
{
    if (!s)
        return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars)
        env->ReleaseStringUTFChars(s, chars);
    return out;
}

// A Java exception thrown inside a callback must not unwind through openvpn3: clear it, report false.
bool cleared(JNIEnv *env)
{
    if (env->ExceptionCheck())
    {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

class QuartzClient : public ClientAPI::OpenVPNClient
{
  public:
    explicit QuartzClient(jobject callback)
        : callback_(callback)
    {
    }

    ~QuartzClient() override
    {
        Env env;
        if (env && callback_)
            env->DeleteGlobalRef(callback_);
    }

    // Tun builder: each call goes to Kotlin's VpnService.Builder.

    bool tun_builder_new() override
    {
        return callBool(g_m.tunNew);
    }

    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override
    {
        Env env;
        if (!env)
            return false;
        JStr a(env.get(), address);
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.tunSetRemoteAddress, (jstring)a, (jboolean)ipv6));
    }

    bool tun_builder_add_address(const std::string &address,
                                 int prefix_length,
                                 const std::string &gateway,
                                 bool ipv6,
                                 bool net30) override
    {
        Env env;
        if (!env)
            return false;
        JStr a(env.get(), address);
        JStr g(env.get(), gateway);
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.tunAddAddress, (jstring)a, (jint)prefix_length, (jstring)g, (jboolean)ipv6, (jboolean)net30));
    }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override
    {
        Env env;
        if (!env)
            return false;
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.tunRerouteGw, (jboolean)ipv4, (jboolean)ipv6, (jint)flags));
    }

    bool tun_builder_add_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return route(g_m.tunAddRoute, address, prefix_length, metric, ipv6);
    }

    bool tun_builder_exclude_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return route(g_m.tunExcludeRoute, address, prefix_length, metric, ipv6);
    }

    bool tun_builder_set_dns_options(const DnsOptions &dns) override
    {
        Env env;
        if (!env)
            return false;
        bool ok = true;
        // Servers in priority order (std::map), every address of each.
        for (const auto &[priority, server] : dns.servers)
        {
            for (const auto &address : server.addresses)
            {
                JStr a(env.get(), address.address);
                const bool ipv6 = address.address.find(':') != std::string::npos;
                ok = result(env.get(), env->CallBooleanMethod(callback_, g_m.tunAddDns, (jstring)a, (jboolean)ipv6)) && ok;
            }
        }
        for (const auto &domain : dns.search_domains)
        {
            JStr d(env.get(), domain.domain);
            ok = result(env.get(), env->CallBooleanMethod(callback_, g_m.tunAddSearchDomain, (jstring)d)) && ok;
        }
        return ok;
    }

    bool tun_builder_set_mtu(int mtu) override
    {
        Env env;
        if (!env)
            return false;
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.tunSetMtu, (jint)mtu));
    }

    bool tun_builder_set_session_name(const std::string &name) override
    {
        Env env;
        if (!env)
            return false;
        JStr n(env.get(), name);
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.tunSetSessionName, (jstring)n));
    }

    // Android's VpnService takes a whole config at once: options openvpn3 may send that it has
    // no use for are accepted and ignored, so they don't fail the session.
    bool tun_builder_set_layer(int layer) override
    {
        return layer == 3;
    }
    bool tun_builder_add_proxy_bypass(const std::string &) override
    {
        return true;
    }
    bool tun_builder_set_proxy_auto_config_url(const std::string &) override
    {
        return true;
    }
    bool tun_builder_set_proxy_http(const std::string &, int) override
    {
        return true;
    }
    bool tun_builder_set_proxy_https(const std::string &, int) override
    {
        return true;
    }
    bool tun_builder_add_wins_server(const std::string &) override
    {
        return true;
    }
    bool tun_builder_set_allow_family(int, bool) override
    {
        return true;
    }

    int tun_builder_establish() override
    {
        Env env;
        if (!env)
            return -1;
        const jint fd = env->CallIntMethod(callback_, g_m.tunEstablish);
        return cleared(env.get()) ? -1 : fd;
    }

    // Reuse the TUN across reconnects when the server pushes the same settings.
    bool tun_builder_persist() override
    {
        return true;
    }

    void tun_builder_teardown(bool disconnect) override
    {
        Env env;
        if (!env)
            return;
        env->CallVoidMethod(callback_, g_m.tunTeardown, (jboolean)disconnect);
        cleared(env.get());
    }

    bool socket_protect(openvpn_io::detail::socket_type socket, std::string, bool) override
    {
        Env env;
        if (!env)
            return false;
        return result(env.get(), env->CallBooleanMethod(callback_, g_m.protect, (jint)socket));
    }

    void event(const ClientAPI::Event &ev) override
    {
        Env env;
        if (!env)
            return;
        JStr name(env.get(), ev.name);
        JStr info(env.get(), ev.info);
        env->CallVoidMethod(callback_, g_m.event, (jstring)name, (jstring)info, (jboolean)ev.error, (jboolean)ev.fatal);
        cleared(env.get());
    }

    void acc_event(const ClientAPI::AppCustomControlMessageEvent &) override
    {
    }

    void log(const ClientAPI::LogInfo &info) override
    {
        Env env;
        if (!env)
            return;
        JStr text(env.get(), info.text);
        env->CallVoidMethod(callback_, g_m.log, (jstring)text);
        cleared(env.get());
    }

    // Profiles with an external certificate (Android keystore) aren't supported.
    void external_pki_cert_request(ClientAPI::ExternalPKICertRequest &req) override
    {
        req.error = true;
        req.errorText = "External PKI isn't supported";
    }

    void external_pki_sign_request(ClientAPI::ExternalPKISignRequest &req) override
    {
        req.error = true;
        req.errorText = "External PKI isn't supported";
    }

    // A connection that keeps timing out disconnects (CONNECTION_TIMEOUT) rather than pausing:
    // Quartz shows the tunnel as stopped and auto-tunnel can try again.
    bool pause_on_connection_timeout() override
    {
        return false;
    }

  private:
    bool callBool(jmethodID method)
    {
        Env env;
        if (!env)
            return false;
        return result(env.get(), env->CallBooleanMethod(callback_, method));
    }

    bool route(jmethodID method, const std::string &address, int prefix_length, int metric, bool ipv6)
    {
        Env env;
        if (!env)
            return false;
        JStr a(env.get(), address);
        return result(env.get(), env->CallBooleanMethod(callback_, method, (jstring)a, (jint)prefix_length, (jint)metric, (jboolean)ipv6));
    }

    static bool result(JNIEnv *env, jboolean value)
    {
        return !cleared(env) && value == JNI_TRUE;
    }

    jobject callback_;
};

QuartzClient *client(jlong handle)
{
    return reinterpret_cast<QuartzClient *>(static_cast<intptr_t>(handle));
}

ClientAPI::Config makeConfig(const std::string &content, bool disableClientCert = false)
{
    ClientAPI::Config config;
    config.content = content;
    config.disableClientCert = disableClientCert;
    config.info = true;
    // Old servers still run BF-CBC / no-AEAD setups; let them connect like OpenVPN 2.x clients do.
    config.enableLegacyAlgorithms = true;
    config.enableNonPreferredDCAlgorithms = true;
    return config;
}

// Parses a profile. One without a client certificate and key would need external PKI (an
// Android keystore certificate), which Quartz doesn't offer: it's a username/password profile, so
// it's parsed again with the client certificate turned off.
template <typename Evaluator>
ClientAPI::EvalConfig evaluate(Evaluator &evaluator, const std::string &content)
{
    ClientAPI::EvalConfig eval = evaluator.eval_config(makeConfig(content));
    if (!eval.error && eval.externalPki)
        eval = evaluator.eval_config(makeConfig(content, true));
    return eval;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass cls = env->FindClass("app/quartz/engine/openvpn/OpenVpnNative$Callback");
    if (!cls)
        return JNI_ERR;
    g_m.tunNew = env->GetMethodID(cls, "tunNew", "()Z");
    g_m.tunSetRemoteAddress = env->GetMethodID(cls, "tunSetRemoteAddress", "(Ljava/lang/String;Z)Z");
    g_m.tunAddAddress = env->GetMethodID(cls, "tunAddAddress", "(Ljava/lang/String;ILjava/lang/String;ZZ)Z");
    g_m.tunRerouteGw = env->GetMethodID(cls, "tunRerouteGw", "(ZZI)Z");
    g_m.tunAddRoute = env->GetMethodID(cls, "tunAddRoute", "(Ljava/lang/String;IIZ)Z");
    g_m.tunExcludeRoute = env->GetMethodID(cls, "tunExcludeRoute", "(Ljava/lang/String;IIZ)Z");
    g_m.tunAddDns = env->GetMethodID(cls, "tunAddDns", "(Ljava/lang/String;Z)Z");
    g_m.tunAddSearchDomain = env->GetMethodID(cls, "tunAddSearchDomain", "(Ljava/lang/String;)Z");
    g_m.tunSetMtu = env->GetMethodID(cls, "tunSetMtu", "(I)Z");
    g_m.tunSetSessionName = env->GetMethodID(cls, "tunSetSessionName", "(Ljava/lang/String;)Z");
    g_m.tunEstablish = env->GetMethodID(cls, "tunEstablish", "()I");
    g_m.tunTeardown = env->GetMethodID(cls, "tunTeardown", "(Z)V");
    g_m.protect = env->GetMethodID(cls, "protect", "(I)Z");
    g_m.event = env->GetMethodID(cls, "event", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
    g_m.log = env->GetMethodID(cls, "log", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeCreate(JNIEnv *env, jclass, jobject callback)
{
    try
    {
        return static_cast<jlong>(reinterpret_cast<intptr_t>(new QuartzClient(env->NewGlobalRef(callback))));
    }
    catch (const std::exception &)
    {
        return 0;
    }
}

// Parses the profile and gives the credentials; null when ready to connect, else the error.
JNIEXPORT jstring JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeConfigure(JNIEnv *env, jclass, jlong handle, jstring profile, jstring username, jstring password)
{
    try
    {
        auto *c = client(handle);
        const ClientAPI::EvalConfig eval = evaluate(*c, toStd(env, profile));
        if (eval.error)
            return env->NewStringUTF(eval.message.c_str());
        const std::string user = toStd(env, username);
        const std::string pass = toStd(env, password);
        if (!eval.autologin)
        {
            if (user.empty() && eval.userlockedUsername.empty())
                return env->NewStringUTF("This profile needs a username and password: add them in the tunnel's settings");
            ClientAPI::ProvideCreds creds;
            creds.username = user.empty() ? eval.userlockedUsername : user;
            creds.password = pass;
            const ClientAPI::Status status = c->provide_creds(creds);
            if (status.error)
                return env->NewStringUTF(status.message.c_str());
        }
        return nullptr;
    }
    catch (const std::exception &e)
    {
        return env->NewStringUTF(e.what());
    }
}

// Runs the session on the calling thread until it ends; null on a clean stop, else the error.
JNIEXPORT jstring JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeConnect(JNIEnv *env, jclass, jlong handle)
{
    try
    {
        const ClientAPI::Status status = client(handle)->connect();
        if (!status.error)
            return nullptr;
        const std::string message = status.message.empty() ? status.status : status.message;
        return env->NewStringUTF(message.empty() ? "OpenVPN error" : message.c_str());
    }
    catch (const std::exception &e)
    {
        return env->NewStringUTF(e.what());
    }
}

// May be called from any thread while nativeConnect runs.
JNIEXPORT void JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeStop(JNIEnv *, jclass, jlong handle)
{
    try
    {
        client(handle)->stop();
    }
    catch (const std::exception &)
    {
    }
}

JNIEXPORT void JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
    delete client(handle);
}

// Bytes in and out through the tunnel: {in, out}.
JNIEXPORT jlongArray JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeTraffic(JNIEnv *env, jclass, jlong handle)
{
    jlong values[2] = {0, 0};
    try
    {
        const ClientAPI::TransportStats stats = client(handle)->transport_stats();
        values[0] = static_cast<jlong>(stats.bytesIn);
        values[1] = static_cast<jlong>(stats.bytesOut);
    }
    catch (const std::exception &)
    {
    }
    jlongArray out = env->NewLongArray(2);
    if (out)
        env->SetLongArrayRegion(out, 0, 2, values);
    return out;
}

// Checks a profile without connecting: "" if it needs nothing, "creds" if it needs a username
// and password, otherwise "error:" and the parser's message.
JNIEXPORT jstring JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeCheck(JNIEnv *env, jclass, jstring profile)
{
    try
    {
        ClientAPI::OpenVPNClientHelper helper;
        const ClientAPI::EvalConfig eval = evaluate(helper, toStd(env, profile));
        if (eval.error)
            return env->NewStringUTF(("error:" + eval.message).c_str());
        return env->NewStringUTF(eval.autologin ? "" : "creds");
    }
    catch (const std::exception &e)
    {
        return env->NewStringUTF((std::string("error:") + e.what()).c_str());
    }
}

JNIEXPORT jstring JNICALL
Java_app_quartz_engine_openvpn_OpenVpnNative_nativeVersion(JNIEnv *env, jclass)
{
    return env->NewStringUTF(ClientAPI::OpenVPNClientHelper::platform().c_str());
}

} // extern "C"
