<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createDataset, inspectImport, listOntology, previewImport, type ImportInput, type ImportPreview, type Inspection, type Ontology } from '../../api/datasets';
import type { FeatureCollection } from 'geojson';
import type { StyleSpecification } from 'maplibre-gl';
import PreviewMap from './PreviewMap.vue';
import { request } from '../../api/client';
import type { Dataset } from '../../api/datasets';

const props = defineProps<{ token: string; basemap?: StyleSpecification; dataset?: Dataset }>();
const emit = defineEmits<{ close: []; imported: []; preview: [data?: FeatureCollection]; busy: [value: boolean] }>();
const dialog = ref<HTMLDialogElement>();
const input = ref<ImportInput>({ code: '', name: '', ontology: 'station', format: 'geojson', content: '', mapping: {} });
const inspection = ref<Inspection>();
const preview = ref<ImportPreview>();
const ontology = ref<Ontology[]>([]);
const geometryMode = ref('wkt');
const mappings = ref<{ from: string; to: string; keep: boolean }[]>([]);
const error = ref('');
const busy = ref(false);
watch(busy, value => emit('busy', value), { flush: 'sync' });
const declared = ref(false);
const updateMode = ref('merge');
const lifetime = new AbortController();
const fields = computed(() => inspection.value?.fields || []);
const attributeMappings = computed(() => mappings.value.filter(m => input.value.format !== 'csv' ||
    !(geometryMode.value === 'wkt' ? [input.value.geometryColumn] : [input.value.longitudeColumn, input.value.latitudeColumn]).includes(m.from)));
const fingerprint = computed(() => JSON.stringify([input.value, mappings.value, geometryMode.value, declared.value]));
watch(fingerprint, () => { preview.value = undefined; emit('preview', undefined); });
async function run(action: () => Promise<void>) {
    busy.value = true; error.value = '';
    try { await action(); } catch (cause) { if (!lifetime.signal.aborted) error.value = String(cause); }
    finally { busy.value = false; }
}
async function file(event: Event) {
    const selected = (event.target as HTMLInputElement).files?.[0];
    if (!selected) return;
    await run(async () => {
        inspection.value = undefined; preview.value = undefined;
        if (selected.size > 5 * 1024 * 1024) throw new Error('文件不能超过 5 MiB');
        input.value.content = await selected.text();
        input.value.format = selected.name.toLowerCase().endsWith('.csv') ? 'csv' : selected.name.toLowerCase().endsWith('.wkt') ? 'wkt' : 'geojson';
        input.value.name = selected.name.replace(/\.[^.]+$/, '');
        input.value.code = input.value.name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 64);
        if (!/^[a-z]/.test(input.value.code)) input.value.code = '';
        if (props.dataset) { input.value.code = props.dataset.code; input.value.name = props.dataset.name; input.value.ontology = props.dataset.ontology_code; }
        const result = await inspectImport(input.value, props.token, lifetime.signal);
        inspection.value = result;
        mappings.value = result.fields.map(from => ({ from, to: from, keep: true }));
        input.value.geometryColumn = result.fields.find(f => /^(wkt|geom|geometry)$/i.test(f)) || result.fields[0];
        input.value.longitudeColumn = result.fields.find(f => /^(lng|lon|longitude|x)$/i.test(f));
        input.value.latitudeColumn = result.fields.find(f => /^(lat|latitude|y)$/i.test(f));
    });
}
function payload(): ImportInput {
    if (!declared.value) throw new Error('请先确认二维 WGS84 坐标声明');
    const kept = attributeMappings.value.filter(m => m.keep);
    if (attributeMappings.value.length && !kept.length) throw new Error('请至少保留一个属性字段');
    if (new Set(kept.map(m => m.to)).size !== kept.length) throw new Error('映射目标字段不能重复');
    return { ...input.value, mapping: Object.fromEntries(kept.map(m => [m.from, m.to])),
        geometryColumn: geometryMode.value === 'wkt' ? input.value.geometryColumn : undefined };
}
async function validate() { await run(async () => { preview.value = await previewImport(payload(), props.token, lifetime.signal); emit('preview', preview.value.geojson); }); }
async function create() {
    if (!preview.value) return;
    await run(async () => {
        if (props.dataset) await request(`admin/datasets/${props.dataset.code}/import?mode=${updateMode.value}`,
            {method:'POST',body:payload(),token:props.token,signal:lifetime.signal});
        else await createDataset(payload(), props.token, lifetime.signal);
        emit('imported');
    });
}
onMounted(async () => { dialog.value?.showModal(); await run(async () => { ontology.value = (await listOntology(lifetime.signal)).filter(o => o.enabled && !o.is_dynamic); }); });
onBeforeUnmount(() => { lifetime.abort(); emit('preview', undefined); });
</script>

