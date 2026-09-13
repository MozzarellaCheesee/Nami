//! Проверка и установка обновлений сервера.
//!
//! Существующая команда `nami update` предполагала `git pull` и `cargo build` - то есть
//! установку из исходников. У человека, поставившего сервер одной командой, ни репозитория, ни
//! Rust нет, и обновляться ему было нечем. Здесь обновление устроено так же, как у приложения:
//! спрашиваем последний релиз на GitHub, скачиваем готовый бинарник под свою платформу и
//! подменяем им себя.
//!
//! # Почему замену можно делать на живом процессе
//!
//! Перезаписать работающий исполняемый файл нельзя ни на Windows, ни на Linux, а вот
//! ПЕРЕИМЕНОВАТЬ его можно на обеих: открытый файл продолжает жить под новым именем. Поэтому
//! порядок такой - отодвинуть себя в `.old`, положить новый файл на своё место, перезапуститься.
//! Остаток убирается при следующем запуске: пока процесс жив, он держит старый файл.

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::Res;

const REPO: &str = "MozzarellaCheesee/Nami";

#[derive(Debug, Serialize)]
pub struct UpdateInfo {
    pub current: String,
    pub latest: Option<String>,
    /// Есть ли что ставить. Отдельным полем, а не сравнением на клиенте: правило сравнения
    /// версий должно быть одно и то же у веба, TUI и CLI.
    pub available: bool,
    /// Ссылка на страницу релиза - человеку почитать, что меняется.
    pub url: Option<String>,
    /// Прямая ссылка на бинарник под текущую платформу. Пусто - сборки для неё в релизе нет.
    pub asset_url: Option<String>,
    pub notes: Option<String>,
}

pub fn current_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// Имя файла в релизе для текущей платформы.
fn asset_name() -> Option<&'static str> {
    if cfg!(windows) {
        Some("nami-server-windows-x86_64.zip")
    } else if cfg!(target_arch = "aarch64") {
        Some("nami-server-linux-aarch64.tar.gz")
    } else if cfg!(target_arch = "x86_64") {
        Some("nami-server-linux-x86_64.tar.gz")
    } else {
        None
    }
}

pub fn check() -> Res<UpdateInfo> {
    let body = ureq::get(format!("https://api.github.com/repos/{REPO}/releases/latest"))
        .header("User-Agent", "nami-server")
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| format!("GitHub недоступен: {e}"))?
        .body_mut()
        .read_to_string()
        .map_err(|e| format!("ответ GitHub не прочитан: {e}"))?;
    let json: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| format!("ответ GitHub не разобран: {e}"))?;

    let latest = json["tag_name"].as_str().map(|t| t.trim_start_matches('v').to_string());
    let url = json["html_url"].as_str().map(str::to_string);
    let notes = json["body"].as_str().map(str::to_string);

    let want = asset_name();
    let asset_url = want.and_then(|want| {
        json["assets"].as_array()?.iter().find_map(|a| {
            (a["name"].as_str()? == want).then(|| a["browser_download_url"].as_str())?
        })
    });

    let available = latest
        .as_deref()
        .map(|l| is_newer(l, current_version()))
        .unwrap_or(false);

    Ok(UpdateInfo {
        current: current_version().to_string(),
        latest,
        available,
        url,
        asset_url: asset_url.map(str::to_string),
        notes,
    })
}

