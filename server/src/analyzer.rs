//! Анализ аудио: ReplayGain/R128, BPM, тональность, chromaprint-fingerprint.
//!
//! ponytail: ffmpeg для ReplayGain/R128 (ebur128 filter) и для декода в PCM, chromaprint
//! для fingerprint через fpcalc. BPM и тональность - порт того же DSP, что в Android-клиенте
//! (`player/.../BpmKeyAnalyzer.kt`): RMS-огибающая -> onset -> автокорреляция для темпа;
//! хромаграмма из оконного FFT -> корреляция с профилями Крумганьского-Шмуклера для
//! тональности. FFT - свой радикс-2 (одна функция), тянуть ради него крейт незачем.

use std::path::Path;
use std::process::Command;

use rusqlite::Connection;
use serde::Serialize;

use crate::Res;

/// Результат анализа трека
#[derive(Debug, Clone, Default)]
pub struct Analysis {
    /// ReplayGain track gain (dB)
    pub replaygain_track_gain: Option<f32>,
    /// ReplayGain track peak (0.0..1.0+)
    pub replaygain_track_peak: Option<f32>,
    /// EBU R128 loudness (LUFS)
    pub r128_loudness: Option<f32>,
    /// Chromaprint fingerprint (base64)
    pub fingerprint: Option<String>,
    /// Оценка темпа, ударов в минуту (50..200). Оценка, не claim на точность DJ-софта.
    pub bpm: Option<f32>,
    /// Тональность, например `A Minor`. По первым 90 с трека.
    pub musical_key: Option<String>,
    /// RMS-огибающая трека: WAVEFORM_BARS значений 0..1, нормировано к своему максимуму.
    /// Клиент рисует ей скраббер, не декодируя файл сам (§34).
    pub waveform: Option<Vec<f32>>,
}

/// Столбиков в форме волны - как в Android-клиенте (`WaveformScanner.BAR_COUNT`).
const WAVEFORM_BARS: usize = 120;

/// Сколько секунд трека берём на BPM/тональность: полный декод+FFT ограничен по CPU/RAM.
const ANALYSIS_SECS: u32 = 90;
/// Частота дискретизации для анализа. 22050 хватает и хромаграмме (до 5 кГц), и темпу.
const ANALYSIS_RATE: u32 = 22_050;

/// Анализирует трек через ffmpeg (ReplayGain/R128) и fpcalc (chromaprint).
///
/// ponytail: один проход ffmpeg с ebur128 filter даёт оба значения (ReplayGain ~= R128).
/// fpcalc вызывается отдельно, если нужен fingerprint для дедупликации/поиска.
pub fn analyze(path: &Path, need_fingerprint: bool) -> Res<Analysis> {
    let mut result = Analysis::default();

    // ReplayGain/R128 через ebur128 filter
    let output = Command::new("ffmpeg")
        .args([
            "-i",
            path.to_str().unwrap(),
            "-af",
            "ebur128=framelog=verbose",
            "-f",
            "null",
            "-",
        ])
        .output()?;

    if output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);

        // Парсим I (integrated loudness) из лога ebur128
        if let Some(line) = stderr.lines().find(|l| l.contains("I:")) {
            if let Some(val) = line.split("I:").nth(1).and_then(|s| {
                s.trim().split_whitespace().next().and_then(|v| v.parse::<f32>().ok())
            }) {
                result.r128_loudness = Some(val);
                // ReplayGain track gain = -18 LUFS - измеренный LUFS
                result.replaygain_track_gain = Some(-18.0 - val);
            }
        }

        // Peak из того же вывода
        if let Some(line) = stderr.lines().find(|l| l.contains("Peak:")) {
            if let Some(val) = line.split("Peak:").nth(1).and_then(|s| {
                s.trim().split_whitespace().next().and_then(|v| v.parse::<f32>().ok())
            }) {
                result.replaygain_track_peak = Some(val);
            }
        }
    }

    // BPM и тональность: декод первых 90 с в mono f32 PCM, дальше чистый DSP.
    if let Some(samples) = decode_mono(path, Some(ANALYSIS_SECS)) {
        result.bpm = estimate_bpm(&samples, ANALYSIS_RATE);
        result.musical_key = estimate_key(&samples, ANALYSIS_RATE);
    }
    // Форма волны - по всему треку (отдельный декод: буферы BPM/ключа не хватило бы).
    result.waveform = waveform_from_file(path);

    // Chromaprint fingerprint через fpcalc
    if need_fingerprint {
        if let Ok(output) = Command::new("fpcalc")
            .args(["-raw", path.to_str().unwrap()])
            .output()
        {
            if output.status.success() {
                let stdout = String::from_utf8_lossy(&output.stdout);
                if let Some(line) = stdout.lines().find(|l| l.starts_with("FINGERPRINT=")) {
                    if let Some(fp) = line.strip_prefix("FINGERPRINT=") {
                        result.fingerprint = Some(fp.trim().to_string());
                    }
                }
            }
        }
    }

    Ok(result)
}

