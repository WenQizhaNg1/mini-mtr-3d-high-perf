export interface Pattern {
    code: string; featureKey: string; direction: string; reversed: boolean;
    stops: {stationKey: string; fraction: number | null}[];
}
export interface TimetableTrip {
    key: string; pattern: string; serviceDate: string | null;
    stops: {seq:number; arrival:string|null; departure:string|null}[];
}
export interface TransitConfig {
    name:string; timezone:string; adapter:string; mode:'simulation'|'realtime';
    mapping: { routesDataset:string; stationsDataset:string; routeFields:Record<string,string>; stationFields:Record<string,string>;
        patterns:Pattern[]; snapToleranceMeters:number };
    simulation: {serviceDayStart:string; rules:{id:string;pattern:string;first:string;last:string;headwaySeconds:number;speedKmph:number;dwellSeconds:number}[]; timetable:TimetableTrip[]};
    realtime: {monitors:{line:string;station:string}[];staleAfterSeconds:number};
}
export interface Configured {code:string; id:number; config:TransitConfig}
