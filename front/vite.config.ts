import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), '');
    return {
        plugins: [vue()],
        server: {
            // Preserve Host so Spring sees browser writes as same-origin through the dev proxy.
            proxy: { '/api': { target: process.env.API_PROXY_TARGET || env.API_PROXY_TARGET || 'http://127.0.0.1:3002', changeOrigin: false } },
            fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../assets/fonts', import.meta.url))] },
            port: 8080,
            strictPort: true,
        },
    };
});