// ---------------------------------------------------------------- BPM и тональность

/// Декодирует первые ANALYSIS_SECS секунд в mono f32 PCM через ffmpeg.
/// ~8 МБ на 90 с при 22050 Гц - в память помещается, для целевого бюджета терпимо.
fn decode_mono(path: &Path, limit_secs: Option<u32>) -> Option<Vec<f32>> {
    let mut args: Vec<String> = vec!["-v".into(), "error".into(), "-i".into(), path.to_str()?.into()];
    if let Some(s) = limit_secs {
        args.push("-t".into());
        args.push(s.to_string());
    }
    args.extend(
        ["-ac", "1", "-ar", &ANALYSIS_RATE.to_string(), "-f", "f32le", "-"]
            .map(String::from),
    );
    let out = Command::new("ffmpeg").args(&args).output().ok()?;
    // Меньше секунды звука - анализировать нечего.
    if !out.status.success() || out.stdout.len() < 4 * ANALYSIS_RATE as usize {
        return None;
    }
    Some(
        out.stdout
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .collect(),
    )
}

/// Потоковая форма волны с постоянной памятью. При заполнении 240 промежуточных
/// бакетов соседние объединяются; даже многочасовой микс не превращается в Vec всего PCM.
fn waveform_from_file(path: &Path) -> Option<Vec<f32>> {
    use std::io::Read;
    let mut child = Command::new("ffmpeg")
        .args(["-v", "error", "-i"]).arg(path)
        .args(["-ac", "1", "-ar", &ANALYSIS_RATE.to_string(), "-f", "f32le", "-"])
        .stdout(std::process::Stdio::piped()).spawn().ok()?;
    let mut stdout = child.stdout.take()?;
    let mut bins: Vec<(f64, u64)> = Vec::with_capacity(WAVEFORM_BARS * 2);
    let mut target = 128u64;
    let (mut sum, mut count) = (0.0f64, 0u64);
    let mut bytes = [0u8; 32 * 1024];
    let mut carry = Vec::with_capacity(3);
    loop {
        let n = stdout.read(&mut bytes).ok()?;
        if n == 0 { break; }
        carry.extend_from_slice(&bytes[..n]);
        let complete = carry.len() / 4 * 4;
        for b in carry[..complete].chunks_exact(4) {
            let sample = f32::from_le_bytes([b[0], b[1], b[2], b[3]]) as f64;
            sum += sample * sample;
            count += 1;
            if count == target { bins.push((sum, count)); sum = 0.0; count = 0; }
        }
        carry.drain(..complete);
        if bins.len() >= WAVEFORM_BARS * 2 {
            bins = bins.chunks(2).map(|p| {
                if p.len() == 2 { (p[0].0 + p[1].0, p[0].1 + p[1].1) } else { p[0] }
            }).collect();
            target *= 2;
        }
    }
    if count > 0 { bins.push((sum, count)); }
    let _ = child.wait();
    if bins.is_empty() { return None; }
    let rms: Vec<f32> = bins.iter().map(|(s, n)| (s / *n as f64).sqrt() as f32).collect();
    let sampled: Vec<f32> = (0..WAVEFORM_BARS)
        .map(|i| rms[i * rms.len() / WAVEFORM_BARS])
        .collect();
    waveform_bars(&sampled)
}

