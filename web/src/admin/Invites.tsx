import { useState, useEffect } from 'react'

interface Invite {
  token: string
  role: string
  library_id: number
  created_by: number
  expires_at: number
}

export function Invites() {
  const [invites, setInvites] = useState<Invite[]>([])
  const [role, setRole] = useState('user')
  const [libraryId, setLibraryId] = useState(0)
  const [ttlDays, setTtlDays] = useState(7)
  const [error, setError] = useState('')

  useEffect(() => {
    loadInvites()
  }, [])

  async function loadInvites() {
    const token = localStorage.getItem('token')
    const res = await fetch('/api/invites', {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (res.ok) {
      const data = await res.json()
      setInvites(data)
    }
  }

  async function createInvite() {
    const token = localStorage.getItem('token')
    const res = await fetch('/api/invites', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({
        role,
        library_id: libraryId,
        ttl_secs: ttlDays * 86400
      })
    })

    if (res.ok) {
      loadInvites()
      setError('')
    } else {
      const err = await res.json()
      setError(err.error)
    }
  }

  function copyLink(token: string) {
    const url = `${location.origin}/api/invites/${token}/accept`
    navigator.clipboard.writeText(url)
  }

  return (
    <div style={{ padding: 20, maxWidth: 800, margin: '0 auto' }}>
      <h2>Инвайты</h2>

      <div style={{ marginBottom: 20, padding: 15, background: '#f5f5f5', borderRadius: 8 }}>
        <h3>Создать инвайт</h3>
        <div style={{ display: 'grid', gap: 10, marginBottom: 10 }}>
          <label>
            Роль:
            <select value={role} onChange={e => setRole(e.target.value)} style={{ marginLeft: 10 }}>
              <option value="user">user</option>
              <option value="guest">guest</option>
            </select>
          </label>
          <label>
            Библиотека ID:
            <input
              type="number"
              value={libraryId}
              onChange={e => setLibraryId(Number(e.target.value))}
              style={{ marginLeft: 10, width: 80 }}
            />
          </label>
          <label>
            Срок действия (дней):
            <input
              type="number"
              value={ttlDays}
              onChange={e => setTtlDays(Number(e.target.value))}
              style={{ marginLeft: 10, width: 80 }}
            />
          </label>
        </div>
        <button onClick={createInvite} style={{ padding: '8px 16px' }}>
          Создать
        </button>
        {error && <div style={{ color: '#d00', marginTop: 10 }}>{error}</div>}
      </div>

      <h3>Активные инвайты</h3>
      {invites.length === 0 ? (
        <p>Нет активных инвайтов</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #ddd' }}>
              <th style={{ padding: 8, textAlign: 'left' }}>Токен</th>
              <th style={{ padding: 8, textAlign: 'left' }}>Роль</th>
              <th style={{ padding: 8, textAlign: 'left' }}>Истекает</th>
              <th style={{ padding: 8, textAlign: 'left' }}>Действия</th>
            </tr>
          </thead>
          <tbody>
            {invites.map(inv => (
              <tr key={inv.token} style={{ borderBottom: '1px solid #eee' }}>
                <td style={{ padding: 8, fontFamily: 'monospace', fontSize: 12 }}>
                  {inv.token.slice(0, 12)}...
                </td>
                <td style={{ padding: 8 }}>{inv.role}</td>
                <td style={{ padding: 8 }}>
                  {new Date(inv.expires_at * 1000).toLocaleString()}
                </td>
                <td style={{ padding: 8 }}>
                  <button onClick={() => copyLink(inv.token)} style={{ padding: '4px 12px' }}>
                    Копировать ссылку
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
