import React, { useState } from 'react';
import { api } from '../api.js';
import { NamiColors } from '../colors.js';

// Компонент создания и управления инвайтами
export function Invites() {
  const [invites, setInvites] = useState([]);
  const [role, setRole] = useState('user');
  const [libraryId, setLibraryId] = useState('');
  const [ttlSecs, setTtlSecs] = useState(86400); // 24 часа по умолчанию
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // Создание нового инвайта
  async function handleCreate() {
    setLoading(true);
    setError('');
    try {
      const result = await api.createInvite(
        role,
        libraryId || null,
        ttlSecs
      );

      // Добавляем созданный инвайт в список
      const newInvite = {
        token: result.token,
        url: result.url || `${window.location.origin}/invites/${result.token}/accept`,
        role,
        library_id: libraryId || null,
        expires_at: Date.now() + ttlSecs * 1000,
        created_at: Date.now()
      };

      setInvites([newInvite, ...invites]);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  // Копирование ссылки в буфер
  function copyToClipboard(url) {
    navigator.clipboard.writeText(url);
  }

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>Инвайты</h2>

      {/* Форма создания инвайта */}
      <div style={styles.form}>
        <div style={styles.formGroup}>
          <label style={styles.label}>Роль:</label>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            style={styles.select}
          >
            <option value="user">Пользователь</option>
            <option value="owner">Владелец</option>
          </select>
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>ID библиотеки (опционально):</label>
          <input
            type="text"
            value={libraryId}
            onChange={(e) => setLibraryId(e.target.value)}
            placeholder="Оставьте пустым для shared-режима"
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Срок действия (секунд):</label>
          <input
            type="number"
            value={ttlSecs}
            onChange={(e) => setTtlSecs(parseInt(e.target.value))}
            min="60"
            style={styles.input}
          />
          <div style={styles.hint}>
            {Math.floor(ttlSecs / 3600)} ч {Math.floor((ttlSecs % 3600) / 60)} мин
          </div>
        </div>

        <button
          onClick={handleCreate}
          disabled={loading}
          style={{...styles.button, opacity: loading ? 0.5 : 1}}
        >
          {loading ? 'Создаю...' : 'Создать инвайт'}
        </button>

        {error && <div style={styles.error}>{error}</div>}
      </div>

      {/* Список активных инвайтов */}
      <div style={styles.invitesList}>
        <h3 style={styles.subtitle}>Активные инвайты</h3>
        {invites.length === 0 ? (
          <div style={styles.empty}>Нет активных инвайтов</div>
        ) : (
          invites.map((invite, idx) => (
            <div key={idx} style={styles.inviteCard}>
              <div style={styles.inviteHeader}>
                <span style={styles.inviteRole}>{invite.role}</span>
                {invite.library_id && (
                  <span style={styles.inviteLibrary}>
                    Библиотека: {invite.library_id}
                  </span>
                )}
              </div>
              <div style={styles.inviteUrl}>{invite.url}</div>
              <div style={styles.inviteFooter}>
                <span style={styles.inviteTime}>
                  Истекает: {new Date(invite.expires_at).toLocaleString('ru')}
                </span>
                <button
                  onClick={() => copyToClipboard(invite.url)}
                  style={styles.copyButton}
                >
                  Копировать
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '24px',
    maxWidth: '800px',
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
  form: {
    backgroundColor: NamiColors.Ink800,
    padding: '20px',
    borderRadius: '8px',
    marginBottom: '32px'
  },
  formGroup: {
    marginBottom: '16px'
  },
  label: {
    display: 'block',
    marginBottom: '8px',
    fontSize: '14px',
    color: NamiColors.Paper70
  },
  input: {
    width: '100%',
    padding: '10px 12px',
    backgroundColor: NamiColors.Ink700,
    border: `1px solid ${NamiColors.Ink600}`,
    borderRadius: '6px',
    color: NamiColors.Paper100,
    fontSize: '14px',
    boxSizing: 'border-box'
  },
  select: {
    width: '100%',
    padding: '10px 12px',
    backgroundColor: NamiColors.Ink700,
    border: `1px solid ${NamiColors.Ink600}`,
    borderRadius: '6px',
    color: NamiColors.Paper100,
    fontSize: '14px'
  },
  hint: {
    marginTop: '4px',
    fontSize: '12px',
    color: NamiColors.Paper40
  },
  button: {
    width: '100%',
    padding: '12px',
    backgroundColor: NamiColors.Shu,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '6px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer',
    marginTop: '8px'
  },
  error: {
    marginTop: '12px',
    padding: '12px',
    backgroundColor: NamiColors.Ink700,
    border: `1px solid ${NamiColors.Shu}`,
    borderRadius: '6px',
    color: NamiColors.Shu,
    fontSize: '14px'
  },
  invitesList: {
    marginTop: '32px'
  },
  empty: {
    padding: '40px',
    textAlign: 'center',
    color: NamiColors.Paper40,
    backgroundColor: NamiColors.Ink800,
    borderRadius: '8px'
  },
  inviteCard: {
    backgroundColor: NamiColors.Ink800,
    padding: '16px',
    borderRadius: '8px',
    marginBottom: '12px'
  },
  inviteHeader: {
    display: 'flex',
    gap: '12px',
    marginBottom: '8px'
  },
  inviteRole: {
    padding: '4px 12px',
    backgroundColor: NamiColors.Ai,
    color: NamiColors.Paper100,
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600'
  },
  inviteLibrary: {
    padding: '4px 12px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper70,
    borderRadius: '4px',
    fontSize: '12px'
  },
  inviteUrl: {
    padding: '8px',
    backgroundColor: NamiColors.Ink700,
    borderRadius: '4px',
    fontSize: '13px',
    fontFamily: 'monospace',
    color: NamiColors.Paper70,
    wordBreak: 'break-all',
    marginBottom: '8px'
  },
  inviteFooter: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  inviteTime: {
    fontSize: '12px',
    color: NamiColors.Paper40
  },
  copyButton: {
    padding: '6px 16px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '4px',
    fontSize: '13px',
    cursor: 'pointer'
  }
};