const ENVELOPE_HOP: usize = 512;
const FFT_SIZE: usize = 4096;
const NOTE_NAMES: [&str; 12] =
    ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
// Профили Крумганьского-Шмуклера, корень C (индекс 0).
const MAJOR_PROFILE: [f64; 12] =
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR_PROFILE: [f64; 12] =
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

/// Оценка темпа: RMS-огибающая (хоп 512) -> полуволновой onset -> автокорреляция
/// по лагам, отвечающим 50..200 BPM. Лаг с максимумом самоподобия - период доли.
fn estimate_bpm(samples: &[f32], rate: u32) -> Option<f32> {
    if samples.len() < ENVELOPE_HOP * 4 {
        return None;
    }
    let n = samples.len() / ENVELOPE_HOP;
    let mut env = vec![0.0f64; n];
    for (i, e) in env.iter_mut().enumerate() {
        let start = i * ENVELOPE_HOP;
        let end = (start + ENVELOPE_HOP).min(samples.len());
        let ss: f64 = samples[start..end].iter().map(|x| *x as f64 * *x as f64).sum();
        *e = (ss / ENVELOPE_HOP as f64).sqrt();
    }
    let mut onset = vec![0.0f64; n];
    for i in 1..n {
        onset[i] = (env[i] - env[i - 1]).max(0.0);
    }
    let hop_s = ENVELOPE_HOP as f64 / rate as f64;
    let min_lag = ((60.0 / 200.0 / hop_s).round() as usize).max(1);
    let max_lag = (60.0 / 50.0 / hop_s).round() as usize;
    if max_lag >= n || min_lag >= max_lag {
        return None;
    }
    let (mut best_lag, mut best) = (0usize, 0.0f64);
    for lag in min_lag..=max_lag {
        let score: f64 = (0..n - lag).map(|i| onset[i] * onset[i + lag]).sum();
        if score > best {
            best = score;
            best_lag = lag;
        }
    }
    if best_lag == 0 || best <= 0.0 {
        return None;
    }
    let bpm = (60.0 / (best_lag as f64 * hop_s)) as f32;
    (50.0..=200.0).contains(&bpm).then_some(bpm)
}

/// Оценка тональности: хромаграмма из оконного FFT -> корреляция с 24 повёрнутыми
/// профилями Крумганьского-Шмуклера. Лучший - `<нота> Major|Minor`.
fn estimate_key(samples: &[f32], rate: u32) -> Option<String> {
    if samples.len() < FFT_SIZE {
        return None;
    }
    let window: Vec<f64> = (0..FFT_SIZE)
        .map(|i| {
            0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (FFT_SIZE as f64 - 1.0)).cos()
        })
        .collect();
    let mut chroma = [0.0f64; 12];
    let mut frames = 0usize;
    let mut start = 0usize;
    while start + FFT_SIZE <= samples.len() {
        let mut re: Vec<f64> =
            (0..FFT_SIZE).map(|i| samples[start + i] as f64 * window[i]).collect();
        let mut im = vec![0.0f64; FFT_SIZE];
        fft(&mut re, &mut im);
        for k in 1..FFT_SIZE / 2 {
            let freq = k as f64 * rate as f64 / FFT_SIZE as f64;
            if !(80.0..=5000.0).contains(&freq) {
                continue;
            }
            let mag = (re[k] * re[k] + im[k] * im[k]).sqrt();
            let midi = 69.0 + 12.0 * (freq / 440.0).log2();
            let pc = (((midi.round() as i64 - 60) % 12 + 12) % 12) as usize;
            chroma[pc] += mag;
        }
        frames += 1;
        start += FFT_SIZE / 2;
    }
    if frames == 0 || chroma.iter().sum::<f64>() <= 0.0 {
        return None;
    }
    let (mut best_corr, mut best_root, mut best_major) = (f64::NEG_INFINITY, 0usize, true);
    for root in 0..12 {
        for (profile, is_major) in [(&MAJOR_PROFILE, true), (&MINOR_PROFILE, false)] {
            let c = correlate(&chroma, profile, root);
            if c > best_corr {
                best_corr = c;
                best_root = root;
                best_major = is_major;
            }
        }
    }
    Some(format!("{} {}", NOTE_NAMES[best_root], if best_major { "Major" } else { "Minor" }))
}

