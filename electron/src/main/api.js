const axios = require('axios');
const log = require('electron-log');

const createApiClient = () => {
  const baseURL = process.env.BACKEND_URL || 'http://localhost:8080';
  const token = process.env.BACKEND_AUTH_TOKEN;
  const apiKey = process.env.BACKEND_API_KEY;

  const headers = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (apiKey) {
    headers['X-API-Key'] = apiKey;
  }

  return axios.create({
    baseURL,
    headers,
    timeout: 60000
  });
};

const streamAsk = async (client, payload, onChunk) => {
  const maxAttempts = 3;
  let attempt = 0;
  let lastError;

  while (attempt < maxAttempts) {
    try {
      attempt += 1;
      log.info(`Ask attempt ${attempt}`);
      const response = await client.post('/api/ask', payload, {
        responseType: 'stream'
      });

      await new Promise((resolve, reject) => {
        let buffer = '';
        response.data.on('data', (data) => {
          buffer += data.toString();
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed.startsWith('data:')) {
              continue;
            }
            const jsonText = trimmed.replace('data:', '').trim();
            if (!jsonText) {
              continue;
            }
            try {
              const chunk = JSON.parse(jsonText);
              onChunk(chunk);
              if (chunk.finish) {
                resolve();
              }
            } catch (parseError) {
              log.warn('Failed to parse SSE chunk', parseError);
            }
          }
        });

        response.data.on('end', () => {
          resolve();
        });

        response.data.on('error', (error) => {
          reject(error);
        });
      });

      return;
    } catch (error) {
      lastError = error;
      const delay = Math.min(1000 * Math.pow(2, attempt), 8000);
      log.warn('Ask retrying after delay', { delay, error: error?.message });
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }

  throw lastError || new Error('请求失败');
};

module.exports = { createApiClient, streamAsk };
