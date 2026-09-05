import { createRouter, createWebHistory } from 'vue-router';

export const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes: [
        { path: '/', name: 'map', component: () => import('../views/MapView.vue') },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
});