/// Форма волны: WAVEFORM_BARS столбиков RMS-громкости по всему треку, нормировано к
/// своему максимуму, со сглаживанием 5-точечным взвешенным средним. Порт
/// `WaveformScanner.kt`: RMS, а не пик - у современных мастерингов пик почти везде у
/// потолка, «кирпич»; RMS показывает реальный контур тихо/громко.
fn waveform_bars(samples: &[f32]) -> Option<Vec<f32>> {
    if samples.len() < WAVEFORM_BARS {
        return None;
    }
    let mut sum_sq = vec![0.0f64; WAVEFORM_BARS];
    let mut counts = vec![0u64; WAVEFORM_BARS];
    for (i, s) in samples.iter().enumerate() {
        let bucket = i * WAVEFORM_BARS / samples.len();
        sum_sq[bucket] += *s as f64 * *s as f64;
        counts[bucket] += 1;
    }
    let rms: Vec<f64> = (0..WAVEFORM_BARS)
        .map(|i| if counts[i] > 0 { (sum_sq[i] / counts[i] as f64).sqrt() } else { 0.0 })
        .collect();
    let max = rms.iter().cloned().fold(0.0f64, f64::max);
    if max <= 0.0 {
        return None;
    }
    let norm: Vec<f32> = rms.iter().map(|v| (v / max).clamp(0.05, 1.0) as f32).collect();

    // 5-точечное взвешенное сглаживание, у краёв окно укорачивается (не паддим нулями).
    let weights = [1.0f32, 2.0, 4.0, 2.0, 1.0];
    let radius = 2i32;
    Some(
        (0..WAVEFORM_BARS as i32)
            .map(|i| {
                let (mut acc, mut wt) = (0.0f32, 0.0f32);
                for off in -radius..=radius {
                    let j = i + off;
                    if (0..WAVEFORM_BARS as i32).contains(&j) {
                        let w = weights[(off + radius) as usize];
                        acc += norm[j as usize] * w;
                        wt += w;
                    }
                }
                acc / wt
            })
            .collect(),
    )
}

/// Корреляция Пирсона хромаграммы с профилем тональности, повёрнутым тоникой на `root`.
fn correlate(chroma: &[f64; 12], profile: &[f64; 12], root: usize) -> f64 {
    let rot: [f64; 12] = std::array::from_fn(|i| profile[(i + 12 - root) % 12]);
    let cm = chroma.iter().sum::<f64>() / 12.0;
    let pm = rot.iter().sum::<f64>() / 12.0;
    let (mut num, mut cv, mut pv) = (0.0f64, 0.0f64, 0.0f64);
    for i in 0..12 {
        let (cd, pd) = (chroma[i] - cm, rot[i] - pm);
        num += cd * pd;
        cv += cd * cd;
        pv += pd * pd;
    }
    let den = (cv * pv).sqrt();
    if den <= 0.0 {
        0.0
    } else {
        num / den
    }
}

