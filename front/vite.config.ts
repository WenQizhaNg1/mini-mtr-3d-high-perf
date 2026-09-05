import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';

export default defineConfig({
    plugins: [vue()],
    server: {
        fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../assets/fonts', import.meta.url))] },
        port: 8080,
        strictPort: true,
    },
});
