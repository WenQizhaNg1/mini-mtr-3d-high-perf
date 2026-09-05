import { ref, watch } from 'vue';
import { defineStore } from 'pinia';

export const usePreferencesStore = defineStore('preferences', () => {
    const language = ref<'en' | 'zh'>(readLanguage());
    const powerSave = ref(false);
    watch(language, value => {
        document.documentElement.lang = value === 'zh' ? 'zh-HK' : 'en';
        try { localStorage.setItem('mtr-language', value); }
        catch (cause) { console.warn('Cannot save language preference', cause); }
    }, { immediate: true });
    return { language, powerSave };
});

function readLanguage(): 'en' | 'zh' {
    try {
        const saved = localStorage.getItem('mtr-language');
        if (saved === 'en' || saved === 'zh') return saved;
    } catch (cause) { console.warn('Language preference unavailable', cause); }
    return navigator.language.startsWith('zh') ? 'zh' : 'en';
}