/// Итеративный радикс-2 БПФ на месте. Длина `re`/`im` обязана быть степенью двойки.
/// ponytail: своя реализация вместо крейта - алгоритм классический, кода мало.
fn fft(re: &mut [f64], im: &mut [f64]) {
    let n = re.len();
    // Бит-реверс перестановка.
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j |= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= n {
        let ang = -2.0 * std::f64::consts::PI / len as f64;
        let (wr, wi) = (ang.cos(), ang.sin());
        let mut i = 0usize;
        while i < n {
            let (mut cr, mut ci) = (1.0f64, 0.0f64);
            for k in 0..len / 2 {
                let a = i + k;
                let b = a + len / 2;
                let tr = cr * re[b] - ci * im[b];
                let ti = cr * im[b] + ci * re[b];
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
                let ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = ncr;
            }
            i += len;
        }
        len <<= 1;
    }
}

/// Проверяет доступность ffmpeg и fpcalc в PATH
pub fn check_tools() -> (bool, bool) {
    let ffmpeg = Command::new("ffmpeg").arg("-version").output().is_ok();
    let fpcalc = Command::new("fpcalc").arg("-version").output().is_ok();
    (ffmpeg, fpcalc)
}

/// Отчёт порционного анализа - та же форма, что у обогащения метаданных.
#[derive(Debug, Default, Serialize)]
pub struct AnalyzeReport {
    /// Сколько треков рассмотрено за вызов.
    pub checked: usize,
    /// У скольких появились реальные значения (loudness или fingerprint).
    pub analyzed: usize,
    /// Сколько треков ещё без анализа (для следующего вызова).
    pub remaining: i64,
}

