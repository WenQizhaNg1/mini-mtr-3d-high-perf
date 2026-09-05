import { sql } from 'drizzle-orm';
import {
    bigint,
    boolean,
    check,
    customType,
    date,
    doublePrecision,
    foreignKey,
    index,
    integer,
    jsonb,
    primaryKey,
    text,
    timestamp,
    unique,
} from 'drizzle-orm/pg-core';
import { pgSchema } from 'drizzle-orm/pg-core';

const lineString4326 = customType<{ data: unknown; driverData: string }>({
    dataType: () => 'geometry(LineString, 4326)',
});
const point4326 = customType<{ data: unknown; driverData: string }>({
    dataType: () => 'geometry(Point, 4326)',
});

export const mtr = pgSchema('mtr');

export const routePatterns = mtr.table('route_patterns', {
    id: text().primaryKey(),
    osmRelationId: bigint('osm_relation_id', { mode: 'number' }).notNull(),
    lineId: text('line_id').notNull(),
    fromCode: text('from_code').notNull(),
    toCode: text('to_code').notNull(),
    fromName: text('from_name').notNull(),
    toName: text('to_name').notNull(),
    via: text().notNull(),
    colour: text().notNull(),
    lengthM: doublePrecision('length_m').notNull(),
    stopCount: integer('stop_count').notNull(),
    geom: lineString4326().notNull(),
}, table => [
    unique('route_patterns_osm_relation_id_key').on(table.osmRelationId),
    index('route_patterns_geom_idx').using('gist', table.geom),
    index('route_patterns_line_id_idx').on(table.lineId),
]);

export const stations = mtr.table('stations', {
    code: text().primaryKey(),
    name: text().notNull(),
    nameEn: text('name_en').notNull(),
    nameZh: text('name_zh').notNull(),
    lineIds: text('line_ids').notNull(),
    interchange: boolean().notNull(),
    geom: point4326().notNull(),
}, table => [index('stations_geom_idx').using('gist', table.geom)]);

export const routeStops = mtr.table('route_stops', {
    patternId: text('pattern_id').notNull(),
    stopSequence: integer('stop_sequence').notNull(),
    stationCode: text('station_code').notNull(),
    distanceM: doublePrecision('distance_m').notNull(),
    fraction: doublePrecision().notNull(),
}, table => [
    primaryKey({ name: 'route_stops_pkey', columns: [table.patternId, table.stopSequence] }),
    foreignKey({
        name: 'route_stops_pattern_id_fkey',
        columns: [table.patternId],
        foreignColumns: [routePatterns.id],
    }).onDelete('cascade'),
    foreignKey({
        name: 'route_stops_station_code_fkey',
        columns: [table.stationCode],
        foreignColumns: [stations.code],
    }),
    check('route_stops_stop_sequence_check', sql`${table.stopSequence} >= 0`),
    check('route_stops_distance_m_check', sql`${table.distanceM} >= 0`),
    check('route_stops_fraction_check', sql`${table.fraction} >= 0 AND ${table.fraction} <= 1`),
    index('route_stops_station_code_idx').on(table.stationCode),
]);

export const trainRuns = mtr.table('train_runs', {
    id: text().primaryKey(),
    serviceDate: date('service_date', { mode: 'string' }).notNull(),
    patternId: text('pattern_id').notNull(),
    startsAt: timestamp('starts_at', { withTimezone: true, mode: 'date' }).notNull(),
    endsAt: timestamp('ends_at', { withTimezone: true, mode: 'date' }).notNull(),
}, table => [
    foreignKey({
        name: 'train_runs_pattern_id_fkey',
        columns: [table.patternId],
        foreignColumns: [routePatterns.id],
    }),
    check('train_runs_check', sql`${table.endsAt} > ${table.startsAt}`),
    index('train_runs_service_date_idx').on(table.serviceDate),
    index('train_runs_window_idx').on(table.startsAt, table.endsAt),
]);

export const trainLegs = mtr.table('train_legs', {
    trainId: text('train_id').notNull(),
    sequence: integer().notNull(),
    fromStopSequence: integer('from_stop_sequence').notNull(),
    toStopSequence: integer('to_stop_sequence').notNull(),
    departureAt: timestamp('departure_at', { withTimezone: true, mode: 'date' }).notNull(),
    arrivalAt: timestamp('arrival_at', { withTimezone: true, mode: 'date' }).notNull(),
}, table => [
    primaryKey({ name: 'train_legs_pkey', columns: [table.trainId, table.sequence] }),
    foreignKey({
        name: 'train_legs_train_id_fkey',
        columns: [table.trainId],
        foreignColumns: [trainRuns.id],
    }).onDelete('cascade'),
    check('train_legs_sequence_check', sql`${table.sequence} >= 0`),
    check('train_legs_check', sql`${table.toStopSequence} > ${table.fromStopSequence}`),
    check('train_legs_check1', sql`${table.arrivalAt} > ${table.departureAt}`),
    index('train_legs_window_idx').on(table.departureAt, table.arrivalAt),
]);

export const realtimeOffsets = mtr.table('realtime_offsets', {
    trainId: text('train_id').primaryKey(),
    delaySeconds: doublePrecision('delay_seconds').notNull().default(0),
    observedAt: timestamp('observed_at', { withTimezone: true, mode: 'date' }).notNull(),
}, table => [
    foreignKey({
        name: 'realtime_offsets_train_id_fkey',
        columns: [table.trainId],
        foreignColumns: [trainRuns.id],
    }).onDelete('cascade'),
]);

export const motionCheckpoint = mtr.table('motion_checkpoint', {
    id: integer().primaryKey(),
    state: jsonb().notNull(),
}, table => [check('motion_checkpoint_singleton', sql`${table.id} = 1`)]);

export const databaseSchema = {
    motionCheckpoint,
    routePatterns,
    stations,
    routeStops,
    trainRuns,
    trainLegs,
    realtimeOffsets,
};