/// Строго ли `candidate` новее `current`.
///
/// Сравнение почисловое, а не строковое: "1.0.10" строкой меньше "1.0.9", и на десятом выпуске
/// обновления просто перестали бы предлагаться. Суффикс после дефиса (бета) отбрасывается -
/// для сервера версии всегда вида x.y.z.
pub fn is_newer(candidate: &str, current: &str) -> bool {
    fn parts(v: &str) -> Vec<u64> {
        v.trim_start_matches('v')
            .split('-')
            .next()
            .unwrap_or("")
            .split('.')
            .map(|p| p.parse().unwrap_or(0))
            .collect()
    }
    let (a, b) = (parts(candidate), parts(current));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

/// Скачивает и ставит обновление. Возвращает версию, на которую обновились.
pub fn install(info: &UpdateInfo) -> Res<String> {
    let Some(asset) = info.asset_url.as_deref() else {
        return Err("для этой платформы в релизе нет готовой сборки".into());
    };
    let latest = info.latest.clone().unwrap_or_else(|| "неизвестно".into());

    let exe = std::env::current_exe()?;
    let dir = exe.parent().ok_or("не удалось определить каталог установки")?.to_path_buf();
    let tmp_dir = dir.join("update_tmp");
    let _ = std::fs::remove_dir_all(&tmp_dir);
    std::fs::create_dir_all(&tmp_dir)?;

    let archive = tmp_dir.join(if asset.ends_with(".zip") { "nami.zip" } else { "nami.tar.gz" });
    download(asset, &archive)?;
    let fresh = unpack_binary(&archive, &tmp_dir)?;

    // Себя отодвигаем, а не перезаписываем: работающий файл переименовать можно, перезаписать -
    // нельзя. Остаток уберёт следующий запуск, пока процесс жив он держит старый файл.
    let old = dir.join(format!(
        "{}.old",
        exe.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default()
    ));
    let _ = std::fs::remove_file(&old);
    std::fs::rename(&exe, &old)?;
    if let Err(e) = std::fs::copy(&fresh, &exe) {
        // Возвращаем себя на место, иначе сервер останется без исполняемого файла.
        let _ = std::fs::rename(&old, &exe);
        return Err(format!("не удалось заменить бинарник: {e}").into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&exe, std::fs::Permissions::from_mode(0o755));
    }
    let _ = std::fs::remove_dir_all(&tmp_dir);
    Ok(latest)
}

/// Убирает остаток прошлого обновления. Зовётся при старте: к этому моменту старый процесс
/// уже завершился и файл никем не держится.
pub fn cleanup_old() {
    if let Ok(exe) = std::env::current_exe() {
        if let (Some(dir), Some(name)) = (exe.parent(), exe.file_name()) {
            let _ = std::fs::remove_file(dir.join(format!("{}.old", name.to_string_lossy())));
        }
    }
}

fn download(url: &str, to: &Path) -> Res<()> {
    let mut resp = ureq::get(url)
        .header("User-Agent", "nami-server")
        .call()
        .map_err(|e| format!("не удалось скачать: {e}"))?;
    let mut file = std::fs::File::create(to)?;
    std::io::copy(&mut resp.body_mut().as_reader(), &mut file)?;
    Ok(())
}

/// Достаёт исполняемый файл из скачанного архива системным распаковщиком.
///
/// Своего распаковщика нет намеренно: tar есть в любой современной Windows и в любом Linux, а
/// тащить крейты zip и tar+gzip ради одной операции раз в месяц - лишние зависимости в сборке,
/// которая должна оставаться маленькой.
fn unpack_binary(archive: &Path, dir: &Path) -> Res<PathBuf> {
    let status = std::process::Command::new("tar")
        .arg("-xf")
        .arg(archive)
        .arg("-C")
        .arg(dir)
        .status()
        .map_err(|e| format!("tar не запустился: {e}"))?;
    if !status.success() {
        return Err("архив обновления не распаковался".into());
    }
    let wanted = if cfg!(windows) { "nami-server.exe" } else { "nami-server" };
    find_file(dir, wanted).ok_or_else(|| format!("в архиве нет {wanted}").into())
}

fn find_file(dir: &Path, name: &str) -> Option<PathBuf> {
    for entry in std::fs::read_dir(dir).ok()?.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if let Some(found) = find_file(&path, name) {
                return Some(found);
            }
        } else if path.file_name().map(|f| f == name).unwrap_or(false) {
            return Some(path);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::is_newer;

    #[test]
    fn версии_сравниваются_числами_а_не_строками() {
        // Именно на этом ломается строковое сравнение: "1.0.10" < "1.0.9" как текст, и на
        // десятом выпуске обновления перестали бы предлагаться совсем.
        assert!(is_newer("1.0.10", "1.0.9"));
        assert!(!is_newer("1.0.9", "1.0.10"));
    }

    #[test]
    fn та_же_версия_не_предлагается() {
        assert!(!is_newer("1.0.14", "1.0.14"));
        assert!(!is_newer("v1.0.14", "1.0.14"));
    }

    #[test]
    fn разная_длина_и_суффикс_не_мешают() {
        assert!(is_newer("1.1", "1.0.9"));
        assert!(!is_newer("1.0.14-beta.1", "1.0.14"));
    }
}
