import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  root: path.join(__dirname, 'src/renderer'),
  plugins: [react()],
  build: {
    outDir: path.join(__dirname, 'dist/renderer'),
    emptyOutDir: true
  },
  server: {
    port: 5173
  }
});
