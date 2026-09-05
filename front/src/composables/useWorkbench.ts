import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import type { LayerSpecification, StyleSpecification } from 'maplibre-gl';
import { getBasemap, getSources, listStyles, saveStyle, type MapStyle, type SourceCatalog } from '../api/styles';
import { listDatasets, publishDataset, type Dataset } from '../api/datasets';
import { request } from '../api/client';
import { composeStyle } from '../map/workbenchStyle';

export function useWorkbench() {
    const token = ref('');
    const styles = ref<Omit<MapStyle, 'layers'>[]>([]);
    const datasets = ref<Dataset[]>([]);
    const sources = shallowRef<SourceCatalog>({});
    const basemaps = shallowRef<Record<string, StyleSpecification>>({});
    const draft = ref<MapStyle>();
    const original = ref('');
    const selected = ref(0);
    const pendingEditor = ref(false);
    const busy = ref(false);
    const error = ref('');
    const notice = ref('');
    const verifiedStyle = shallowRef<StyleSpecification>();
    const dirty = computed(() => pendingEditor.value || JSON.stringify(draft.value) !== original.value && !!draft.value);
    const lifetime = new AbortController();
    const signal = lifetime.signal;

    async function run(action: () => Promise<void>) {
        if (busy.value) return;
        busy.value = true; error.value = ''; notice.value = '';
        try { await action(); }
        catch (cause) { if (!signal.aborted) error.value = String(cause); }
        finally { busy.value = false; }
    }
    async function refresh() {
        const [catalog, items, schemes] = await Promise.all([getSources(signal), token.value ? listDatasets(token.value, signal)
            : request<Dataset[]>('datasets', { signal }), listStyles(signal)]);
        sources.value = catalog; datasets.value = items; styles.value = schemes;
    }
    async function connect(value: string) {
        await run(async () => {
            const items = await listDatasets(value, signal);
            token.value = value; datasets.value = items; notice.value = '管理权限已启用，令牌仅保存在当前页面内存中。';
        });
    }
    function canDiscard() { return !dirty.value || window.confirm('有未保存的修改，确定放弃吗？'); }
    async function load(code: string) {
        if (!canDiscard()) return;
        await run(async () => {
            const value = await request<MapStyle>(`styles/${code}`, { signal });
            draft.value = value; original.value = JSON.stringify(value); selected.value = 0; pendingEditor.value = false;
            verifiedStyle.value = undefined;
        });
    }
    function create(copy: boolean) {
        if (busy.value || !canDiscard()) return;
        const code = window.prompt('新样式编码（小写字母开头，可含数字和连字符）', 'custom-map');
        if (!code) return;
        if (!/^[a-z][a-z0-9-]{0,63}$/.test(code) || styles.value.some(s => s.code === code)) { error.value = '编码无效或已存在'; return; }
        const value = copy && draft.value ? JSON.parse(JSON.stringify(draft.value)) as MapStyle
            : { code, name: '新地图样式', basemap: 'positron', layers: [] };
        value.code = code;
        if (copy) value.name += ' · 副本';
        draft.value = value; original.value = ''; selected.value = 0; pendingEditor.value = false;
    }
    const composed = computed(() => {
        if (!draft.value || !basemaps.value[draft.value.basemap]) return { style: undefined, errors: [] as string[] };
        try {
            const style = composeStyle(basemaps.value[draft.value.basemap], draft.value, sources.value);
            const errors = validateStyleMin(style).map(e => e.message);
            return { style: errors.length ? undefined : style, errors };
        } catch (cause) { return { style: undefined, errors: [String(cause)] }; }
    });
    async function save() {
        if (!draft.value || !token.value || pendingEditor.value || composed.value.errors.length) return;
        await run(async () => {
            await saveStyle(draft.value!, token.value, signal);
            original.value = JSON.stringify(draft.value);
            verifiedStyle.value = await request<StyleSpecification>(`styles/${draft.value!.code}/style.json`, { signal });
            await refresh();
            notice.value = '样式已保存；预览已读取服务端结果。';
        });
    }
    function updateLayer(layer: LayerSpecification) {
        if (draft.value) draft.value.layers[selected.value] = layer;
        verifiedStyle.value = undefined;
    }
    function move(offset: number) {
        if (!draft.value) return;
        const target = selected.value + offset;
        if (target < 0 || target >= draft.value.layers.length) return;
        const [layer] = draft.value.layers.splice(selected.value, 1);
        draft.value.layers.splice(target, 0, layer); selected.value = target;
    }
    async function publication(dataset: Dataset) {
        if (dataset.published && !window.confirm(`取消发布“${dataset.name}”？依赖它的图层将从新请求的样式中移除。`)) return;
        await run(async () => { await publishDataset(dataset.code, !dataset.published, token.value, signal); await refresh(); });
    }
    function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = ''; } }
    onBeforeRouteLeave(() => !busy.value && canDiscard());
    onMounted(async () => {
        window.addEventListener('beforeunload', beforeUnload);
        await run(async () => {
            const [light, dark] = await Promise.all([getBasemap('positron', signal), getBasemap('osm-liberty-dark', signal)]);
            basemaps.value = { positron: light, 'osm-liberty-dark': dark };
            await refresh();
            const value = await request<MapStyle>('styles/mtr-light', { signal });
            draft.value = value; original.value = JSON.stringify(value);
        });
    });
    onBeforeUnmount(() => { lifetime.abort(); token.value = ''; window.removeEventListener('beforeunload', beforeUnload); });
    return { token, styles, datasets, sources, basemaps, draft, selected, pendingEditor, busy, error, notice, dirty,
        composed, verifiedStyle, connect, load, create, save, run, refresh, updateLayer, move, publication };
}
