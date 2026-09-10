import React, { useState } from 'react';
import { api } from '../api.js';
import { NamiColors } from '../colors.js';

// Компонент просмотра логов сканирования библиотеки
export function ScannerLogs() {
  const [logs, setLogs] = useState(null);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState('');

  // Запуск сканирования
  async function handleScan() {
    setScanning(true);
    setError('');
    try {
      const result = await api.triggerScan();
      setLogs(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setScanning(false);
    }
  }

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>Сканирование библиотеки</h2>

      <div style={styles.actions}>
        <button
          onClick={handleScan}
          disabled={scanning}
          style={{...styles.scanButton, opacity: scanning ? 0.5 : 1}}
        >
          {scanning ? 'Сканирую...' : 'Запустить сканирование'}
        </button>
      </div>

      {error && <div style={styles.error}>{error}</div>}

      {scanning && (
        <div style={styles.scanningStatus}>
          <div style={styles.spinner}></div>
          <div>Идёт сканирование библиотеки...</div>
        </div>
      )}

      {logs && !scanning && (
        <div style={styles.results}>
          <h3 style={styles.subtitle}>Результаты сканирования</h3>

          {/* Общая статистика */}
          <div style={styles.statsGrid}>
            {logs.added !== undefined && (
              <div style={styles.statCard}>
                <div style={styles.statValue}>{logs.added}</div>
                <div style={styles.statLabel}>Добавлено</div>
              </div>
            )}
            {logs.updated !== undefined && (
              <div style={styles.statCard}>
                <div style={styles.statValue}>{logs.updated}</div>
                <div style={styles.statLabel}>Обновлено</div>
              </div>
            )}
            {logs.removed !== undefined && (
              <div style={styles.statCard}>
                <div style={styles.statValue}>{logs.removed}</div>
                <div style={styles.statLabel}>Удалено</div>
              </div>
            )}
            {logs.total !== undefined && (
              <div style={styles.statCard}>
                <div style={{...styles.statValue, color: NamiColors.Ai}}>
                  {logs.total}
                </div>
                <div style={styles.statLabel}>Всего треков</div>
              </div>
            )}
          </div>

          {/* Время сканирования */}
          {logs.duration_ms !== undefined && (
            <div style={styles.infoCard}>
              <div style={styles.infoLabel}>Длительность:</div>
              <div style={styles.infoValue}>
                {(logs.duration_ms / 1000).toFixed(2)} сек
              </div>
            </div>
          )}

          {/* Ошибки сканирования */}
          {logs.errors && logs.errors.length > 0 && (
            <div style={styles.errorsSection}>
              <h4 style={styles.errorTitle}>
                Ошибки ({logs.errors.length})
              </h4>
              <div style={styles.errorsList}>
                {logs.errors.map((err, idx) => (
                  <div key={idx} style={styles.errorItem}>
                    <div style={styles.errorPath}>{err.path || err.file}</div>
                    <div style={styles.errorMessage}>
                      {err.error || err.message}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Предупреждения */}
          {logs.warnings && logs.warnings.length > 0 && (
            <div style={styles.warningsSection}>
              <h4 style={styles.warningTitle}>
                Предупреждения ({logs.warnings.length})
              </h4>
              <div style={styles.warningsList}>
                {logs.warnings.map((warn, idx) => (
                  <div key={idx} style={styles.warningItem}>
                    {warn.message || warn}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Успешное завершение без проблем */}
          {(!logs.errors || logs.errors.length === 0) &&
           (!logs.warnings || logs.warnings.length === 0) && (
            <div style={styles.successCard}>
              <div style={styles.successIcon}>✓</div>
              <div style={styles.successText}>
                Сканирование завершено успешно
              </div>
            </div>
          )}

          {/* Необработанные данные (для отладки) */}
          {logs.raw_data && (
            <details style={styles.details}>
              <summary style={styles.detailsSummary}>
                Подробные данные (JSON)
              </summary>
              <pre style={styles.jsonPre}>
                {JSON.stringify(logs, null, 2)}
              </pre>
            </details>
          )}
        </div>
      )}

      {!logs && !scanning && !error && (
        <div style={styles.placeholder}>
          Нажмите кнопку для запуска сканирования библиотеки
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {
    padding: '24px',
    maxWidth: '900px',
    margin: '0 auto',
    color: NamiColors.Paper100,
    backgroundColor: NamiColors.Ink900,
    minHeight: '100vh'
  },
  title: {
    fontSize: '28px',
    fontWeight: '600',
    marginBottom: '24px',
    color: NamiColors.Paper100
  },
  subtitle: {
    fontSize: '20px',
    fontWeight: '600',
    marginBottom: '16px',
    color: NamiColors.Paper100
  },
  actions: {
    marginBottom: '24px'
  },
  scanButton: {
    padding: '12px 24px',
    backgroundColor: NamiColors.Shu,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '8px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  error: {
    marginBottom: '16px',
    padding: '12px',
    backgroundColor: NamiColors.Ink700,
    border: `1px solid ${NamiColors.Shu}`,
    borderRadius: '6px',
    color: NamiColors.Shu,
    fontSize: '14px'
  },
  scanningStatus: {
    display: 'flex',
    alignItems: 'center',
    gap: '16px',
    padding: '24px',
    backgroundColor: NamiColors.Ink800,
    borderRadius: '8px',
    color: NamiColors.Paper70
  },
  spinner: {
    width: '24px',
    height: '24px',
    border: `3px solid ${NamiColors.Ink600}`,
    borderTop: `3px solid ${NamiColors.Shu}`,
    borderRadius: '50%',
    animation: 'spin 1s linear infinite'
  },
  placeholder: {
    padding: '60px 40px',
    textAlign: 'center',
    color: NamiColors.Paper40,
    backgroundColor: NamiColors.Ink800,
    borderRadius: '8px',
    fontSize: '15px'
  },
  results: {
    marginTop: '24px'
  },
  statsGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
    gap: '12px',
    marginBottom: '20px'
  },
  statCard: {
    backgroundColor: NamiColors.Ink800,
    padding: '20px',
    borderRadius: '8px',
    textAlign: 'center'
  },
  statValue: {
    fontSize: '32px',
    fontWeight: '700',
    color: NamiColors.Wakaba,
    marginBottom: '8px'
  },
  statLabel: {
    fontSize: '13px',
    color: NamiColors.Paper70
  },
  infoCard: {
    backgroundColor: NamiColors.Ink800,
    padding: '16px',
    borderRadius: '8px',
    marginBottom: '16px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  infoLabel: {
    fontSize: '14px',
    color: NamiColors.Paper70
  },
  infoValue: {
    fontSize: '16px',
    fontWeight: '600',
    color: NamiColors.Paper100
  },
  errorsSection: {
    marginTop: '20px',
    padding: '16px',
    backgroundColor: NamiColors.Ink800,
    border: `1px solid ${NamiColors.Shu}`,
    borderRadius: '8px'
  },
  errorTitle: {
    fontSize: '16px',
    fontWeight: '600',
    color: NamiColors.Shu,
    marginBottom: '12px'
  },
  errorsList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px'
  },
  errorItem: {
    padding: '12px',
    backgroundColor: NamiColors.Ink700,
    borderRadius: '6px'
  },
  errorPath: {
    fontSize: '13px',
    fontFamily: 'monospace',
    color: NamiColors.Paper100,
    marginBottom: '4px',
    wordBreak: 'break-all'
  },
  errorMessage: {
    fontSize: '13px',
    color: NamiColors.Paper70
  },
  warningsSection: {
    marginTop: '20px',
    padding: '16px',
    backgroundColor: NamiColors.Ink800,
    border: `1px solid ${NamiColors.Kin}`,
    borderRadius: '8px'
  },
  warningTitle: {
    fontSize: '16px',
    fontWeight: '600',
    color: NamiColors.Kin,
    marginBottom: '12px'
  },
  warningsList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px'
  },
  warningItem: {
    padding: '10px',
    backgroundColor: NamiColors.Ink700,
    borderRadius: '6px',
    fontSize: '13px',
    color: NamiColors.Paper70
  },
  successCard: {
    marginTop: '20px',
    padding: '24px',
    backgroundColor: NamiColors.Ink800,
    border: `1px solid ${NamiColors.Wakaba}`,
    borderRadius: '8px',
    display: 'flex',
    alignItems: 'center',
    gap: '16px'
  },
  successIcon: {
    width: '48px',
    height: '48px',
    borderRadius: '50%',
    backgroundColor: NamiColors.Wakaba,
    color: NamiColors.Ink900,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '28px',
    fontWeight: 'bold'
  },
  successText: {
    fontSize: '16px',
    fontWeight: '600',
    color: NamiColors.Wakaba
  },
  details: {
    marginTop: '24px',
    backgroundColor: NamiColors.Ink800,
    padding: '16px',
    borderRadius: '8px'
  },
  detailsSummary: {
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
    color: NamiColors.Paper70,
    marginBottom: '12px'
  },
  jsonPre: {
    padding: '12px',
    backgroundColor: NamiColors.Ink700,
    borderRadius: '6px',
    fontSize: '12px',
    fontFamily: 'monospace',
    color: NamiColors.Paper70,
    overflow: 'auto',
    maxHeight: '400px'
  }
};
