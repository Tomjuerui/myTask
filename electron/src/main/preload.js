const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  ask: async (question, sessionId) => {
    try {
      return await ipcRenderer.invoke('ask', { question, sessionId });
    } catch (error) {
      return { done: false, message: error?.message || '请求失败' };
    }
  },
  getHistory: async () => {
    try {
      return await ipcRenderer.invoke('get-history');
    } catch (error) {
      return [];
    }
  },
  clearHistory: async () => {
    try {
      return await ipcRenderer.invoke('clear-history');
    } catch (error) {
      return { ok: false };
    }
  }
});

ipcRenderer.on('ai-reply', (_event, chunk) => {
  window.dispatchEvent(new CustomEvent('ai-reply', { detail: chunk }));
});

ipcRenderer.on('error', (_event, payload) => {
  window.dispatchEvent(new CustomEvent('error', { detail: payload }));
});
