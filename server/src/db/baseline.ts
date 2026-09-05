import type { Client } from 'pg';

const expectedColumns = [
    'realtime_offsets.delay_seconds:double precision:true',
    'realtime_offsets.observed_at:timestamp with time zone:true',
    'realtime_offsets.train_id:text:true',
    'route_patterns.colour:text:true',
    'route_patterns.from_code:text:true',
    'route_patterns.from_name:text:true',
    'route_patterns.geom:geometry(LineString,4326):true',
    'route_patterns.id:text:true',
    'route_patterns.length_m:double precision:true',
    'route_patterns.line_id:text:true',
    'route_patterns.osm_relation_id:bigint:true',
    'route_patterns.stop_count:integer:true',
    'route_patterns.to_code:text:true',
    'route_patterns.to_name:text:true',
    'route_patterns.via:text:true',
    'route_stops.distance_m:double precision:true',
    'route_stops.fraction:double precision:true',
    'route_stops.pattern_id:text:true',
    'route_stops.station_code:text:true',
    'route_stops.stop_sequence:integer:true',
    'stations.code:text:true',
    'stations.geom:geometry(Point,4326):true',
    'stations.interchange:boolean:true',
    'stations.line_ids:text:true',
    'stations.name:text:true',
    'stations.name_en:text:true',
    'stations.name_zh:text:true',
    'train_legs.arrival_at:timestamp with time zone:true',
    'train_legs.departure_at:timestamp with time zone:true',
    'train_legs.from_stop_sequence:integer:true',
    'train_legs.sequence:integer:true',
    'train_legs.to_stop_sequence:integer:true',
    'train_legs.train_id:text:true',
    'train_runs.ends_at:timestamp with time zone:true',
    'train_runs.id:text:true',
    'train_runs.pattern_id:text:true',
    'train_runs.service_date:date:true',
    'train_runs.starts_at:timestamp with time zone:true',
].sort();

const expectedConstraints = [
    'realtime_offsets.realtime_offsets_pkey:p',
    'realtime_offsets.realtime_offsets_train_id_fkey:f',
    'route_patterns.route_patterns_osm_relation_id_key:u',
    'route_patterns.route_patterns_pkey:p',
    'route_stops.route_stops_distance_m_check:c',
    'route_stops.route_stops_fraction_check:c',
    'route_stops.route_stops_pattern_id_fkey:f',
    'route_stops.route_stops_pkey:p',
    'route_stops.route_stops_station_code_fkey:f',
    'route_stops.route_stops_stop_sequence_check:c',
    'stations.stations_pkey:p',
    'train_legs.train_legs_check1:c',
    'train_legs.train_legs_check:c',
    'train_legs.train_legs_pkey:p',
    'train_legs.train_legs_sequence_check:c',
    'train_legs.train_legs_train_id_fkey:f',
    'train_runs.train_runs_check:c',
    'train_runs.train_runs_pattern_id_fkey:f',
    'train_runs.train_runs_pkey:p',
].sort();

const expectedIndexes = [
    'route_patterns_geom_idx',
    'route_patterns_line_id_idx',
    'route_stops_station_code_idx',
    'stations_geom_idx',
    'train_legs_window_idx',
    'train_runs_window_idx',
].sort();

const expectedFunctionResult = 'TABLE(id text, line_id text, pattern_id text, colour text, lng double precision, lat double precision, bearing double precision, state text, previous_station text, next_station text, destination_station text, delay_seconds double precision)';

function assertSame(label: string, actual: string[], expected: string[]) {
    const left = [...actual].sort();
    const right = [...expected].sort();
    if (left.length === right.length && left.every((value, index) => value === right[index])) return;
    const missing = right.filter(value => !left.includes(value));
    const unexpected = left.filter(value => !right.includes(value));
    throw new Error(`${label} mismatch; missing=${JSON.stringify(missing)} unexpected=${JSON.stringify(unexpected)}`);
}

export async function validateBaselineSchema(client: Client): Promise<void> {
    const extension = await client.query<{ installed: boolean }>(`
        SELECT EXISTS (
            SELECT 1 FROM pg_extension WHERE extname = 'postgis'
        ) AS installed
    `);
    if (!extension.rows[0]?.installed) throw new Error('PostGIS extension is not installed');

    const tables = await client.query<{ table_name: string }>(`
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'mtr' AND table_type = 'BASE TABLE'
        ORDER BY table_name
    `);
    assertSame('mtr tables', tables.rows.map(row => row.table_name), [
        'realtime_offsets',
        'route_patterns',
        'route_stops',
        'stations',
        'train_legs',
        'train_runs',
    ]);

    const columns = await client.query<{
        table_name: string;
        column_name: string;
        data_type: string;
        not_null: boolean;
    }>(`
        SELECT
            relation.relname AS table_name,
            attribute.attname AS column_name,
            format_type(attribute.atttypid, attribute.atttypmod) AS data_type,
            attribute.attnotnull AS not_null
        FROM pg_attribute AS attribute
        JOIN pg_class AS relation ON relation.oid = attribute.attrelid
        JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'mtr'
          AND relation.relkind = 'r'
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
        ORDER BY relation.relname, attribute.attnum
    `);
    assertSame('mtr columns', columns.rows.map(row => (
        `${row.table_name}.${row.column_name}:${row.data_type}:${row.not_null}`
    )), expectedColumns);

    const constraints = await client.query<{
        table_name: string;
        constraint_name: string;
        constraint_type: string;
    }>(`
        SELECT
            relation.relname AS table_name,
            constraint_info.conname AS constraint_name,
            constraint_info.contype AS constraint_type
        FROM pg_constraint AS constraint_info
        JOIN pg_class AS relation ON relation.oid = constraint_info.conrelid
        JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'mtr'
          AND constraint_info.contype IN ('p', 'u', 'f', 'c')
        ORDER BY relation.relname, constraint_info.conname
    `);
    assertSame('mtr constraints', constraints.rows.map(row => (
        `${row.table_name}.${row.constraint_name}:${row.constraint_type}`
    )), expectedConstraints);

    const indexes = await client.query<{ indexname: string }>(`
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'mtr'
          AND indexname = ANY($1::text[])
        ORDER BY indexname
    `, [expectedIndexes]);
    assertSame('mtr indexes', indexes.rows.map(row => row.indexname), expectedIndexes);

    const functions = await client.query<{
        arguments: string;
        result: string;
        volatility: string;
    }>(`
        SELECT
            pg_get_function_arguments(function_info.oid) AS arguments,
            pg_get_function_result(function_info.oid) AS result,
            function_info.provolatile AS volatility
        FROM pg_proc AS function_info
        JOIN pg_namespace AS namespace ON namespace.oid = function_info.pronamespace
        WHERE namespace.nspname = 'mtr'
          AND function_info.proname = 'train_positions'
    `);
    const trainPositions = functions.rows[0];
    if (
        functions.rows.length !== 1
        || trainPositions?.arguments !== 'p_at timestamp with time zone'
        || trainPositions.result !== expectedFunctionResult
        || trainPositions.volatility !== 's'
    ) {
        throw new Error('mtr.train_positions signature or volatility does not match the initial migration');
    }
}
