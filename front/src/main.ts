import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { router } from './router';
import 'maplibre-gl/dist/maplibre-gl.css';
import './styles/base.css';
import './styles/panels.css';
import App from './App.vue';

createApp(App).use(createPinia()).use(router).mount('#app');
