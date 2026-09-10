import React from 'react';
import ReactDOM from 'react-dom/client';
import { AdminPanel } from './admin/AdminPanel.jsx';

// Глобальные стили и анимации
const globalStyles = document.createElement('style');
globalStyles.textContent = `
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }

  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }

  button:hover {
    opacity: 0.9;
  }

  button:active {
    transform: scale(0.98);
  }

  input:focus,
  select:focus,
  button:focus {
    outline: 2px solid #C24A34;
    outline-offset: 2px;
  }

  input::placeholder {
    opacity: 0.5;
  }
`;
document.head.appendChild(globalStyles);

// Монтирование React-приложения
const root = ReactDOM.createRoot(document.getElementById('app'));
root.render(<AdminPanel />);
