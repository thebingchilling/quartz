use serde::Deserialize;

/// What the kill switch blocks. Loopback, the Core, the TUN adapter and DHCP always pass, so
/// traffic flows while the tunnel is up and goes nowhere while it's down.
#[derive(Debug, Default, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct FirewallParams {
    #[serde(default)]
    pub block_all: bool,
    #[serde(default)]
    pub apps: Vec<String>,
    #[serde(default)]
    pub tun_alias: Option<String>,
    #[serde(default)]
    pub block_on_core_exit: bool,
}

impl FirewallParams {
    pub fn is_off(&self) -> bool {
        !self.block_all && self.apps.is_empty()
    }

    /// The Helper sees the Core die first, so blocking here leaves no gap.
    pub fn on_core_exit(&self) -> Option<FirewallParams> {
        self.block_on_core_exit.then(|| FirewallParams {
            block_all: true,
            apps: Vec::new(),
            tun_alias: self.tun_alias.clone(),
            block_on_core_exit: false,
        })
    }
}

#[cfg(windows)]
pub(super) use windows_impl::apply;

#[cfg(not(windows))]
pub(super) fn apply(params: &FirewallParams, _core: &std::path::Path) -> Result<(), String> {
    if params.is_off() {
        Ok(())
    } else {
        Err("the kill switch firewall is only available on Windows".into())
    }
}

#[cfg(windows)]
mod windows_impl {
    use super::FirewallParams;
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use std::ptr::{null, null_mut};
    use std::sync::Mutex;
    use windows_sys::core::GUID;
    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::NetworkManagement::IpHelper::ConvertInterfaceAliasToLuid;
    use windows_sys::Win32::NetworkManagement::Ndis::NET_LUID_LH;
    use windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::*;
    use windows_sys::Win32::System::Rpc::RPC_C_AUTHN_WINNT;

    struct Engine {
        handle: HANDLE,
        sublayer: usize,
    }

    // SAFETY: a WFP engine handle may be used and closed from any thread.
    unsafe impl Send for Engine {}

    impl Drop for Engine {
        fn drop(&mut self) {
            // The session is dynamic: closing it removes every filter it added.
            unsafe { FwpmEngineClose0(self.handle) };
        }
    }

    static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

