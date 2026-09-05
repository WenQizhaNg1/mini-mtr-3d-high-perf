import assert from 'node:assert/strict';
import pg from 'pg';

const { Client } = pg;
const connectionString = process.env.DATABASE_URL;

if (!connectionString) {
    throw new Error('DATABASE_URL is required');
}

const client = new Client({ connectionString });
const trainId = `train-position-test-${process.pid}-${Date.now()}`;
const patternId = 'ISL:KET-CHW';

async function positionAt(at) {
    const { rows } = await client.query(
        'SELECT * FROM mtr.train_positions($1) WHERE id = $2',
        [at, trainId]
    );
    return rows[0] || null;
}

try {
    await client.connect();
    await client.query('BEGIN');

    const { rows: stops } = await client.query(`
        SELECT stop_sequence, station_code, fraction
        FROM mtr.route_stops
        WHERE pattern_id = $1
        ORDER BY stop_sequence
        LIMIT 3
    `, [patternId]);
    assert.equal(stops.length, 3);

    await client.query(`
        INSERT INTO mtr.train_runs (id, service_date, pattern_id, starts_at, ends_at)
        VALUES ($1, '2026-09-04', $2, '2026-09-04T08:00:00Z', '2026-09-04T08:11:00Z')
    `, [trainId, patternId]);
    await client.query(`
        INSERT INTO mtr.train_legs (
            train_id, sequence, from_stop_sequence, to_stop_sequence,
            departure_at, arrival_at
        ) VALUES
            ($1, 0, $2, $3, '2026-09-04T08:00:00Z', '2026-09-04T08:05:00Z'),
            ($1, 1, $3, $4, '2026-09-04T08:06:00Z', '2026-09-04T08:11:00Z')
    `, [trainId, stops[0].stop_sequence, stops[1].stop_sequence, stops[2].stop_sequence]);

    const midpoint = await positionAt('2026-09-04T08:02:30Z');
    assert(midpoint);
    assert.equal(midpoint.state, 'running');
    assert.equal(midpoint.previous_station, stops[0].station_code);
    assert.equal(midpoint.next_station, stops[1].station_code);
    assert(Number.isFinite(midpoint.lng));
    assert(Number.isFinite(midpoint.lat));
    assert(midpoint.bearing >= 0 && midpoint.bearing <= 360);

    const { rows: [expectedMidpoint] } = await client.query(`
        SELECT
            ST_X(ST_LineInterpolatePoint(geom, $2)) AS lng,
            ST_Y(ST_LineInterpolatePoint(geom, $2)) AS lat
        FROM mtr.route_patterns
        WHERE id = $1
    `, [patternId, (stops[0].fraction + stops[1].fraction) / 2]);
    assert(Math.abs(midpoint.lng - expectedMidpoint.lng) < 1e-10);
    assert(Math.abs(midpoint.lat - expectedMidpoint.lat) < 1e-10);

    const dwell = await positionAt('2026-09-04T08:05:30Z');
    assert(dwell);
    assert.equal(dwell.state, 'dwell');
    assert.equal(dwell.previous_station, stops[1].station_code);
    assert.equal(dwell.next_station, stops[2].station_code);

    await client.query(`
        INSERT INTO mtr.realtime_offsets (train_id, delay_seconds, observed_at)
        VALUES ($1, 60, '2026-09-04T08:00:00Z')
    `, [trainId]);
    assert.equal(await positionAt('2026-09-04T08:00:30Z'), null);

    const delayedMidpoint = await positionAt('2026-09-04T08:03:30Z');
    assert(delayedMidpoint);
    assert.equal(delayedMidpoint.delay_seconds, 60);
    assert(Math.abs(delayedMidpoint.lng - midpoint.lng) < 1e-10);
    assert(Math.abs(delayedMidpoint.lat - midpoint.lat) < 1e-10);

    console.log('Verified running, dwell, bearing, and realtime delay positions.');
} finally {
    await client.query('ROLLBACK').catch(() => {});
    await client.end();
}
