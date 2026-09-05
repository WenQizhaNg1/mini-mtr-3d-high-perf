<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import type { FeatureCollection } from 'geojson';
import type { Dataset } from '../../api/datasets';
import type { Configured, Pattern, TransitConfig, TimetableTrip } from '../../api/transitConfig';
import { request } from '../../api/client';

const props = defineProps<{token:string;datasets:Dataset[]}>();
const emit = defineEmits<{close:[];saved:[]}>();
const dialog = ref<HTMLDialogElement>();
const items = ref<Configured[]>([]), adapters = ref<{code:string;modes:string[]}[]>([]);
const code = ref(''), current = ref('');
function empty(): TransitConfig { return {
    name:'',timezone:'Asia/Hong_Kong',adapter:'file',mode:'simulation',
    mapping:{routesDataset:'',stationsDataset:'',routeFields:{line:'line',colour:'colour'},stationFields:{name:'name'},patterns:[],snapToleranceMeters:100},
    simulation:{serviceDayStart:'00:00',rules:[],timetable:[]},realtime:{monitors:[],staleAfterSeconds:45},
}; }
const config = ref<TransitConfig>(empty());
const original = ref(JSON.stringify(config.value));
const dirty = computed(() => JSON.stringify(config.value)!==original.value);
const paths = ref<FeatureCollection['features']>([]), stations = ref<FeatureCollection['features']>([]);
const selectedPattern = ref(0);
const pattern = computed(() => config.value.mapping.patterns[selectedPattern.value]);
const modes = computed(() => adapters.value.find(a=>a.code===config.value.adapter)?.modes || []);
const routeFields = computed(() => Object.keys(props.datasets.find(d=>d.code===config.value.mapping.routesDataset)?.fields || {}));
const stationFields = computed(() => Object.keys(props.datasets.find(d=>d.code===config.value.mapping.stationsDataset)?.fields || {}));
const busy = ref(false), error = ref(''), notice = ref('');
const csv = ref(''), csvFields = ref<Record<string,string>>({trip:'trip',pattern:'pattern',seq:'seq',arrival:'arrival',departure:'departure',serviceDate:'serviceDate'});
const headers = ref<string[]>([]);
const lifetime = new AbortController();
let dataRequest: AbortController | undefined;
const options = () => ({token:props.token,signal:lifetime.signal});
async function run(action:()=>Promise<void>) {
    if (busy.value) return;
    busy.value=true; error.value=''; notice.value='';
    try { await action(); } catch(cause) {
        if (!lifetime.signal.aborted) {
            error.value=String(cause);
            const index=config.value.mapping.patterns.findIndex(p=>p.code && error.value.includes('Pattern '+p.code));
            if (index>=0) selectedPattern.value=index;
        }
    }
    finally { busy.value=false; }
}
async function refresh() { items.value=await request<Configured[]>('admin/transit',options()); }
function discard() { return !dirty.value || window.confirm('放弃尚未保存的交通配置？'); }
function select(value:string) {
    if (!discard()) return;
    const item=items.value.find(i=>i.code===value);
    current.value=value; code.value=value; config.value=item?JSON.parse(JSON.stringify(item.config)):empty();
    original.value=JSON.stringify(config.value); selectedPattern.value=0; error.value=''; notice.value='';
}
watch(() => [config.value.mapping.routesDataset,config.value.mapping.stationsDataset],async () => {
    dataRequest?.abort(); dataRequest=new AbortController(); const signal=dataRequest.signal;
    paths.value=[]; stations.value=[];
    try {
        const [a,b]=await Promise.all([config.value.mapping.routesDataset,config.value.mapping.stationsDataset].map(dataset=>
            dataset?request<FeatureCollection>(`admin/datasets/${dataset}/features`,{token:props.token,signal}):Promise.resolve({features:[]})));
        if (!signal.aborted) { paths.value=a.features; stations.value=b.features; }
    } catch(cause) { if (!signal.aborted) error.value=String(cause); }
});
watch(() => config.value.adapter, () => {
    if (!modes.value.includes(config.value.mode)) config.value.mode=modes.value[0] as TransitConfig['mode'] || 'simulation';
});
function addPattern() {
    config.value.mapping.patterns.push({code:'',featureKey:String(paths.value[0]?.id || ''),direction:'out',reversed:false,stops:[]});
    selectedPattern.value=config.value.mapping.patterns.length-1;
}
function moveStop(index:number,delta:number) {
    if (!pattern.value) return;
    const stops=pattern.value.stops,target=index+delta;
    if (target<0 || target>=stops.length) return;
    const [stop]=stops.splice(index,1); stops.splice(target,0,stop);
}
async function save() {
    await run(async()=>{
        const result=await request<Configured>('admin/transit/'+encodeURIComponent(code.value),{...options(),method:'PUT',body:config.value});
        config.value=result.config; current.value=result.code; original.value=JSON.stringify(config.value);
        await refresh(); emit('saved'); notice.value='已保存，路径校验及计划重算完成。';
    });
}
async function remove() {
    if (!current.value || !window.confirm('解除该运营方的全部交通绑定和计划？源空间数据保留。')) return;
    await run(async()=>{
        await request('admin/transit/'+encodeURIComponent(current.value),{...options(),method:'DELETE'});
        await refresh(); config.value=empty(); current.value=code.value=''; original.value=JSON.stringify(config.value); emit('saved');
    });
}
async function file(event:Event) {
    const f=(event.target as HTMLInputElement).files?.[0]; if (!f) return;
    await run(async()=>{
        if (f.size>5*1024*1024) throw new Error('文件不能超过 5 MiB');
        csv.value=await f.text();
        // Header inspection uses the same CSV parser as spatial imports.
        const inspection=await request<{fields:string[]}>('datasets/inspect',{...options(),method:'POST',body:{format:'csv',content:csv.value}});
        headers.value=inspection.fields;
    });
}
async function importCsv() {
    await run(async()=>{
        config.value.simulation.timetable=await request<TimetableTrip[]>('admin/transit/timetable/preview',
            {...options(),method:'POST',body:{content:csv.value,fields:csvFields.value}});
        notice.value='时刻表已读取；保存时校验停站顺序和到发时间。';
    });
}
function close() { if (!busy.value && discard()) emit('close'); }
function beforeUnload(event:BeforeUnloadEvent) { if(dirty.value || busy.value) { event.preventDefault(); event.returnValue=''; } }
onBeforeRouteLeave(()=> !busy.value && discard());
onMounted(async()=>{ window.addEventListener('beforeunload',beforeUnload); dialog.value?.showModal(); await run(async()=>{
    adapters.value=await request('admin/transit/adapters',options()); await refresh();
    if (items.value.length) select(items.value[0].code);
}); });
onBeforeUnmount(()=>{ window.removeEventListener('beforeunload',beforeUnload); lifetime.abort(); dataRequest?.abort(); });
</script>
<template>
    <dialog ref="dialog" class="wb-transit" @cancel.prevent="close">
        <div class="wb-row wb-between"><h2>交通数据接入</h2><button :disabled="busy" @click="close">关闭</button></div>
        <fieldset :disabled="busy">
            <div class="wb-row">
                <select aria-label="编辑运营方" :value="current" @change="select(($event.target as HTMLSelectElement).value)">
                    <option value="">新运营方</option><option v-for="item in items" :key="item.code" :value="item.code">{{ item.config.name }}</option>
                </select><button @click="select('')">新建接入</button>
                <button class="wb-primary" @click="save">保存并重算</button><button v-if="current" @click="remove">解除全部绑定</button>
            </div>
            <p v-if="error" class="wb-error" role="alert">{{ error }}</p><p v-if="notice" class="wb-success" role="status">{{ notice }}</p>
            <div class="transit-columns">
                <section>
                    <h3>1 · 运营方与空间字段</h3>
                    <div class="wb-row"><label>运营方编码<input v-model="code" :disabled="!!current" aria-label="运营方编码" /></label><label>名称<input v-model="config.name" aria-label="运营方名称" /></label></div>
                    <label>时区<input v-model="config.timezone" aria-label="运营方时区" placeholder="Asia/Hong_Kong" /></label>
                    <label>路径数据集<select v-model="config.mapping.routesDataset" aria-label="路径数据集"><option value="">请选择</option><option v-for="d in datasets" :key="d.code" :value="d.code">{{ d.name }}</option></select></label>
                    <div class="wb-row"><label v-for="field in [{key:'line',name:'线路编号'},{key:'colour',name:'线路颜色'},{key:'lineName',name:'线路名称'}]" :key="field.key">{{ field.name }}
                        <select v-model="config.mapping.routeFields[field.key]" :aria-label="field.name+'字段'"><option value="">默认值</option><option v-for="f in routeFields" :key="f">{{ f }}</option></select></label></div>
                    <label>站点数据集<select v-model="config.mapping.stationsDataset" aria-label="站点数据集"><option value="">请选择</option><option v-for="d in datasets" :key="d.code" :value="d.code">{{ d.name }}</option></select></label>
                    <div class="field-grid"><label v-for="field in [{key:'code',name:'站点编号'},{key:'name',name:'站名'},{key:'nameEn',name:'英文站名'},{key:'lines',name:'所属线路（逗号分隔）'}]" :key="field.key">{{ field.name }}
                        <select v-model="config.mapping.stationFields[field.key]" :aria-label="field.name+'字段'"><option value="">默认值</option><option v-for="f in stationFields" :key="f">{{ f }}</option></select></label></div>
                    <label>站点距路径容差（米）<input v-model.number="config.mapping.snapToleranceMeters" type="number" min="0" max="2000" /></label>
                    <h3>2 · 有向运行方案</h3>
                    <div class="wb-row"><select v-model.number="selectedPattern" aria-label="运行方案"><option v-for="(p,i) in config.mapping.patterns" :key="i" :value="i">{{ p.code || '新方案' }}</option></select><button @click="addPattern">添加方案</button></div>
                    <template v-if="pattern">
                        <label>方案编号<input v-model="pattern.code" aria-label="方案编号" /></label>
                        <label>路径要素<select v-model="pattern.featureKey" aria-label="路径要素"><option v-for="f in paths" :key="f.id" :value="String(f.id)">{{ f.id }}</option></select></label>
                        <div class="wb-row"><label>方向<input v-model="pattern.direction" /></label><label class="wb-check"><input v-model="pattern.reversed" type="checkbox" />反转几何方向</label></div>
                        <p class="wb-muted">按运行顺序添加停站。比例留空自动投影；歧义时填 0–1 明确沿线位置。</p>
                        <div v-for="(stop,i) in pattern.stops" :key="i" class="wb-row stop-row">
                            <span>{{ i }}</span><select v-model="stop.stationKey" :aria-label="'停站 '+i"><option v-for="f in stations" :key="f.id" :value="String(f.id)">{{ f.properties?.name || f.properties?.code || f.id }} · {{ f.id }}</option></select>
                            <input :value="stop.fraction" type="number" min="0" max="1" step="any" placeholder="自动" :aria-label="'沿线比例 '+i" @input="stop.fraction=($event.target as HTMLInputElement).value === '' ? null : Number(($event.target as HTMLInputElement).value)" />
                            <button @click="moveStop(i,-1)">↑</button><button @click="moveStop(i,1)">↓</button><button @click="pattern.stops.splice(i,1)">−</button>
                        </div>
                        <button @click="pattern.stops.push({stationKey:String(stations[0]?.id || ''),fraction:null})">添加停站</button>
                        <button @click="config.mapping.patterns.splice(selectedPattern,1); selectedPattern=0">删除方案</button>
                    </template>
                </section>
                <section>
                    <h3>3 · 数据来源与运行</h3>
                    <div class="wb-row"><label>Adapter<select v-model="config.adapter" aria-label="Adapter"><option v-for="a in adapters" :key="a.code">{{ a.code }}</option></select></label>
                        <label>默认运行模式<select v-model="config.mode" aria-label="默认运行模式"><option v-for="m in modes" :key="m" :value="m">{{ m === 'simulation' ? '模拟' : '实时' }}</option></select></label></div>
                    <p class="wb-muted">file：时刻表及规则；feed：标准观测推送与模拟；mtr：港铁规则与官方 ETA。</p>
                    <template v-if="modes.includes('simulation')">
                        <label>服务日起点<input v-model="config.simulation.serviceDayStart" placeholder="00:00" /></label>
                        <h3>时刻表</h3>
                        <p class="wb-muted">每班次逐站填写，序号从 0 开始；跨午夜使用 24:xx。服务日期为空表示每日运行。</p>
                        <label>导入 CSV<input type="file" accept=".csv" aria-label="时刻表文件" @change="file" /></label>
                        <div v-if="headers.length" class="field-grid"><label v-for="(_,field) in csvFields" :key="field">{{ field }}<select v-model="csvFields[field]"><option v-if="field === 'serviceDate'" value="">每日</option><option v-for="h in headers" :key="h">{{ h }}</option></select></label></div>
                        <button v-if="headers.length" @click="importCsv">读取时刻表</button>
                        <p>{{ config.simulation.timetable.length }} 个班次 <button v-if="config.simulation.timetable.length" @click="config.simulation.timetable=[]">清空时刻表</button></p>
                        <details v-if="config.simulation.timetable.length"><summary>查看班次与到发时刻</summary><pre>{{ JSON.stringify(config.simulation.timetable,null,2) }}</pre></details>
                        <h3>模拟发车规则</h3>
                        <article v-for="(rule,i) in config.simulation.rules" :key="i" class="rule">
                            <div class="wb-row"><label>规则编号<input v-model="rule.id" /></label><label>运行方案<select v-model="rule.pattern"><option v-for="p in config.mapping.patterns" :key="p.code">{{ p.code }}</option></select></label></div>
                            <div class="field-grid"><label>首班<input v-model="rule.first" /></label><label>末班<input v-model="rule.last" /></label>
                                <label>间隔（秒）<input v-model.number="rule.headwaySeconds" type="number" /></label><label>速度（km/h）<input v-model.number="rule.speedKmph" type="number" /></label><label>停站（秒）<input v-model.number="rule.dwellSeconds" type="number" /></label></div>
                            <button @click="config.simulation.rules.splice(i,1)">删除规则</button>
                        </article>
                        <button @click="config.simulation.rules.push({id:'rule-'+(config.simulation.rules.length+1),pattern:pattern?.code || '',first:'06:00',last:'24:00',headwaySeconds:300,speedKmph:40,dwellSeconds:30})">添加发车规则</button>
                    </template>
                    <template v-if="modes.includes('realtime')">
                        <h3>实时观测</h3><label>过期阈值（秒）<input v-model.number="config.realtime.staleAfterSeconds" type="number" min="5" max="600" /></label>
                        <p v-if="config.adapter === 'feed'" class="wb-muted">通过管理接口 PUT /api/admin/transit/{{ code || '运营方编码' }}/observations 推送 GPS、沿线里程或 ETA。接口格式见接入文档。</p>
                        <template v-if="config.adapter === 'mtr'">
                            <p class="wb-muted">港铁当前提供站点 ETA；实时模式只展示到站信息。</p>
                            <div v-for="(monitor,i) in config.realtime.monitors" :key="i" class="wb-row"><input v-model="monitor.line" placeholder="线路 API 编码" /><input v-model="monitor.station" placeholder="监测站编号" /><button @click="config.realtime.monitors.splice(i,1)">−</button></div>
                            <button @click="config.realtime.monitors.push({line:'',station:''})">添加监测站</button>
                        </template>
                    </template>
                </section>
            </div>
        </fieldset>
    </dialog>
</template>
<style scoped>
.wb-transit{width:min(1160px,95vw);max-width:95vw;max-height:92vh;overflow:auto;border:0;border-radius:10px;padding:24px;background:#f8fafb;color:#18334b}
.transit-columns{display:grid;grid-template-columns:1fr 1fr;gap:32px}.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.stop-row{margin:8px 0}.stop-row select{flex:1}.stop-row input{width:80px}.stop-row button{padding:5px}
.rule{padding:12px;margin:8px 0;border:1px solid #dce3e8;border-radius:6px}
</style>