    // Two keys, so the next set of filters is in place before the previous session closes.
    const SUBLAYERS: [GUID; 2] = [
        GUID::from_u128(0x5a1f3c2e_8d4b_4e6a_9c71_2b3d4e5f6a7b),
        GUID::from_u128(0x7c2e4d1f_3a5b_4c6d_8e9f_0a1b2c3d4e5f),
    ];
    const UDP: u8 = 17;

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(Some(0)).collect()
    }

    fn check(code: u32, what: &str) -> Result<(), String> {
        if code == 0 {
            Ok(())
        } else {
            Err(format!("{what} failed: 0x{code:08x}"))
        }
    }

    struct AppId(*mut FWP_BYTE_BLOB);

    impl AppId {
        fn of(path: &Path) -> Result<Self, String> {
            let name = wide(path.as_os_str());
            let mut blob = null_mut();
            check(
                unsafe { FwpmGetAppIdFromFileName0(name.as_ptr(), &mut blob) },
                &format!("app id of {}", path.display()),
            )?;
            Ok(Self(blob))
        }
    }

    impl Drop for AppId {
        fn drop(&mut self) {
            unsafe { FwpmFreeMemory0(&mut self.0 as *mut _ as *mut *mut core::ffi::c_void) };
        }
    }

    enum Match {
        Loopback,
        App(*mut FWP_BYTE_BLOB),
        Interface(*mut u64),
        UdpPort(u16),
    }

    fn conditions(matches: &[Match]) -> Vec<FWPM_FILTER_CONDITION0> {
        let mut out = Vec::new();
        for item in matches {
            let (field, match_type, value) = match *item {
                Match::Loopback => (
                    FWPM_CONDITION_FLAGS,
                    FWP_MATCH_FLAGS_ALL_SET,
                    FWP_CONDITION_VALUE0 {
                        r#type: FWP_UINT32,
                        Anonymous: FWP_CONDITION_VALUE0_0 {
                            uint32: FWP_CONDITION_FLAG_IS_LOOPBACK,
                        },
                    },
                ),
                Match::App(blob) => (
                    FWPM_CONDITION_ALE_APP_ID,
                    FWP_MATCH_EQUAL,
                    FWP_CONDITION_VALUE0 {
                        r#type: FWP_BYTE_BLOB_TYPE,
                        Anonymous: FWP_CONDITION_VALUE0_0 { byteBlob: blob },
                    },
                ),
                Match::Interface(luid) => (
                    FWPM_CONDITION_IP_LOCAL_INTERFACE,
                    FWP_MATCH_EQUAL,
                    FWP_CONDITION_VALUE0 {
                        r#type: FWP_UINT64,
                        Anonymous: FWP_CONDITION_VALUE0_0 { uint64: luid },
                    },
                ),
                Match::UdpPort(port) => {
                    out.push(FWPM_FILTER_CONDITION0 {
                        fieldKey: FWPM_CONDITION_IP_PROTOCOL,
                        matchType: FWP_MATCH_EQUAL,
                        conditionValue: FWP_CONDITION_VALUE0 {
                            r#type: FWP_UINT8,
                            Anonymous: FWP_CONDITION_VALUE0_0 { uint8: UDP },
                        },
                    });
                    (
                        FWPM_CONDITION_IP_REMOTE_PORT,
                        FWP_MATCH_EQUAL,
                        FWP_CONDITION_VALUE0 {
                            r#type: FWP_UINT16,
                            Anonymous: FWP_CONDITION_VALUE0_0 { uint16: port },
                        },
                    )
                }
            };
            out.push(FWPM_FILTER_CONDITION0 {
                fieldKey: field,
                matchType: match_type,
                conditionValue: value,
            });
        }
        out
    }

    fn add_filter(
        engine: HANDLE,
        sublayer: &GUID,
        layer: &GUID,
        permit: bool,
        weight: u8,
        matches: &[Match],
    ) -> Result<(), String> {
        let mut name = wide(OsStr::new("Quartz kill switch"));
        let mut conditions = conditions(matches);
        // Every pointer set below outlives FwpmFilterAdd0.
        let mut filter: FWPM_FILTER0 = unsafe { std::mem::zeroed() };
        filter.displayData.name = name.as_mut_ptr();
        filter.layerKey = *layer;
        filter.subLayerKey = *sublayer;
        filter.weight = FWP_VALUE0 {
            r#type: FWP_UINT8,
            Anonymous: FWP_VALUE0_0 { uint8: weight },
        };
        filter.numFilterConditions = conditions.len() as u32;
        filter.filterCondition = if conditions.is_empty() {
            null_mut()
        } else {
            conditions.as_mut_ptr()
        };
        filter.action.r#type = if permit {
            FWP_ACTION_PERMIT
        } else {
            FWP_ACTION_BLOCK
        };
        let mut id = 0u64;
        check(
            unsafe { FwpmFilterAdd0(engine, &filter, null_mut(), &mut id) },
            "adding a filter",
        )
    }

    fn open(sublayer: usize) -> Result<Engine, String> {
        let mut name = wide(OsStr::new("Quartz kill switch"));
        let mut session: FWPM_SESSION0 = unsafe { std::mem::zeroed() };
        session.displayData.name = name.as_mut_ptr();
        session.flags = FWPM_SESSION_FLAG_DYNAMIC;
        let mut handle: HANDLE = 0;
        check(
            unsafe { FwpmEngineOpen0(null(), RPC_C_AUTHN_WINNT, null(), &session, &mut handle) },
            "opening the filter engine",
        )?;
        let engine = Engine { handle, sublayer };
        let mut layer: FWPM_SUBLAYER0 = unsafe { std::mem::zeroed() };
        layer.subLayerKey = SUBLAYERS[sublayer];
        layer.displayData.name = name.as_mut_ptr();
        layer.weight = u16::MAX;
        check(
            unsafe { FwpmSubLayerAdd0(engine.handle, &layer, null_mut()) },
            "adding the sublayer",
        )?;
        Ok(engine)
    }

    fn tun_luid(alias: &str) -> Option<u64> {
        let name = wide(OsStr::new(alias));
        let mut luid = NET_LUID_LH { Value: 0 };
        let code = unsafe { ConvertInterfaceAliasToLuid(name.as_ptr(), &mut luid) };
        (code == 0).then(|| unsafe { luid.Value })
    }

    fn install(engine: &Engine, params: &FirewallParams, core: &Path) -> Result<(), String> {
        let core_id = AppId::of(core)?;
        let blocked = params
            .apps
            .iter()
            .filter_map(|app| AppId::of(Path::new(app)).ok())
            .collect::<Vec<_>>();
        let mut luid = params.tun_alias.as_deref().and_then(tun_luid);
        let sublayer = &SUBLAYERS[engine.sublayer];
        for (layer, dhcp) in [
            (FWPM_LAYER_ALE_AUTH_CONNECT_V4, 67u16),
            (FWPM_LAYER_ALE_AUTH_CONNECT_V6, 547u16),
        ] {
            let h = engine.handle;
            add_filter(h, sublayer, &layer, true, 15, &[Match::Loopback])?;
            add_filter(h, sublayer, &layer, true, 14, &[Match::App(core_id.0)])?;
            if let Some(luid) = luid.as_mut() {
                add_filter(h, sublayer, &layer, true, 13, &[Match::Interface(luid)])?;
            }
            add_filter(h, sublayer, &layer, true, 12, &[Match::UdpPort(dhcp)])?;
            if params.block_all {
                add_filter(h, sublayer, &layer, false, 0, &[])?;
            } else {
                for app in &blocked {
                    add_filter(h, sublayer, &layer, false, 1, &[Match::App(app.0)])?;
                }
            }
        }
        Ok(())
    }

    pub(in crate::service) fn apply(params: &FirewallParams, core: &Path) -> Result<(), String> {
        let mut current = ENGINE
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if params.is_off() {
            *current = None;
            return Ok(());
        }
        let next = current.as_ref().map_or(0, |engine| 1 - engine.sublayer);
        let engine = open(next)?;
        check(
            unsafe { FwpmTransactionBegin0(engine.handle, 0) },
            "starting a transaction",
        )?;
        if let Err(error) = install(&engine, params, core) {
            unsafe { FwpmTransactionAbort0(engine.handle) };
            return Err(error);
        }
        check(
            unsafe { FwpmTransactionCommit0(engine.handle) },
            "committing the filters",
        )?;
        *current = Some(engine);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::FirewallParams;

    #[test]
    fn params_read_the_json_quartz_sends() {
        let params: FirewallParams =
            serde_json::from_str(r#"{"blockAll":true,"apps":["C:\\a.exe"],"tunAlias":"Quartz"}"#)
                .unwrap();
        assert!(params.block_all);
        assert_eq!(params.apps, vec![r"C:\a.exe".to_string()]);
        assert_eq!(params.tun_alias.as_deref(), Some("Quartz"));
        assert!(!params.block_on_core_exit);
        assert!(FirewallParams::default().is_off());
    }

    #[test]
    fn an_armed_core_exit_blocks_everything_through_the_same_tunnel() {
        let armed: FirewallParams = serde_json::from_str(
            r#"{"blockAll":false,"tunAlias":"Quartz","blockOnCoreExit":true}"#,
        )
        .unwrap();
        assert!(armed.is_off());
        let block = armed.on_core_exit().unwrap();
        assert!(block.block_all);
        assert_eq!(block.tun_alias.as_deref(), Some("Quartz"));
        assert!(block.on_core_exit().is_none());
        assert!(FirewallParams::default().on_core_exit().is_none());
    }
}
