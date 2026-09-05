import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const base = (process.argv.find(a=>a.startsWith('--api='))?.slice(6) || 'http://127.0.0.1:3002').replace(/\/$/,'');
const token = process.env.WORKBENCH_TOKEN;
if (!token) throw new Error('Set WORKBENCH_TOKEN (or run node --env-file=.env scripts/import-mtr.mjs)');
const read = name => JSON.parse(readFileSync(fileURLToPath(new URL('../'+name,import.meta.url)),'utf8'));
async function api(path, method='GET', body) {
    const response=await fetch(base+'/api/'+path,{method,
        headers:{Authorization:'Bearer '+token,...(body===undefined?{}:{'Content-Type':'application/json'})},
        body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(180000)});
    if (!response.ok) throw new Error(method+' '+path+': '+response.status+' '+await response.text());
    const text=await response.text(); return text?JSON.parse(text):null;
}
const settings=read('data/mtr-service.json');
const routes=read('data/osm/generated/mtr-route-patterns.geojson');
const stations=read('data/osm/generated/mtr-stations.geojson');
const details=read('data/osm/generated/mtr-route-patterns.json');
const lines=new Map(settings.lines.map(line=>[line.lineId,line]));
routes.features.forEach(f=>f.properties.line_name=lines.get(f.properties.line_id).nameZh);

// The source includes actual on-path stop positions. Retain their ordered positions,
// avoiding centroid projection at stations with multiple tracks or repeated passes.
function fractions(pattern) {
    const points=pattern.geometry.coordinates;
    const units=[0];
    for(let i=1;i<points.length;i++) units.push(units.at(-1)+Math.hypot(points[i][0]-points[i-1][0],points[i][1]-points[i-1][1]));
    let previous=-1;
    return pattern.stops.map(stop=>{
        const [x,y]=stop.pathCoordinate;
        let best;
        for(let i=1;i<points.length;i++) {
            const a=points[i-1],b=points[i],dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;
            if (!length) continue;
            const t=Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/length));
            const at=units[i-1]+t*(units[i]-units[i-1]);
            if (at<=previous+1e-12) continue;
            const error=Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy);
            if (!best || error<best.error-1e-12) best={at,error};
        }
        if (!best || best.error>0.00001) throw new Error('Cannot locate '+pattern.id+' stop '+stop.code);
        previous=best.at;
        return {stationKey:stop.code,fraction:best.at/units.at(-1)};
    });
}
const config={
    name:'港铁',timezone:'Asia/Hong_Kong',adapter:'mtr',mode:'simulation',
    mapping:{routesDataset:'mtr-paths',stationsDataset:'mtr-stations',
        routeFields:{line:'line_id',colour:'colour',lineName:'line_name'},
        stationFields:{code:'code',name:'name_zh',nameEn:'name_en',lines:'line_ids'},
        patterns:details.patterns.map(p=>({code:p.id,featureKey:p.id,direction:p.id.split(':')[1],reversed:false,stops:fractions(p)})),
        snapToleranceMeters:200},
    simulation:{serviceDayStart:'05:30',rules:[],timetable:[]},
    realtime:{monitors:settings.lines.map(l=>({line:l.apiCode,station:l.monitorStation})),staleAfterSeconds:60},
};
const datasets=await api('admin/datasets');
for(const [code,name,ontology,keyField,data] of [
    ['mtr-paths','港铁 · 有向路径','route','id',routes],
    ['mtr-stations','港铁 · 站点','station','code',stations],
]) {
    const input={code,name,ontology,keyField,format:'geojson',content:JSON.stringify(data),mapping:{}};
    if (datasets.some(d=>d.code===code)) await api('admin/datasets/'+code+'/import?mode=merge','POST',input);
    else await api('datasets','POST',input);
    await api('datasets/'+code+'/publication','PUT',{published:true});
    console.log(code+': '+data.features.length+' features');
}
await api('admin/transit/mtr','PUT',config);
const network=await api('network?operator=mtr');
const frame=await api('trains?operator=mtr&mode=simulation&at='+encodeURIComponent(new Date().toISOString()));
console.log(JSON.stringify({operator:network.operator,lines:network.lines.length,stations:network.stations.length,
    patterns:config.mapping.patterns.length,mode:frame.mode,trains:frame.trains.length}));
