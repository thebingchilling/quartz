#![cfg_attr(windows, windows_subsystem = "windows")]

use std::ffi::OsString;
use std::fs;
use std::io::{self, Cursor};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, SystemTime};

static BUNDLE: &[u8] = include_bytes!(env!("BUNDLE_ZIP_PATH"));
const BUNDLE_HASH: &str = env!("BUNDLE_HASH");
const APP_EXE: &str = "Quartz.exe";
/// Read by Quartz: the file the updater replaces.
const PORTABLE_ENV: &str = "QUARTZ_PORTABLE_EXE";
const KEEP_VERSIONS: usize = 2;
/// Passed by Quartz's updater after it replaced this file: wait for the old instance to exit, so
/// the new one isn't handed off to it.
const AFTER_UPDATE: &str = "--after-update";
const AFTER_UPDATE_WAIT: Duration = Duration::from_secs(3);

fn main() {
    if let Err(error) = run() {
        show_error(&format!("Quartz couldn't start: {error}"));
        std::process::exit(1);
    }
}

fn run() -> io::Result<()> {
    let root = cache_root()?;
    let target = root.join(&BUNDLE_HASH[..16]);
    let app = match find_app(&target) {
        Some(app) => app,
        None => {
            unpack(BUNDLE, &root, &target)?;
            find_app(&target).ok_or_else(|| io::Error::other("the bundle has no Quartz.exe"))?
        }
    };
    let me = std::env::current_exe()?;
    forget_download_origin(&me);
    let mut args: Vec<OsString> = std::env::args_os().skip(1).collect();
    if args.first().is_some_and(|arg| arg == AFTER_UPDATE) {
        args.remove(0);
        std::thread::sleep(AFTER_UPDATE_WAIT);
    }
    Command::new(&app)
        .args(args)
        .env(PORTABLE_ENV, &me)
        .current_dir(app.parent().unwrap_or(&target))
        .spawn()?;
    let _ = fs::write(target.join(".last-run"), []);
    prune(&root, KEEP_VERSIONS);
    Ok(())
}

fn cache_root() -> io::Result<PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .or_else(|| Some(std::env::temp_dir()))
        .ok_or_else(|| io::Error::other("no local app data folder"))?;
    Ok(base.join("Quartz").join("app"))
}

fn find_app(dir: &Path) -> Option<PathBuf> {
    let direct = dir.join(APP_EXE);
    if direct.is_file() {
        return Some(direct);
    }
    fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|entry| entry.path().join(APP_EXE))
        .find(|path| path.is_file())
}

/// Unpacks next to [target] first, so a half-written copy is never run.
fn unpack(bundle: &[u8], root: &Path, target: &Path) -> io::Result<()> {
    fs::create_dir_all(root)?;
    let staging = root.join(format!(".unpack-{}", std::process::id()));
    let _ = fs::remove_dir_all(&staging);
    let mut archive = zip::ZipArchive::new(Cursor::new(bundle)).map_err(io::Error::other)?;
    archive.extract(&staging).map_err(io::Error::other)?;
    if fs::rename(&staging, target).is_err() {
        let _ = fs::remove_dir_all(&staging);
        if find_app(target).is_none() {
            return Err(io::Error::other("couldn't unpack Quartz"));
        }
    }
    Ok(())
}

/// Keeps the newest [keep] versions; older ones still in use stay until a later run.
fn prune(root: &Path, keep: usize) {
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    let mut versions = entries
        .flatten()
        .filter(|entry| entry.path().is_dir())
        .filter(|entry| !entry.file_name().to_string_lossy().starts_with('.'))
        .map(|entry| {
            let modified = entry
                .metadata()
                .and_then(|meta| meta.modified())
                .unwrap_or(SystemTime::UNIX_EPOCH);
            (modified, entry.path())
        })
        .collect::<Vec<_>>();
    versions.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, path) in versions.into_iter().skip(keep) {
        let _ = fs::remove_dir_all(path);
    }
}

/// SmartScreen checks a file each time it runs for as long as it carries the
/// mark of the web, and clicking "Run anyway" leaves the mark in place; once
/// Windows has let the EXE start, dropping the mark stops the repeat prompts.
#[cfg(windows)]
fn forget_download_origin(exe: &Path) {
    let mut stream = exe.as_os_str().to_owned();
    stream.push(":Zone.Identifier");
    let _ = fs::remove_file(PathBuf::from(stream));
}

#[cfg(not(windows))]
fn forget_download_origin(_exe: &Path) {}

#[cfg(windows)]
fn show_error(message: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
    let text: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
    let title: Vec<u16> = "Quartz".encode_utf16().chain(Some(0)).collect();
    unsafe { MessageBoxW(0, text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONERROR) };
}

#[cfg(not(windows))]
fn show_error(message: &str) {
    eprintln!("{message}");
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn bundle() -> Vec<u8> {
        let mut out = Cursor::new(Vec::new());
        let mut zip = zip::ZipWriter::new(&mut out);
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("Quartz/Quartz.exe", options).unwrap();
        zip.write_all(b"exe").unwrap();
        zip.start_file("Quartz/data/app.so", options).unwrap();
        zip.write_all(b"so").unwrap();
        zip.finish().unwrap();
        out.into_inner()
    }

    #[test]
    fn unpacks_once_and_finds_the_app_one_level_down() {
        let root = std::env::temp_dir().join(format!("quartz-launcher-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let target = root.join("abc");
        unpack(&bundle(), &root, &target).unwrap();
        let app = find_app(&target).unwrap();
        assert!(app.ends_with("Quartz/Quartz.exe"));
        unpack(&bundle(), &root, &target).unwrap();
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn prune_keeps_the_newest_versions() {
        let root = std::env::temp_dir().join(format!("quartz-prune-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        for name in ["a", "b", "c"] {
            fs::create_dir_all(root.join(name)).unwrap();
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        prune(&root, 2);
        let mut left = fs::read_dir(&root)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().into_string().unwrap())
            .collect::<Vec<_>>();
        left.sort();
        assert_eq!(left, ["b", "c"]);
        fs::remove_dir_all(&root).unwrap();
    }
}