/// Прогоняет анализатор по трекам без `analyzed_at`, порциями.
///
/// ponytail: ffmpeg декодирует файл целиком ради ebur128 - это секунды на трек, поэтому
/// синхронно в сканер такое ставить нельзя (50 000 треков - часы). Отдельный порционный
/// проход, как `library::enrich` для MusicBrainz: вызывающий обязан запускать в
/// spawn_blocking, `remaining` говорит, сколько осталось.
///
/// Трек помечается `analyzed_at` в любом случае - битый файл не должен вечно висеть в
/// очереди и стоить по запуску ffmpeg каждый раз.
pub fn analyze_batch(
    conn: &Connection,
    limit: usize,
    need_fingerprint: bool,
) -> rusqlite::Result<AnalyzeReport> {
    let mut rep = AnalyzeReport::default();
    let todo: Vec<(i64, String)> = {
        let mut stmt = conn.prepare(
            "SELECT id, path FROM tracks WHERE analyzed_at IS NULL ORDER BY id LIMIT ?1",
        )?;
        let rows = stmt
            .query_map([limit as i64], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows
    };

    for (id, path) in &todo {
        rep.checked += 1;
        conn.execute(
            "UPDATE tracks SET analyzed_at=?2 WHERE id=?1",
            rusqlite::params![id, crate::db::now()],
        )?;
        let Ok(a) = analyze(Path::new(path), need_fingerprint) else {
            continue;
        };
        let wf = a.waveform.as_ref().map(|w| serde_json::to_string(w).unwrap_or_default());
        conn.execute(
            "UPDATE tracks SET rg_track_gain=?2, rg_track_peak=?3, r128_loudness=?4,
                fingerprint=COALESCE(?5, fingerprint), bpm=?6, musical_key=?7,
                waveform=COALESCE(?8, waveform) WHERE id=?1",
            rusqlite::params![
                id,
                a.replaygain_track_gain,
                a.replaygain_track_peak,
                a.r128_loudness,
                a.fingerprint,
                a.bpm,
                a.musical_key,
                wf,
            ],
        )?;
        if a.r128_loudness.is_some() || a.fingerprint.is_some() || a.bpm.is_some() {
            rep.analyzed += 1;
        }
    }

    rep.remaining =
        conn.query_row("SELECT COUNT(*) FROM tracks WHERE analyzed_at IS NULL", [], |r| r.get(0))?;
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    #[test]
    fn порционный_анализ_помечает_и_считает_остаток() {
        let c = db();
        for i in 1..=3 {
            c.execute(
                "INSERT INTO tracks (path, title, seen_at) VALUES (?1, ?2, 0)",
                rusqlite::params![format!("/нет/такого-{i}.flac"), format!("трек {i}")],
            )
            .unwrap();
        }

        // Битые пути: анализ не даст значений, но пометит рассмотренными.
        let rep = analyze_batch(&c, 2, false).unwrap();
        assert_eq!(rep.checked, 2);
        assert_eq!(rep.analyzed, 0, "несуществующий файл значений не даёт");
        assert_eq!(rep.remaining, 1, "третий трек ещё в очереди");

        let done: i64 = c
            .query_row("SELECT COUNT(*) FROM tracks WHERE analyzed_at IS NOT NULL", [], |r| r.get(0))
            .unwrap();
        assert_eq!(done, 2);

        // Второй вызов добирает остаток и очередь пустеет.
        let rep = analyze_batch(&c, 10, false).unwrap();
        assert_eq!(rep.checked, 1);
        assert_eq!(rep.remaining, 0);
    }

    #[test]
    fn форма_волны_нормируется_и_сглаживается() {
        let rate = ANALYSIS_RATE as usize;
        // Первая половина тихая, вторая громкая.
        let mut s = vec![0.05f32; rate * 4];
        for x in s.iter_mut().skip(rate * 2) {
            *x = 0.8;
        }
        let w = waveform_bars(&s).expect("форма волны должна посчитаться");
        assert_eq!(w.len(), WAVEFORM_BARS);
        assert!(w.iter().all(|v| (0.05..=1.0).contains(v)), "значения в диапазоне 0.05..1");
        assert!(w.last().unwrap() > &0.9, "громкий хвост нормируется к ~1");
        assert!(w[10] < w[110], "тихое начало ниже громкого конца");
        // Короткий вход - None, не паника.
        assert!(waveform_bars(&[0.1; 10]).is_none());
    }

    #[test]
    fn fft_даёт_пик_на_частоте_синуса() {
        let n = 1024;
        let bin = 64; // ровно 64 периода на окно
        let mut re: Vec<f64> = (0..n)
            .map(|i| (2.0 * std::f64::consts::PI * bin as f64 * i as f64 / n as f64).sin())
            .collect();
        let mut im = vec![0.0f64; n];
        fft(&mut re, &mut im);
        let mag = |k: usize| (re[k] * re[k] + im[k] * im[k]).sqrt();
        let peak = (1..n / 2).max_by(|a, b| mag(*a).total_cmp(&mag(*b))).unwrap();
        assert_eq!(peak, bin, "энергия синуса должна лечь ровно в свой бин");
    }

    #[test]
    fn bpm_считается_по_щелчкам() {
        // Щелчок каждые 24 хопа огибающей - период кратен хопу, без алиасинга.
        // 60 / (24 * 512/22050) ≈ 107.6 BPM. Октавная неоднозначность (половина/вдвое)
        // у onset-автокорреляции ожидаема - её же не правит и оригинал в Android.
        let rate = ANALYSIS_RATE;
        let period = ENVELOPE_HOP * 24;
        let expected = 60.0 / (period as f64 / rate as f64);
        let mut s = vec![0.0f32; (rate * 20) as usize];
        let mut i = 0usize;
        while i < s.len() {
            for j in 0..64.min(s.len() - i) {
                s[i + j] = if j < 32 { 0.9 } else { -0.9 };
            }
            i += period;
        }
        let bpm = estimate_bpm(&s, rate).expect("темп по щелчкам должен посчитаться") as f64;
        let ok = [expected, expected / 2.0, expected * 2.0]
            .iter()
            .any(|t| (bpm - t).abs() < 3.0);
        assert!(ok, "ожидали ~{expected:.0} BPM (или октаву), получили {bpm:.1}");
    }
}
