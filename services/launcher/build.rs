use sha2::{Digest, Sha256};
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=BUNDLE_ZIP");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let bundle = match std::env::var("BUNDLE_ZIP") {
        Ok(path) => PathBuf::from(path),
        Err(_) => {
            // Lets `cargo check` and tests run without a Windows build to embed.
            let empty = out.join("empty.zip");
            std::fs::write(&empty, []).unwrap();
            empty
        }
    };
    println!("cargo:rerun-if-changed={}", bundle.display());
    let bytes = std::fs::read(&bundle).expect("BUNDLE_ZIP is unreadable");
    let hash = format!("{:x}", Sha256::digest(&bytes));
    println!("cargo:rustc-env=BUNDLE_ZIP_PATH={}", bundle.display());
    println!("cargo:rustc-env=BUNDLE_HASH={hash}");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut resource = winresource::WindowsResource::new();
        resource.set_icon("../../windows/runner/resources/app_icon.ico");
        resource.set("ProductName", "Quartz");
        resource.set("FileDescription", "Quartz");
        if let Err(error) = resource.compile() {
            println!("cargo:warning=no icon embedded: {error}");
        }
    }
}
