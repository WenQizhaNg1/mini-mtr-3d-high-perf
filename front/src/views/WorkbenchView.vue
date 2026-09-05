<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import type { FeatureCollection } from 'geojson';
import { useWorkbench } from '../composables/useWorkbench';
import { defaultLayer } from '../map/workbenchStyle';
import PreviewMap from '../components/workbench/PreviewMap.vue';
import LayerEditor from '../components/workbench/LayerEditor.vue';
import ImportDialog from '../components/workbench/ImportDialog.vue';
import '../styles/workbench.css';

const { token, styles, datasets, sources, basemaps, draft, selected, pendingEditor, busy, error, notice, dirty,
    composed, verifiedStyle, connect, load, create, save, run, refresh, updateLayer, move, publication } = useWorkbench();
const enteredToken = ref('');
const importing = ref(false);
const importBusy = ref(false);
onBeforeRouteLeave(() => !importBusy.value);
const sample = ref<FeatureCollection>();
const bounds = ref<number[]>();
const mapError = ref('');
const tab = ref<'layers' | 'datasets'>('layers');
const sourceKey = ref('mtr');
const sourceLayer = ref('mtr_routes');
const layerType = ref('line');
const newLayerId = ref('');
const selectedLayer = computed(() => draft.value?.layers[selected.value]);
const previewStyle = computed(() => !dirty.value && verifiedStyle.value ? verifiedStyle.value : composed.value.style);
const sourceLayers = computed(() => sources.value[sourceKey.value]?.layers || []);
const geometry = computed(() => sourceLayers.value.find(l => l.id === sourceLayer.value)?.geometry.toUpperCase() || '');
const layerTypes = computed(() => [...(geometry.value.includes('POINT') ? ['circle'] : []),
    ...(geometry.value.includes('POLYGON') ? ['fill', 'fill-extrusion'] : []),
    ...(geometry.value.includes('POLYGON') || geometry.value.includes('LINESTRING') ? ['line'] : []), 'symbol']);
watch(sourceKey, () => { sourceLayer.value = sourceLayers.value[0]?.id || ''; });
watch(geometry, () => { layerType.value = layerTypes.value[0]; });
watch(previewStyle, () => { mapError.value = ''; });
function select(index: number) {
    if (pendingEditor.value && !window.confirm('放弃尚未应用的 JSON 修改？')) return;
    pendingEditor.value = false; selected.value = index;
}
function addLayer() {
    if (!draft.value) return;
    const id = newLayerId.value.trim();
    if (!/^[a-zA-Z][a-zA-Z0-9:_-]{0,99}$/.test(id) || draft.value.layers.some(layer => layer.id === id)) { error.value = '请输入唯一的有效图层 ID'; return; }
    draft.value.layers.push(defaultLayer(id, sourceKey.value, sourceLayer.value, layerType.value));
    selected.value = draft.value.layers.length - 1; newLayerId.value = ''; error.value = '';
    if (sources.value[sourceKey.value]?.bounds?.length === 4) bounds.value = [...sources.value[sourceKey.value].bounds!];
}
function copyLayer() {
    if (!draft.value || !selectedLayer.value) return;
    const layer = JSON.parse(JSON.stringify(selectedLayer.value));
    let index = 1;
    while (draft.value.layers.some(item => item.id === `${layer.id}-copy-${index}`)) index++;
    layer.id += `-copy-${index}`; draft.value.layers.splice(selected.value + 1, 0, layer); selected.value++;
}
function removeLayer() {
    if (!draft.value || !selectedLayer.value || !window.confirm(`删除图层“${selectedLayer.value.id}”？保存后生效。`)) return;
    draft.value.layers.splice(selected.value, 1); selected.value = Math.max(0, selected.value - 1);
}
async function imported() {
    importing.value = false; sample.value = undefined; tab.value = 'datasets';
    await run(async () => { await refresh(); notice.value = '数据已导入为草稿。发布后可作为图层数据源。'; });
}
async function login() { await connect(enteredToken.value); enteredToken.value = ''; }
</script>

