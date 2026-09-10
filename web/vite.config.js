import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    rollupOptions: {
      input: {
        main: './index.html',
        admin: './admin.html'
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:4533',
      '/share': 'http://localhost:4533'
    }
  }
});
