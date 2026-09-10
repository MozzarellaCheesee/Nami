import { useState, useEffect } from 'react'

interface User {
  id: number
  username: string
  role: string
  library_id: number
  created_at: number
}

export function Users() {
  const [users, setUsers] = useState<User[]>([])
  const [folders, setFolders] = useState<Record<number, string[]>>({})
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editFolders, setEditFolders] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    loadUsers()
  }, [])

  async function loadUsers() {
    const token = localStorage.getItem('token')
    const res = await fetch('/api/users', {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (res.ok) {
      const data = await res.json()
      setUsers(data)
    }
  }

  async function loadFolders(userId: number) {
    const token = localStorage.getItem('token')
    const res = await fetch(`/api/users/${userId}/folders`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (res.ok) {
      const data = await res.json()
      setFolders(prev => ({ ...prev, [userId]: data }))
    }
  }

  async function deleteUser(userId: number) {
    if (!confirm('Удалить пользователя?')) return

    const token = localStorage.getItem('token')
    const res = await fetch(`/api/users/${userId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` }
    })

    if (res.ok) {
      loadUsers()
      setError('')
    } else {
      const err = await res.json()
      setError(err.error)
    }
  }

  async function saveFolders(userId: number) {
    const token = localStorage.getItem('token')
    const list = editFolders.split('\n').filter(s => s.trim())
    const res = await fetch(`/api/users/${userId}/folders`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify(list)
    })

    if (res.ok) {
      setEditingId(null)
      loadFolders(userId)
      setError('')
    } else {
      const err = await res.json()
      setError(err.error)
    }
  }

  function startEdit(userId: number) {
    loadFolders(userId)
    setEditingId(userId)
    setEditFolders((folders[userId] || []).join('\n'))
  }

  return (
    <div style={{ padding: 20, maxWidth: 1000, margin: '0 auto' }}>
      <h2>Пользователи</h2>
      {error && <div style={{ color: '#d00', marginBottom: 10 }}>{error}</div>}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ borderBottom: '2px solid #ddd' }}>
            <th style={{ padding: 8, textAlign: 'left' }}>ID</th>
            <th style={{ padding: 8, textAlign: 'left' }}>Имя</th>
            <th style={{ padding: 8, textAlign: 'left' }}>Роль</th>
            <th style={{ padding: 8, textAlign: 'left' }}>Библиотека</th>
            <th style={{ padding: 8, textAlign: 'left' }}>Создан</th>
            <th style={{ padding: 8, textAlign: 'left' }}>Действия</th>
          </tr>
        </thead>
        <tbody>
          {users.map(user => (
            <tr key={user.id} style={{ borderBottom: '1px solid #eee' }}>
              <td style={{ padding: 8 }}>{user.id}</td>
              <td style={{ padding: 8 }}>{user.username}</td>
              <td style={{ padding: 8 }}>{user.role}</td>
              <td style={{ padding: 8 }}>{user.library_id}</td>
              <td style={{ padding: 8 }}>
                {new Date(user.created_at * 1000).toLocaleDateString()}
              </td>
              <td style={{ padding: 8 }}>
                <button onClick={() => startEdit(user.id)} style={{ padding: '4px 12px', marginRight: 8 }}>
                  Папки
                </button>
                {user.role !== 'owner' && (
                  <button onClick={() => deleteUser(user.id)} style={{ padding: '4px 12px' }}>
                    Удалить
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editingId !== null && (
        <div style={{ marginTop: 20, padding: 15, background: '#f5f5f5', borderRadius: 8 }}>
          <h3>Папки доступа (по одной на строку)</h3>
          <textarea
            value={editFolders}
            onChange={e => setEditFolders(e.target.value)}
            rows={8}
            style={{ width: '100%', fontFamily: 'monospace', fontSize: 14 }}
          />
          <div style={{ marginTop: 10 }}>
            <button onClick={() => saveFolders(editingId)} style={{ padding: '8px 16px', marginRight: 8 }}>
              Сохранить
            </button>
            <button onClick={() => setEditingId(null)} style={{ padding: '8px 16px' }}>
              Отмена
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
