<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { Map as LibreMap, Marker, NavigationControl, setWorkerUrl, type GeoJSONSource, type StyleSpecification } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import type { Feature, FeatureCollection, Point, LineString } from 'geojson';
import type { Dataset } from '../../api/datasets';
import { createDataset } from '../../api/datasets';
import { request } from '../../api/client';

const props = defineProps<{ token: string; dataset?: Dataset; basemap?: StyleSpecification }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const dialog = ref<HTMLDialogElement>(), canvas = ref<HTMLElement>();
const code = ref(props.dataset?.code || ''), name = ref(props.dataset?.name || '');
const ontology = ref(props.dataset?.ontology_code || 'route');
const existing = ref(!!props.dataset);
const collection = shallowRef<FeatureCollection>({type:'FeatureCollection',features:[]});
const selected = ref<Feature<Point | LineString>>();
const key = ref(''), properties = ref('{}'), original = ref('');
const mode = ref<'select' | 'point' | 'line'>('select');
const busy = ref(false), error = ref(''), notice = ref('');
const lifetime = new AbortController();
let map: LibreMap | undefined;
let markers: Marker[] = [];
const dirty = computed(() => selected.value && JSON.stringify([selected.value.geometry,properties.value,key.value]) !== original.value);
const coordinates = computed(() => selected.value?.geometry.type === 'Point' ? [selected.value.geometry.coordinates] : selected.value?.geometry.coordinates || []);
const endpoint = () => 'admin/datasets/' + encodeURIComponent(code.value) + '/features';
function redraw() {
    (map?.getSource('edit-features') as GeoJSONSource | undefined)?.setData(collection.value);
    (map?.getSource('edit-selected') as GeoJSONSource | undefined)?.setData({type:'FeatureCollection',features:
        selected.value && (selected.value.geometry.type === 'Point' || selected.value.geometry.coordinates.length >= 2) ? [selected.value] : []});
    markers.forEach(marker => marker.remove()); markers = [];
    if (!map) return;
    coordinates.value.forEach((coordinate,index) => {
        const marker = new Marker({draggable:true,color:'#f97316'}).setLngLat([coordinate[0],coordinate[1]]).addTo(map!);
        marker.on('dragend', () => {
            const point = marker.getLngLat(); coordinates.value[index][0] = point.lng; coordinates.value[index][1] = point.lat; redraw();
        });
        markers.push(marker);
    });
}
function discard() { return !dirty.value || window.confirm('放弃当前要素未保存的修改？'); }
async function run(action: () => Promise<void>) {
    if (busy.value) return;
    busy.value = true; error.value = ''; notice.value = '';
    try { await action(); } catch(cause) { if (!lifetime.signal.aborted) error.value = String(cause); }
    finally { busy.value = false; }
}
async function load() { collection.value = await request<FeatureCollection>(endpoint(), {token:props.token,signal:lifetime.signal}); redraw(); }
async function select(id: string) {
    if (!discard()) return;
    await run(async () => {
        const feature = await request<Feature>(endpoint() + '/' + encodeURIComponent(id), {token:props.token,signal:lifetime.signal});
        if (!['Point','LineString'].includes(feature.geometry.type)) throw new Error('顶点编辑支持点和单线；其他几何请使用文件更新');
        selected.value = feature as Feature<Point | LineString>; key.value = id;
        properties.value = JSON.stringify(feature.properties,null,2); mode.value = 'select';
        original.value = JSON.stringify([feature.geometry,properties.value,key.value]); redraw();
    });
}
function begin(kind: 'point' | 'line') {
    if (!discard()) return;
    mode.value = kind; key.value = ''; properties.value = kind === 'line' ? '{"line":"L","colour":"#168b92"}' : '{"name":""}';
    selected.value = undefined; original.value = ''; redraw();
}
function finish() { mode.value = 'select'; redraw(); }
async function save() {
    await run(async () => {
        if (!selected.value || !key.value.trim()) throw new Error('请绘制要素并填写来源唯一键');
        if (selected.value.geometry.type === 'LineString' && selected.value.geometry.coordinates.length < 2) throw new Error('线至少需要两个顶点');
        const values = JSON.parse(properties.value);
        if (!values || Array.isArray(values) || typeof values !== 'object') throw new Error('属性必须是 JSON 对象');
        const feature = {...selected.value,id:key.value,properties:values};
        if (!existing.value) {
            await createDataset({code:code.value,name:name.value,ontology:ontology.value,format:'geojson',
                content:JSON.stringify({type:'FeatureCollection',features:[feature]}),mapping:{}},props.token,lifetime.signal);
            existing.value = true;
        } else await request(endpoint()+'/'+encodeURIComponent(key.value), {method:'PUT',body:feature,token:props.token,signal:lifetime.signal});
        mode.value = 'select'; selected.value = feature;
        original.value = JSON.stringify([feature.geometry,properties.value,key.value]);
        await load(); notice.value = '已保存；相关路径和计划已重新计算。'; emit('saved');
    });
}
async function remove() {
    if (!selected.value?.id || !window.confirm('删除当前要素？')) return;
    await run(async () => {
        await request(endpoint()+'/'+encodeURIComponent(String(selected.value!.id)), {method:'DELETE',token:props.token,signal:lifetime.signal});
        selected.value = undefined; await load(); emit('saved');
    });
}
function close() { if (!busy.value && discard()) emit('close'); }
function beforeUnload(event: BeforeUnloadEvent) { if(dirty.value || busy.value) { event.preventDefault(); event.returnValue=''; } }
onBeforeRouteLeave(() => !busy.value && discard());
onMounted(async () => {
    window.addEventListener('beforeunload',beforeUnload);
    dialog.value?.showModal(); setWorkerUrl(workerUrl);
    const style: StyleSpecification = props.basemap ? JSON.parse(JSON.stringify(props.basemap)) : {version:8,sources:{},layers:[]};
    for (const source of ['edit-features','edit-selected']) {
        style.sources[source] = {type:'geojson',data:{type:'FeatureCollection',features:[]}};
        style.layers.push(
            {id:source+'-line',type:'line',source,filter:['==',['geometry-type'],'LineString'],paint:{'line-color':source==='edit-selected'?'#f97316':'#168b92','line-width':5}},
            {id:source+'-point',type:'circle',source,filter:['==',['geometry-type'],'Point'],paint:{'circle-color':source==='edit-selected'?'#f97316':'#168b92','circle-radius':7}});
    }
    map = new LibreMap({container:canvas.value!,style,center:[114.165,22.285],zoom:14});
    map.addControl(new NavigationControl()); map.on('load',redraw);
    map.on('error', event => error.value = event.error.message);
    map.on('click', event => {
        if (busy.value) return;
        const coordinate = [event.lngLat.lng,event.lngLat.lat];
        if (mode.value === 'point') {
            selected.value = {type:'Feature',properties:{},geometry:{type:'Point',coordinates:coordinate}}; mode.value='select'; redraw();
        } else if (mode.value === 'line') {
            if (!selected.value) selected.value = {type:'Feature',properties:{},geometry:{type:'LineString',coordinates:[]}};
            if (selected.value.geometry.type === 'LineString') selected.value.geometry.coordinates.push(coordinate); redraw();
        } else {
            const features = map!.queryRenderedFeatures(event.point,{layers:['edit-features-line','edit-features-point']});
            if (features[0]?.id !== undefined) void select(String(features[0].id));
        }
    });
    if (existing.value) await run(async () => {
        await load();
        const coords = collection.value.features.flatMap(f => f.geometry.type==='Point'?[f.geometry.coordinates]:f.geometry.type==='LineString'?f.geometry.coordinates:[]);
        if (coords.length) {
            const xs=coords.map(p=>p[0]), ys=coords.map(p=>p[1]);
            map!.fitBounds([[Math.min(...xs),Math.min(...ys)],[Math.max(...xs),Math.max(...ys)]],{padding:70,maxZoom:16});
        }
    });
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload',beforeUnload); lifetime.abort(); markers.forEach(m=>m.remove()); map?.remove(); });
</script>

<template>
    <dialog ref="dialog" class="wb-geometry" @cancel.prevent="close">
        <div class="wb-row wb-between"><h2>{{ dataset?.name || '绘制新数据集' }}</h2><button :disabled="busy" @click="close">关闭</button></div>
        <div class="geometry-layout">
            <div ref="canvas" class="geometry-map" aria-label="要素编辑地图" />
            <fieldset :disabled="busy" class="geometry-controls">
                <template v-if="!existing">
                    <label>数据集编码<input v-model="code" aria-label="绘制数据集编码" /></label>
                    <label>名称<input v-model="name" aria-label="绘制数据集名称" /></label>
                    <label>类别<select v-model="ontology"><option value="route">路径</option><option value="station">站点</option></select></label>
                </template>
                <div class="wb-row"><button @click="begin('point')">画点</button><button @click="begin('line')">画线</button><button v-if="mode === 'line'" @click="finish">完成绘制</button></div>
                <p class="wb-muted">{{ mode === 'line' ? '逐点单击地图添加顶点，完成后保存。' : mode === 'point' ? '单击地图放置站点。' : '选择要素；拖动橙色控制点修改位置。' }}</p>
                <select v-if="existing" aria-label="选择要素" :value="selected?.id || ''" @change="select(($event.target as HTMLSelectElement).value)">
                    <option disabled value="">选择要素</option><option v-for="f in collection.features" :key="f.id" :value="f.id">{{ f.id }}</option>
                </select>
                <template v-if="selected">
                    <label>来源唯一键<input v-model="key" aria-label="要素来源键" :disabled="selected.id !== undefined" /></label>
                    <label>属性 JSON<textarea v-model="properties" rows="6" aria-label="要素属性" /></label>
                    <details><summary>顶点坐标（经度、纬度）</summary>
                        <div v-for="(point,index) in coordinates" :key="index" class="wb-row">
                            <input v-model.number="point[0]" type="number" step="any" :aria-label="'经度 '+index" @change="redraw" />
                            <input v-model.number="point[1]" type="number" step="any" :aria-label="'纬度 '+index" @change="redraw" />
                            <button v-if="selected.geometry.type === 'LineString'" @click="selected.geometry.coordinates.splice(index,1); redraw()">−</button>
                        </div>
                    </details>
                    <div class="wb-row"><button class="wb-primary" @click="save">保存要素</button><button v-if="selected.id !== undefined" @click="remove">删除</button></div>
                </template>
                <p v-if="error" class="wb-error" role="alert">{{ error }}</p><p v-if="notice" class="wb-success">{{ notice }}</p>
            </fieldset>
        </div>
    </dialog>
</template>
<style scoped>
.wb-geometry{width:min(1200px,95vw);max-width:95vw;border:0;border-radius:10px;padding:20px;background:#f8fafb;color:#18334b}
.geometry-layout{display:grid;grid-template-columns:1fr 320px;gap:16px;height:72vh}
.geometry-map{height:100%;min-width:0}.geometry-controls{overflow:auto}.geometry-controls label{display:block;margin:10px 0}
.geometry-controls input,.geometry-controls textarea,.geometry-controls select{width:100%;padding:7px;box-sizing:border-box}.geometry-controls .wb-row input{min-width:0}
</style>
