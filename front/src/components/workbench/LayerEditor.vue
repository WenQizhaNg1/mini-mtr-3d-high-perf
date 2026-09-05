<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { LayerSpecification } from 'maplibre-gl';
import type { SourceCatalog } from '../../api/styles';

const props = defineProps<{ layer: LayerSpecification; sources: SourceCatalog }>();
const emit = defineEmits<{ update: [layer: LayerSpecification]; pending: [value: boolean] }>();
const advanced = ref('');
const advancedDirty = ref(false);
const jsonError = ref('');
watch(() => props.layer, value => {
    advanced.value = JSON.stringify(value, null, 2); advancedDirty.value = false; jsonError.value = ''; emit('pending', false);
}, { immediate: true, deep: true });
const sourceKey = computed(() => 'source' in props.layer && typeof props.layer.source === 'string' ? props.layer.source : '');
const fields = computed(() => props.sources[sourceKey.value]?.layers.find(l => l.id === ('source-layer' in props.layer ? props.layer['source-layer'] || '' : ''))?.fields || {});
const controls = computed(() => {
    const type = props.layer.type;
    if (type === 'line') return [['line-color', '颜色', 'color'], ['line-width', '宽度', 'number'], ['line-opacity', '透明度', 'number']];
    if (type === 'circle') return [['circle-color', '颜色', 'color'], ['circle-radius', '半径', 'number'], ['circle-stroke-color', '描边颜色', 'color'], ['circle-stroke-width', '描边宽度', 'number'], ['circle-opacity', '透明度', 'number']];
    if (type === 'fill') return [['fill-color', '填充色', 'color'], ['fill-opacity', '透明度', 'number']];
    if (type === 'fill-extrusion') return [['fill-extrusion-color', '颜色', 'color'], ['fill-extrusion-height', '高度（米）', 'number'], ['fill-extrusion-opacity', '透明度', 'number']];
    if (type === 'symbol') return [['text-color', '文字颜色', 'color'], ['text-halo-color', '描边颜色', 'color'], ['text-halo-width', '描边宽度', 'number']];
    return [];
});
function value(section: 'paint' | 'layout', key: string): unknown {
    return (props.layer[section] as Record<string, unknown> | undefined)?.[key];
}
function expression(section: 'paint' | 'layout', key: string) { const v = value(section, key); return v !== null && typeof v === 'object'; }
function patch(section: 'paint' | 'layout', key: string, next: unknown) {
    const layer = JSON.parse(JSON.stringify(props.layer));
    layer[section] ||= {};
    if (next === undefined) delete layer[section][key]; else layer[section][key] = next;
    emit('update', layer);
}
function paint(key: string, event: Event, type: string) {
    const text = (event.target as HTMLInputElement).value;
    patch('paint', key, text === '' ? undefined : type === 'number' ? Number(text) : text);
}
function zoom(key: string, event: Event) {
    const layer = JSON.parse(JSON.stringify(props.layer));
    const text = (event.target as HTMLInputElement).value;
    if (text === '') delete layer[key]; else layer[key] = Number(text);
    emit('update', layer);
}
const labelField = computed(() => {
    const field = value('layout', 'text-field');
    return Array.isArray(field) && field.length === 2 && field[0] === 'get' ? String(field[1]) : '';
});
function applyJson() {
    try {
        const layer = JSON.parse(advanced.value);
        if (!layer || typeof layer !== 'object' || Array.isArray(layer) || typeof layer.id !== 'string' || !layer.type || !layer.source) throw new Error('需要包含 id、type、source 的图层对象');
        emit('update', layer); advancedDirty.value = false; emit('pending', false); jsonError.value = '';
    } catch (cause) { jsonError.value = String(cause); }
}
</script>

<template>
    <section class="wb-editor">
        <h2>图层属性</h2><p class="wb-muted">{{ layer.id }} · {{ layer.type }}</p>
        <p class="wb-muted">{{ sources[sourceKey]?.name || '不可用数据源' }} / {{ 'source-layer' in layer ? layer['source-layer'] : 'GeoJSON' }}</p>
        <fieldset :disabled="advancedDirty">
            <label class="wb-check"><input type="checkbox" :checked="value('layout', 'visibility') !== 'none'" @change="patch('layout', 'visibility', ($event.target as HTMLInputElement).checked ? 'visible' : 'none')" />显示图层</label>
            <div class="wb-row">
                <label>最小缩放<input aria-label="最小缩放" type="number" min="0" max="24" :value="layer.minzoom ?? 0" @change="zoom('minzoom', $event)" /></label>
                <label>最大缩放<input aria-label="最大缩放" type="number" min="0" max="24" :value="layer.maxzoom ?? 24" @change="zoom('maxzoom', $event)" /></label>
            </div>
            <label v-for="[key, name, type] in controls" :key="key">{{ name }}
                <input v-if="type === 'color'" class="wb-color" :aria-label="`${name}选择器`" type="color" :disabled="expression('paint', key)"
                    :value="typeof value('paint', key) === 'string' && /^#[0-9a-fA-F]{6}$/.test(String(value('paint', key))) ? value('paint', key) : '#168b92'" @input="paint(key, $event, 'color')" />
                <input :aria-label="name" :type="type === 'color' ? 'text' : type" :placeholder="type === 'color' ? '#168b92' : '默认'"
                    :step="key.includes('opacity') ? '0.1' : 'any'" :disabled="expression('paint', key)"
                    :value="expression('paint', key) ? '表达式（保留）' : value('paint', key) ?? ''" @change="paint(key, $event, type)" />
                <small v-if="expression('paint', key)">使用高级 JSON 编辑表达式。</small>
            </label>
            <template v-if="layer.type === 'symbol'">
                <label>标签字段<select aria-label="标签字段" :value="labelField" :disabled="!!value('layout', 'text-field') && !labelField" @change="patch('layout', 'text-field', ['get', ($event.target as HTMLSelectElement).value])">
                    <option value="" disabled>选择字段 / 保留现有表达式</option><option v-for="(_, field) in fields" :key="field" :value="field">{{ field }}</option>
                </select></label>
                <label>字号<input aria-label="字号" type="number" :disabled="expression('layout', 'text-size')" :value="expression('layout', 'text-size') ? '' : value('layout', 'text-size') ?? 16" @change="patch('layout', 'text-size', Number(($event.target as HTMLInputElement).value))" /></label>
            </template>
        </fieldset>
        <details class="wb-advanced">
            <summary>高级 JSON · 表达式与过滤条件</summary>
            <p class="wb-muted">保留原生配置。应用后进行 MapLibre 校验，通过才可保存。</p>
            <textarea v-model="advanced" aria-label="图层 JSON" spellcheck="false" rows="18" @input="advancedDirty = true; emit('pending', true)" />
            <p v-if="jsonError" role="alert" class="wb-error">{{ jsonError }}</p>
            <button type="button" :disabled="!advancedDirty" @click="applyJson">应用 JSON</button>
            <button type="button" :disabled="!advancedDirty" @click="advanced = JSON.stringify(layer, null, 2); advancedDirty = false; emit('pending', false); jsonError = ''">撤销 JSON 修改</button>
        </details>
    </section>
</template>
