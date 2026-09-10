import React, { useState, useEffect } from 'react';
import { api } from '../api.js';
import { NamiColors } from '../colors.js';

// Компонент управления пользователями
export function Users() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingFolders, setEditingFolders] = useState(null); // {userId, folders: string[]}

  // Загрузка списка пользователей
  useEffect(() => {
    loadUsers();
  }, []);

  async function loadUsers() {
    setLoading(true);
    setError('');
    try {
      const data = await api.getUsers();
      setUsers(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  // Удаление пользователя
  async function handleDelete(userId, username) {
    if (!confirm(`Удалить пользователя ${username}? Все его данные будут стерты.`)) {
      return;
    }

    try {
      await api.deleteUser(userId);
      setUsers(users.filter(u => u.id !== userId));
    } catch (err) {
      alert(`Ошибка: ${err.message}`);
    }
  }

  // Открыть редактор папок
  async function startEditingFolders(userId) {
    try {
      const folders = await api.getUserFolders(userId);
      setEditingFolders({ userId, folders: folders || [] });
    } catch (err) {
      alert(`Ошибка загрузки папок: ${err.message}`);
    }
  }

  // Сохранение папок
  async function saveFolders() {
    if (!editingFolders) return;

    try {
      await api.setUserFolders(editingFolders.userId, editingFolders.folders);
      setEditingFolders(null);
    } catch (err) {
      alert(`Ошибка: ${err.message}`);
    }
  }

  // Добавить папку в список
  function addFolder() {
    if (!editingFolders) return;
    setEditingFolders({
      ...editingFolders,
      folders: [...editingFolders.folders, '']
    });
  }

  // Изменить путь папки
  function updateFolder(index, value) {
    if (!editingFolders) return;
    const newFolders = [...editingFolders.folders];
    newFolders[index] = value;
    setEditingFolders({ ...editingFolders, folders: newFolders });
  }

  // Удалить папку из списка
  function removeFolder(index) {
    if (!editingFolders) return;
    const newFolders = editingFolders.folders.filter((_, i) => i !== index);
    setEditingFolders({ ...editingFolders, folders: newFolders });
  }

  if (loading) {
    return (
      <div style={styles.container}>
        <div style={styles.loading}>Загрузка...</div>
      </div>
    );
  }

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>Пользователи</h2>

      {error && <div style={styles.error}>{error}</div>}

      {users.length === 0 ? (
        <div style={styles.empty}>Нет пользователей</div>
      ) : (
        <div style={styles.usersList}>
          {users.map(user => (
            <div key={user.id} style={styles.userCard}>
              <div style={styles.userHeader}>
                <div style={styles.userInfo}>
                  <div style={styles.username}>{user.username}</div>
                  <div style={styles.userMeta}>
                    <span style={{
                      ...styles.badge,
                      backgroundColor: user.role === 'owner' ? NamiColors.Shu : NamiColors.Ai
                    }}>
                      {user.role}
                    </span>
                    {user.library_id && (
                      <span style={styles.libraryId}>
                        Библиотека: {user.library_id}
                      </span>
                    )}
                  </div>
                </div>
                <div style={styles.userActions}>
                  <button
                    onClick={() => startEditingFolders(user.id)}
                    style={styles.actionButton}
                  >
                    Папки
                  </button>
                  {user.role !== 'owner' && (
                    <button
                      onClick={() => handleDelete(user.id, user.username)}
                      style={{...styles.actionButton, backgroundColor: NamiColors.Shu}}
                    >
                      Удалить
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Модальное окно редактирования папок */}
      {editingFolders && (
        <div style={styles.modal}>
          <div style={styles.modalContent}>
            <h3 style={styles.modalTitle}>Доступные папки</h3>
            <div style={styles.modalHint}>
              Пустой список = доступ ко всем папкам (shared-режим)
            </div>

            <div style={styles.foldersList}>
              {editingFolders.folders.map((folder, idx) => (
                <div key={idx} style={styles.folderRow}>
                  <input
                    type="text"
                    value={folder}
                    onChange={(e) => updateFolder(idx, e.target.value)}
                    placeholder="/путь/к/папке"
                    style={styles.folderInput}
                  />
                  <button
                    onClick={() => removeFolder(idx)}
                    style={styles.removeButton}
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>

            <button onClick={addFolder} style={styles.addButton}>
              + Добавить папку
            </button>

            <div style={styles.modalActions}>
              <button onClick={saveFolders} style={styles.saveButton}>
                Сохранить
              </button>
              <button
                onClick={() => setEditingFolders(null)}
                style={styles.cancelButton}
              >
                Отмена
              </button>
            </div>
          </div>
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
  loading: {
    padding: '40px',
    textAlign: 'center',
    color: NamiColors.Paper70
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
  empty: {
    padding: '40px',
    textAlign: 'center',
    color: NamiColors.Paper40,
    backgroundColor: NamiColors.Ink800,
    borderRadius: '8px'
  },
  usersList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px'
  },
  userCard: {
    backgroundColor: NamiColors.Ink800,
    padding: '16px',
    borderRadius: '8px'
  },
  userHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: '16px'
  },
  userInfo: {
    flex: 1
  },
  username: {
    fontSize: '18px',
    fontWeight: '600',
    marginBottom: '8px',
    color: NamiColors.Paper100
  },
  userMeta: {
    display: 'flex',
    gap: '8px',
    alignItems: 'center'
  },
  badge: {
    padding: '4px 12px',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600',
    color: NamiColors.Paper100
  },
  libraryId: {
    fontSize: '13px',
    color: NamiColors.Paper70
  },
  userActions: {
    display: 'flex',
    gap: '8px'
  },
  actionButton: {
    padding: '8px 16px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    cursor: 'pointer',
    fontWeight: '500'
  },
  modal: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1000
  },
  modalContent: {
    backgroundColor: NamiColors.Ink800,
    padding: '24px',
    borderRadius: '12px',
    maxWidth: '600px',
    width: '90%',
    maxHeight: '80vh',
    overflow: 'auto'
  },
  modalTitle: {
    fontSize: '22px',
    fontWeight: '600',
    marginBottom: '8px',
    color: NamiColors.Paper100
  },
  modalHint: {
    fontSize: '13px',
    color: NamiColors.Paper40,
    marginBottom: '20px'
  },
  foldersList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    marginBottom: '16px'
  },
  folderRow: {
    display: 'flex',
    gap: '8px'
  },
  folderInput: {
    flex: 1,
    padding: '10px 12px',
    backgroundColor: NamiColors.Ink700,
    border: `1px solid ${NamiColors.Ink600}`,
    borderRadius: '6px',
    color: NamiColors.Paper100,
    fontSize: '14px',
    fontFamily: 'monospace'
  },
  removeButton: {
    width: '36px',
    padding: '8px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Shu,
    border: 'none',
    borderRadius: '6px',
    fontSize: '16px',
    cursor: 'pointer'
  },
  addButton: {
    width: '100%',
    padding: '10px',
    backgroundColor: NamiColors.Ink700,
    color: NamiColors.Paper100,
    border: `1px dashed ${NamiColors.Ink500}`,
    borderRadius: '6px',
    fontSize: '14px',
    cursor: 'pointer',
    marginBottom: '20px'
  },
  modalActions: {
    display: 'flex',
    gap: '12px'
  },
  saveButton: {
    flex: 1,
    padding: '12px',
    backgroundColor: NamiColors.Wakaba,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '6px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  cancelButton: {
    flex: 1,
    padding: '12px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '6px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer'
  }
};
