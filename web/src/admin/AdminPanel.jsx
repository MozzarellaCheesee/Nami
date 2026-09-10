import React, { useState, useEffect } from 'react';
import { api } from '../api.js';
import { NamiColors } from '../colors.js';
import { Invites } from './Invites.jsx';
import { Users } from './Users.jsx';
import { ScannerLogs } from './ScannerLogs.jsx';

// Главный компонент админ-панели
export function AdminPanel() {
  const [currentUser, setCurrentUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('users');
  const [error, setError] = useState('');

  // Проверка прав доступа при загрузке
  useEffect(() => {
    checkAccess();
  }, []);

  async function checkAccess() {
    try {
      const user = await api.getMe();
      setCurrentUser(user);

      if (user.role !== 'owner') {
        setError('Доступ запрещен. Только владелец может использовать админ-панель.');
      }
    } catch (err) {
      setError(`Ошибка авторизации: ${err.message}`);
    } finally {
      setLoading(false);
    }
  }

  async function handleLogout() {
    try {
      await api.logout();
      window.location.href = '/';
    } catch (err) {
      alert(`Ошибка выхода: ${err.message}`);
    }
  }

  if (loading) {
    return (
      <div style={styles.loadingContainer}>
        <div style={styles.loadingText}>Загрузка...</div>
      </div>
    );
  }

  if (error || !currentUser || currentUser.role !== 'owner') {
    return (
      <div style={styles.errorContainer}>
        <div style={styles.errorCard}>
          <h2 style={styles.errorTitle}>Доступ запрещен</h2>
          <p style={styles.errorMessage}>
            {error || 'Только владелец может использовать админ-панель'}
          </p>
          <button onClick={handleLogout} style={styles.logoutButton}>
            Выход
          </button>
        </div>
      </div>
    );
  }

  return (
    <div style={styles.container}>
      {/* Шапка */}
      <header style={styles.header}>
        <div style={styles.headerContent}>
          <h1 style={styles.logo}>Nami Admin</h1>
          <div style={styles.userInfo}>
            <span style={styles.username}>{currentUser.username}</span>
            <button onClick={handleLogout} style={styles.logoutBtn}>
              Выход
            </button>
          </div>
        </div>
      </header>

      {/* Навигация */}
      <nav style={styles.nav}>
        <button
          onClick={() => setActiveTab('users')}
          style={{
            ...styles.navButton,
            ...(activeTab === 'users' ? styles.navButtonActive : {})
          }}
        >
          Пользователи
        </button>
        <button
          onClick={() => setActiveTab('invites')}
          style={{
            ...styles.navButton,
            ...(activeTab === 'invites' ? styles.navButtonActive : {})
          }}
        >
          Инвайты
        </button>
        <button
          onClick={() => setActiveTab('scanner')}
          style={{
            ...styles.navButton,
            ...(activeTab === 'scanner' ? styles.navButtonActive : {})
          }}
        >
          Сканирование
        </button>
      </nav>

      {/* Контент */}
      <main style={styles.main}>
        {activeTab === 'users' && <Users />}
        {activeTab === 'invites' && <Invites />}
        {activeTab === 'scanner' && <ScannerLogs />}
      </main>
    </div>
  );
}

const styles = {
  loadingContainer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    backgroundColor: NamiColors.Ink900
  },
  loadingText: {
    fontSize: '18px',
    color: NamiColors.Paper70
  },
  errorContainer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    backgroundColor: NamiColors.Ink900,
    padding: '20px'
  },
  errorCard: {
    backgroundColor: NamiColors.Ink800,
    padding: '32px',
    borderRadius: '12px',
    maxWidth: '500px',
    textAlign: 'center',
    border: `1px solid ${NamiColors.Shu}`
  },
  errorTitle: {
    fontSize: '24px',
    fontWeight: '600',
    color: NamiColors.Shu,
    marginBottom: '16px'
  },
  errorMessage: {
    fontSize: '16px',
    color: NamiColors.Paper70,
    marginBottom: '24px',
    lineHeight: '1.5'
  },
  logoutButton: {
    padding: '12px 24px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '8px',
    fontSize: '16px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  container: {
    minHeight: '100vh',
    backgroundColor: NamiColors.Ink900
  },
  header: {
    backgroundColor: NamiColors.Ink800,
    borderBottom: `1px solid ${NamiColors.Ink600}`,
    padding: '16px 24px'
  },
  headerContent: {
    maxWidth: '1200px',
    margin: '0 auto',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  logo: {
    fontSize: '24px',
    fontWeight: '700',
    color: NamiColors.Shu,
    margin: 0
  },
  userInfo: {
    display: 'flex',
    alignItems: 'center',
    gap: '16px'
  },
  username: {
    fontSize: '15px',
    color: NamiColors.Paper70
  },
  logoutBtn: {
    padding: '8px 16px',
    backgroundColor: NamiColors.Ink600,
    color: NamiColors.Paper100,
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    cursor: 'pointer'
  },
  nav: {
    backgroundColor: NamiColors.Ink800,
    borderBottom: `1px solid ${NamiColors.Ink600}`,
    padding: '0 24px',
    display: 'flex',
    gap: '4px'
  },
  navButton: {
    padding: '14px 20px',
    backgroundColor: 'transparent',
    color: NamiColors.Paper70,
    border: 'none',
    borderBottom: '2px solid transparent',
    fontSize: '15px',
    fontWeight: '500',
    cursor: 'pointer',
    transition: 'all 0.2s'
  },
  navButtonActive: {
    color: NamiColors.Paper100,
    borderBottomColor: NamiColors.Shu
  },
  main: {
    minHeight: 'calc(100vh - 120px)'
  }
};
