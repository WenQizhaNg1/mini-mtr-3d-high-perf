export function useDisplay(language: () => 'en' | 'zh') {
    const text = (zh: string, en: string) => language() === 'zh' ? zh : en;
    const name = (value?: { nameEn: string; nameZh: string }, fallback = '—') =>
        value ? text(value.nameZh, value.nameEn) : fallback;
    const formatTime = (value: number | string | null, date = false) => value === null ? '—' :
        new Intl.DateTimeFormat(language() === 'zh' ? 'zh-HK' : 'en-GB', {
            timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
            ...(date ? { year: 'numeric', month: '2-digit', day: '2-digit' } as const : {}),
        }).format(new Date(value));
    return { text, name, formatTime };
}
