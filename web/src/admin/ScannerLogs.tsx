import { useState } from 'react'

interface ScanReport {
  scanned: number
  added: number
  updated: number
  removed: number
  failed: number
}

export function ScannerLogs() {
  const [report, setReport] = useState<ScanReport | null>(null)
  const [scanning, setScanning] = useState(false)
  const [error, setError] = useState('')

  async function startScan() {
    setScanning(true)
    setError('')

    const token = localStorage.getItem('token')
    const res = await fetch('/api/scan', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })

    if (res.ok) {
      const data = await res.json()
      setReport(data)
    } else {
      const err = await res.json()
      setError(err.error)
    }

    setScanning(false)
  }

  return (
    <div style={{ padding: 20, maxWidth: 800, margin: '0 auto' }}>
      <h2>Сканирование библиотеки</h2>

      <button
        onClick={startScan}
        disabled={scanning}
        style={{ padding: '12px 24px', fontSize: 16, marginBottom: 20 }}
      >
        {scanning ? 'Сканирование...' : 'Запустить сканирование'}
      </button>

      {error && (
        <div style={{ padding: 15, background: '#fee', borderRadius: 8, marginBottom: 20 }}>
          <strong>Ошибка:</strong> {error}
        </div>
      )}

      {report && (
        <div style={{ padding: 15, background: '#f5f5f5', borderRadius: 8 }}>
          <h3>Результаты последнего сканирования</h3>
          <table style={{ width: '100%' }}>
            <tbody>
              <tr>
                <td style={{ padding: 8 }}>Просканировано файлов:</td>
                <td style={{ padding: 8, fontWeight: 'bold' }}>{report.scanned}</td>
              </tr>
              <tr>
                <td style={{ padding: 8 }}>Добавлено:</td>
                <td style={{ padding: 8, fontWeight: 'bold', color: '#080' }}>{report.added}</td>
              </tr>
              <tr>
                <td style={{ padding: 8 }}>Обновлено:</td>
                <td style={{ padding: 8, fontWeight: 'bold', color: '#08f' }}>{report.updated}</td>
              </tr>
              <tr>
                <td style={{ padding: 8 }}>Удалено:</td>
                <td style={{ padding: 8, fontWeight: 'bold', color: '#f80' }}>{report.removed}</td>
              </tr>
              <tr>
                <td style={{ padding: 8 }}>Ошибок:</td>
                <td style={{ padding: 8, fontWeight: 'bold', color: '#d00' }}>{report.failed}</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
