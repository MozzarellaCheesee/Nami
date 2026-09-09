use serde::Serialize;
use sysinfo::{MemoryRefreshKind, RefreshKind, System};

/// Реально измеренные возможности хоста.
///
/// Тяжёлых фич (транскодинг, анализ) на этом этапе ещё нет, но решение "тянет ли машина"
/// принимается здесь и сейчас по фактическим CPU/RAM, а не по флагу в конфиге - чтобы
/// потом не пристраивать измерение к уже готовым фичам.
#[derive(Debug, Clone, Serialize)]
pub struct HostCapabilities {
    /// Логических ядер, доступных процессу (учитывает cgroup-лимиты Docker).
    pub cpu_cores: usize,
    pub total_memory_mb: u64,
    pub available_memory_mb: u64,
    /// Целевой минимум проекта: 1 ядро / 512 МБ.
    pub meets_baseline: bool,
    /// Транскодинг на лету - минимум 2 ядра и 1 ГБ RAM.
    pub transcoding_recommended: bool,
    /// Фоновый анализ (ReplayGain, фингерпринт) - 2 ядра и 2 ГБ RAM.
    pub analysis_recommended: bool,
}

/// Замеряет хост. Дешёвая операция, но не бесплатная - вызывать по запросу, не в цикле.
pub fn measure() -> HostCapabilities {
    let cpu_cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);

    let sys = System::new_with_specifics(
        RefreshKind::nothing().with_memory(MemoryRefreshKind::nothing().with_ram()),
    );
    let total_memory_mb = sys.total_memory() / (1024 * 1024);
    let available_memory_mb = sys.available_memory() / (1024 * 1024);

    HostCapabilities {
        cpu_cores,
        total_memory_mb,
        available_memory_mb,
        meets_baseline: cpu_cores >= 1 && total_memory_mb >= 480,
        transcoding_recommended: cpu_cores >= 2 && total_memory_mb >= 1024,
        analysis_recommended: cpu_cores >= 2 && total_memory_mb >= 2048,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn измерение_возвращает_правдоподобные_значения() {
        let c = measure();
        assert!(c.cpu_cores >= 1, "ядер должно быть хотя бы одно");
        // 64 МБ - заведомо меньше любой машины, на которой это вообще запустится:
        // ловим именно заглушку "return 0", а не конкретное железо.
        assert!(c.total_memory_mb >= 64, "RAM не определилась: {c:?}");
        assert!(c.available_memory_mb <= c.total_memory_mb);
    }

    #[test]
    fn пороги_тяжёлых_фич_согласованы() {
        let c = measure();
        // Транскодинг не может быть рекомендован там, где не выполнен базовый минимум.
        assert!(!c.transcoding_recommended || c.meets_baseline);
        assert!(!c.analysis_recommended || c.transcoding_recommended);
    }
}
