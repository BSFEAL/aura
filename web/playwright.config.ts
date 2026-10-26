import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  use: { baseURL: 'http://127.0.0.1:5173', headless: true, launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {} },
  webServer: [
    { command: '../.venv/bin/python -m uvicorn app:app --app-dir ../backend --host 127.0.0.1 --port 8000', url: 'http://127.0.0.1:8000/openapi.json', env: { AURA_API_TOKEN: 'e2e-local-token-at-least-24-characters', AURA_DB: `/tmp/aura-e2e-${process.pid}.db` } },
    { command: 'npm run dev', url: 'http://127.0.0.1:5173' }
  ]
});
