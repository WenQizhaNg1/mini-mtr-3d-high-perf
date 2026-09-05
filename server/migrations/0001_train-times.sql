DROP FUNCTION mtr.train_positions(timestamptz);
--> statement-breakpoint
CREATE FUNCTION mtr.train_positions(p_at timestamptz)
RETURNS TABLE (
    id text,
    line_id text,
    pattern_id text,
    colour text,
    lng double precision,
    lat double precision,
    bearing double precision,
    state text,
    previous_station text,
    next_station text,
    destination_station text,
    delay_seconds double precision,
    previous_time timestamptz,
    next_time timestamptz
)
LANGUAGE sql
STABLE
AS $function$
    WITH active_runs AS (
        SELECT
            run.id,
            run.pattern_id,
            COALESCE(offsets.delay_seconds, 0) AS delay_seconds
        FROM mtr.train_runs AS run
        LEFT JOIN mtr.realtime_offsets AS offsets ON offsets.train_id = run.id
        WHERE p_at >= run.starts_at + make_interval(secs => COALESCE(offsets.delay_seconds, 0))
          AND p_at <= run.ends_at + make_interval(secs => COALESCE(offsets.delay_seconds, 0))
    ),
    leg_windows AS (
        SELECT
            leg.*,
            LEAD(leg.departure_at) OVER (
                PARTITION BY leg.train_id ORDER BY leg.sequence
            ) AS next_departure_at,
            LEAD(leg.arrival_at) OVER (
                PARTITION BY leg.train_id ORDER BY leg.sequence
            ) AS next_arrival_at,
            LEAD(leg.to_stop_sequence) OVER (
                PARTITION BY leg.train_id ORDER BY leg.sequence
            ) AS next_to_stop_sequence
        FROM mtr.train_legs AS leg
        JOIN active_runs AS run ON run.id = leg.train_id
    ),
    active_legs AS (
        SELECT
            leg.*,
            run.pattern_id,
            run.delay_seconds,
            CASE
                WHEN p_at < leg.arrival_at + make_interval(secs => run.delay_seconds)
                    THEN 'running'
                ELSE 'dwell'
            END AS state
        FROM leg_windows AS leg
        JOIN active_runs AS run ON run.id = leg.train_id
        WHERE (
            p_at >= leg.departure_at + make_interval(secs => run.delay_seconds)
            AND p_at < leg.arrival_at + make_interval(secs => run.delay_seconds)
        ) OR (
            leg.next_departure_at IS NOT NULL
            AND p_at >= leg.arrival_at + make_interval(secs => run.delay_seconds)
            AND p_at < leg.next_departure_at + make_interval(secs => run.delay_seconds)
        )
    ),
    measured AS (
        SELECT
            leg.*,
            route.line_id,
            route.colour,
            route.to_code AS destination_station,
            route.geom,
            from_stop.station_code AS from_station,
            to_stop.station_code AS to_station,
            next_stop.station_code AS following_station,
            CASE
                WHEN leg.state = 'dwell' THEN to_stop.fraction
                ELSE from_stop.fraction +
                    EXTRACT(EPOCH FROM (
                        p_at - leg.departure_at - make_interval(secs => leg.delay_seconds)
                    )) / EXTRACT(EPOCH FROM (leg.arrival_at - leg.departure_at))
                    * (to_stop.fraction - from_stop.fraction)
            END AS fraction,
            CASE
                WHEN leg.state = 'dwell' THEN leg.arrival_at
                ELSE leg.departure_at
            END + make_interval(secs => leg.delay_seconds) AS previous_time,
            CASE
                WHEN leg.state = 'dwell' THEN leg.next_arrival_at
                ELSE leg.arrival_at
            END + make_interval(secs => leg.delay_seconds) AS next_time
        FROM active_legs AS leg
        JOIN mtr.route_patterns AS route ON route.id = leg.pattern_id
        JOIN mtr.route_stops AS from_stop
          ON from_stop.pattern_id = leg.pattern_id
         AND from_stop.stop_sequence = leg.from_stop_sequence
        JOIN mtr.route_stops AS to_stop
          ON to_stop.pattern_id = leg.pattern_id
         AND to_stop.stop_sequence = leg.to_stop_sequence
        LEFT JOIN mtr.route_stops AS next_stop
          ON next_stop.pattern_id = leg.pattern_id
         AND next_stop.stop_sequence = leg.next_to_stop_sequence
    ),
    located AS (
        SELECT
            measured.*,
            ST_LineInterpolatePoint(geom, fraction) AS position,
            ST_LineInterpolatePoint(
                geom,
                CASE
                    WHEN fraction >= 0.9999 THEN GREATEST(0, fraction - 0.0001)
                    ELSE LEAST(1, fraction + 0.0001)
                END
            ) AS direction_point
        FROM measured
    )
    SELECT
        train_id AS id,
        line_id,
        pattern_id,
        colour,
        ST_X(position) AS lng,
        ST_Y(position) AS lat,
        degrees(
            CASE
                WHEN fraction >= 0.9999 THEN ST_Azimuth(
                    ST_Transform(direction_point, 3857),
                    ST_Transform(position, 3857)
                )
                ELSE ST_Azimuth(
                    ST_Transform(position, 3857),
                    ST_Transform(direction_point, 3857)
                )
            END
        ) AS bearing,
        state,
        CASE WHEN state = 'dwell' THEN to_station ELSE from_station END AS previous_station,
        CASE WHEN state = 'dwell' THEN following_station ELSE to_station END AS next_station,
        destination_station,
        delay_seconds,
        previous_time,
        next_time
    FROM located
    ORDER BY train_id;
$function$;