<template>
    <main class="wb">
        <header class="wb-header">
            <div class="wb-brand"><span class="wb-brand-mark">M</span><div><strong>图层工作台</strong><small>DATA & STYLE STUDIO</small></div></div>
            <div class="wb-toolbar">
                <select aria-label="地图样式" :value="draft?.code" :disabled="busy" @change="load(($event.target as HTMLSelectElement).value); ($event.target as HTMLSelectElement).value = draft?.code || ''">
                    <option v-for="style in styles" :key="style.code" :value="style.code">{{ style.name }}</option>
                    <option v-if="draft && !styles.some(s => s.code === draft!.code)" :value="draft.code">{{ draft.name }}（新建）</option>
                </select>
                <button :disabled="busy" @click="create(false)">新建</button><button :disabled="busy || !draft" @click="create(true)">复制方案</button>
            </div>
            <span class="wb-save-status">{{ busy ? '处理中…' : dirty ? '有未保存的修改' : '已与服务器同步' }}</span>
            <button class="wb-primary" :disabled="busy || !token || !draft || pendingEditor || !!composed.errors.length || !dirty" @click="save">保存样式</button>
            <RouterLink to="/">返回首页</RouterLink>
        </header>
        <div class="wb-access">
            <template v-if="!token"><span>只读预览 · 输入管理令牌以导入和保存</span><form @submit.prevent="login"><input v-model="enteredToken" aria-label="管理令牌" type="password" autocomplete="off" placeholder="WORKBENCH_TOKEN" :disabled="busy" /><button :disabled="busy || !enteredToken">连接</button></form></template>
            <template v-else><span class="wb-status-dot" />管理权限已连接 · 令牌仅保存在页面内存中<button :disabled="busy || importing" @click="token = ''; run(refresh)">断开</button></template>
        </div>
        <div v-if="error || notice" class="wb-message" :class="error ? 'wb-error' : 'wb-success'" :role="error ? 'alert' : 'status'">{{ error || notice }}</div>
        <div class="wb-layout">
            <aside class="wb-sidebar">
                <nav class="wb-tabs"><button :class="{ active: tab === 'layers' }" @click="tab = 'layers'">图层</button><button :class="{ active: tab === 'datasets' }" @click="tab = 'datasets'">数据集 <small>{{ datasets.length }}</small></button></nav>
                <fieldset :disabled="busy">
                    <template v-if="tab === 'layers' && draft">
                        <section class="wb-section"><label>方案名称<input v-model="draft.name" aria-label="方案名称" /></label><small class="wb-muted">{{ draft.code }}</small><label>底图方案<select v-model="draft.basemap" aria-label="底图方案"><option value="positron">白天 · Positron</option><option value="osm-liberty-dark">夜晚 · OSM Liberty</option></select></label><small class="wb-muted">只选择现有底图，不编辑底图内容。</small></section>
                        <div class="wb-section-heading"><h2>业务图层</h2><span>{{ draft.layers.length }}</span></div>
                        <ol class="wb-layer-list"><li v-for="(layer, index) in draft.layers" :key="index" :class="{ selected: selected === index }"><button @click="select(index)"><span class="wb-layer-icon">{{ layer.type === 'line' ? '━' : layer.type === 'symbol' ? 'T' : '◉' }}</span><span><strong>{{ layer.id }}</strong><small>{{ layer.type }}{{ layer.layout?.visibility === 'none' ? ' · 隐藏' : '' }}</small></span></button></li></ol>
                        <div class="wb-layer-actions"><button :disabled="selected <= 0 || pendingEditor" @click="move(-1)">上移</button><button :disabled="selected >= draft.layers.length - 1 || pendingEditor" @click="move(1)">下移</button><button :disabled="!selectedLayer || pendingEditor" @click="copyLayer">复制</button><button :disabled="!selectedLayer || pendingEditor" @click="removeLayer">删除</button></div>
                        <p class="wb-muted wb-section">列表从下层到上层排列。港铁路线仍位于底图建筑下方。</p>
                        <details class="wb-section"><summary>添加图层</summary><fieldset :disabled="pendingEditor || draft.layers.length >= 100">
                            <label>数据源<select v-model="sourceKey" aria-label="新图层数据源"><option v-for="(source, key) in sources" :key="key" :value="key">{{ source.name }}</option></select></label>
                            <label>源内图层<select v-model="sourceLayer" aria-label="源内图层"><option v-for="layer in sourceLayers" :key="layer.id" :value="layer.id">{{ layer.id || 'GeoJSON' }}</option></select></label>
                            <label>显示类型<select v-model="layerType" aria-label="新图层类型"><option v-for="type in layerTypes" :key="type">{{ type }}</option></select></label>
                            <label>图层 ID<input v-model="newLayerId" aria-label="新图层 ID" placeholder="station-labels" /></label><button @click="addLayer">添加到方案</button>
                        </fieldset></details>
                    </template>
                    <template v-if="tab === 'datasets'">
                        <div class="wb-section"><button class="wb-primary" :disabled="!token" @click="importing = true">导入数据</button><p class="wb-muted">WGS84 · GeoJSON / CSV / WKT</p></div>
                        <article v-for="dataset in datasets" :key="dataset.code" class="wb-dataset"><div class="wb-row wb-between"><strong>{{ dataset.name }}</strong><span :class="dataset.published ? 'wb-badge' : 'wb-muted'">{{ dataset.published ? '已发布' : '草稿' }}</span></div><p class="wb-muted">{{ dataset.code }} · {{ dataset.ontology_code }}</p><div class="wb-row"><button :disabled="!token" @click="publication(dataset)">{{ dataset.published ? '取消发布' : '发布' }}</button><button v-if="dataset.published" @click="bounds = [...(sources['dataset:' + dataset.code]?.bounds || [])]">定位</button></div></article>
                        <p v-if="!datasets.length" class="wb-section wb-muted">暂无数据集。内置港铁源仍可在图层中使用。</p>
                    </template>
                </fieldset>
            </aside>
            <section class="wb-map-panel">
                <PreviewMap :style="previewStyle" :sample="sample" :bounds="bounds" @error="mapError = $event" />
                <div class="wb-map-caption"><strong>实时样式预览</strong><span>{{ sample ? '橙色：导入样例（最多 10 个）' : '静态瓦片 · 列车为示意要素，无实时推演' }}</span></div>
                <div v-if="composed.errors.length || mapError" class="wb-map-errors" role="alert"><strong>预览提示</strong><p v-for="message in composed.errors" :key="message">{{ message }}</p><p v-if="mapError">{{ mapError }}</p><small v-if="composed.errors.length">修复前保留最后有效预览，无法保存。</small></div>
            </section>
            <aside class="wb-inspector"><fieldset :disabled="busy"><LayerEditor v-if="selectedLayer" :key="`${draft?.code}:${selected}`" :layer="selectedLayer" :sources="sources" @update="updateLayer" @pending="pendingEditor = $event" /><div v-else class="wb-section wb-muted">选择或添加图层以编辑样式。</div></fieldset></aside>
        </div>
        <ImportDialog v-if="importing" :token="token" :basemap="basemaps[draft?.basemap || 'positron']" @close="importing = false; sample = undefined" @imported="imported" @preview="sample = $event" @busy="importBusy = $event" />
    </main>
</template>
