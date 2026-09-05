import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './browser-tests',
    use: { baseURL: 'http://127.0.0.1:8082', channel: 'chrome', viewport: { width: 1280, height: 850 } },
    webServer: { command: 'npm run dev -- --port 8082', url: 'http://127.0.0.1:8082', reuseExistingServer: false },
});