<template>
    <dialog ref="dialog" class="wb-import" @cancel.prevent="!busy && emit('close')">
        <div class="wb-row wb-between"><h2>导入空间数据</h2><button type="button" :disabled="busy" @click="emit('close')">关闭</button></div>
        <p class="wb-muted">选择文件 → 映射字段 → 校验 → 创建草稿。限 5 MiB / 10,000 个要素。</p>
        <fieldset :disabled="busy">
            <label>文件<input aria-label="导入文件" type="file" accept=".geojson,.json,.csv,.wkt" @change="file" /></label>
            <label v-if="dataset">更新方式<select v-model="updateMode"><option value="merge">按来源键新增或更新</option><option value="replace">替换全部（缺失要素将删除，引用冲突时取消）</option></select></label>
            <template v-if="inspection">
                <div class="wb-row"><label>数据集编码<input v-model="input.code" aria-label="数据集编码" placeholder="my-stations" /></label><label>名称<input v-model="input.name" aria-label="数据集名称" /></label></div>
                <label>语义类别<select v-model="input.ontology" aria-label="语义类别"><option v-for="item in ontology" :key="item.code" :value="item.code">{{ item.name }} · {{ item.geometry_types.join(' / ') }}</option></select></label>
                <template v-if="input.format === 'csv'">
                    <label>几何表达<select v-model="geometryMode" aria-label="几何表达"><option value="wkt">WKT 列</option><option value="coordinates">经度 / 纬度列</option></select></label>
                    <label v-if="geometryMode === 'wkt'">WKT 列<select v-model="input.geometryColumn" aria-label="WKT 列"><option v-for="field in fields" :key="field">{{ field }}</option></select></label>
                    <div v-else class="wb-row"><label>经度<select v-model="input.longitudeColumn" aria-label="经度列"><option v-for="field in fields" :key="field">{{ field }}</option></select></label><label>纬度<select v-model="input.latitudeColumn" aria-label="纬度列"><option v-for="field in fields" :key="field">{{ field }}</option></select></label></div>
                </template>
                <h3>属性映射</h3>
                <label>来源唯一键<select v-model="input.keyField" aria-label="来源唯一键"><option :value="undefined">使用 GeoJSON ID，否则自动生成</option><option v-for="field in fields" :key="field">{{ field }}</option></select></label>
                <p class="wb-muted">语义类别约束几何类型；下面指定保留的属性名称。CSV 属性保留字符串。</p>
                <div v-for="mapping in attributeMappings" :key="mapping.from" class="wb-row wb-mapping">
                    <label class="wb-check"><input v-model="mapping.keep" type="checkbox" />{{ mapping.from }}</label>
                    <span>→</span><input v-model="mapping.to" :aria-label="`映射 ${mapping.from}`" :disabled="!mapping.keep" />
                </div>
                <details><summary>原始样例</summary><pre>{{ JSON.stringify(inspection.samples, null, 2) }}</pre></details>
                <label class="wb-check"><input v-model="declared" type="checkbox" />确认数据为二维 WGS84（经度、纬度），不是 GCJ02 或 BD09。</label>
                <button type="button" :disabled="!declared" @click="validate">校验并预览</button>
                <div v-if="preview" class="wb-success">校验通过：{{ preview.count }} 个要素。地图显示最多 10 个橙色样例。
                    <p>字段：{{ Object.keys(preview.fields).join('、') || '无' }}</p>
                    <div class="wb-import-map"><PreviewMap :style="basemap" :sample="preview.geojson" @error="error = $event" /></div>
                    <button type="button" class="wb-primary" @click="create">确认导入</button>
                </div>
            </template>
        </fieldset>
        <p v-if="busy" role="status">正在处理…</p><p v-if="error" role="alert" class="wb-error">{{ error }}</p>
    </dialog>
</template>
