const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const log = require('electron-log');
const Store = require('electron-store');
const { createApiClient, streamAsk } = require('./api');

const store = new Store({
  name: 'history',
  defaults: {
    items: []
  }
});

const createWindow = () => {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    win.loadURL(process.env.VITE_DEV_SERVER_URL).catch((error) => {
      log.error('Failed to load dev server', error);
    });
  } else {
    win.loadFile(path.join(__dirname, '..', '..', 'dist', 'renderer', 'index.html')).catch((error) => {
      log.error('Failed to load renderer HTML', error);
    });
  }

  return win;
};

const appendHistory = (record) => {
  const items = store.get('items', []);
  store.set('items', [record, ...items]);
};

app.whenReady().then(() => {
  log.info('App ready');
  const win = createWindow();

  ipcMain.handle('ask', async (event, payload) => {
    try {
      const { question, sessionId } = payload || {};
      const apiClient = createApiClient();
      let answerBuffer = '';

      log.info('Sending question', { question, sessionId });

      await streamAsk(apiClient, { question, sessionId }, (chunk) => {
        if (chunk.delta) {
          answerBuffer += chunk.delta;
        }
        win.webContents.send('ai-reply', chunk);
      });

      appendHistory({
        id: sessionId,
        question,
        answer: answerBuffer,
        ts: Date.now()
      });

      log.info('Question completed', { sessionId });
      return { done: true };
    } catch (error) {
      const message = error?.message || '网络异常，请稍后重试';
      log.error('Ask failed', error);
      win.webContents.send('error', { message });
      dialog.showErrorBox('网络异常', message);
      return { done: false, message };
    }
  });

  ipcMain.handle('get-history', async () => {
    try {
      return store.get('items', []);
    } catch (error) {
      log.error('Get history failed', error);
      return [];
    }
  });

  ipcMain.handle('clear-history', async () => {
    try {
      store.set('items', []);
      return { ok: true };
    } catch (error) {
      log.error('Clear history failed', error);
      return { ok: false };
    }
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
