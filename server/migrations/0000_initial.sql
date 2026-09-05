CREATE EXTENSION IF NOT EXISTS postgis;
--> statement-breakpoint
CREATE SCHEMA "mtr";
--> statement-breakpoint
CREATE TABLE "mtr"."realtime_offsets" (
	"train_id" text PRIMARY KEY NOT NULL,
	"delay_seconds" double precision DEFAULT 0 NOT NULL,
	"observed_at" timestamp with time zone NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mtr"."route_patterns" (
	"id" text PRIMARY KEY NOT NULL,
	"osm_relation_id" bigint NOT NULL,
	"line_id" text NOT NULL,
	"from_code" text NOT NULL,
	"to_code" text NOT NULL,
	"from_name" text NOT NULL,
	"to_name" text NOT NULL,
	"via" text NOT NULL,
	"colour" text NOT NULL,
	"length_m" double precision NOT NULL,
	"stop_count" integer NOT NULL,
	"geom" geometry(LineString, 4326) NOT NULL,
	CONSTRAINT "route_patterns_osm_relation_id_key" UNIQUE("osm_relation_id")
);
--> statement-breakpoint
CREATE TABLE "mtr"."route_stops" (
	"pattern_id" text NOT NULL,
	"stop_sequence" integer NOT NULL,
	"station_code" text NOT NULL,
	"distance_m" double precision NOT NULL,
	"fraction" double precision NOT NULL,
	CONSTRAINT "route_stops_pkey" PRIMARY KEY("pattern_id","stop_sequence"),
	CONSTRAINT "route_stops_stop_sequence_check" CHECK ("mtr"."route_stops"."stop_sequence" >= 0),
	CONSTRAINT "route_stops_distance_m_check" CHECK ("mtr"."route_stops"."distance_m" >= 0),
	CONSTRAINT "route_stops_fraction_check" CHECK ("mtr"."route_stops"."fraction" >= 0 AND "mtr"."route_stops"."fraction" <= 1)
);
--> statement-breakpoint
CREATE TABLE "mtr"."stations" (
	"code" text PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"name_en" text NOT NULL,
	"name_zh" text NOT NULL,
	"line_ids" text NOT NULL,
	"interchange" boolean NOT NULL,
	"geom" geometry(Point, 4326) NOT NULL
);
--> statement-breakpoint
CREATE TABLE "mtr"."train_legs" (
	"train_id" text NOT NULL,
	"sequence" integer NOT NULL,
	"from_stop_sequence" integer NOT NULL,
	"to_stop_sequence" integer NOT NULL,
	"departure_at" timestamp with time zone NOT NULL,
	"arrival_at" timestamp with time zone NOT NULL,
	CONSTRAINT "train_legs_pkey" PRIMARY KEY("train_id","sequence"),
	CONSTRAINT "train_legs_sequence_check" CHECK ("mtr"."train_legs"."sequence" >= 0),
	CONSTRAINT "train_legs_check" CHECK ("mtr"."train_legs"."to_stop_sequence" > "mtr"."train_legs"."from_stop_sequence"),
	CONSTRAINT "train_legs_check1" CHECK ("mtr"."train_legs"."arrival_at" > "mtr"."train_legs"."departure_at")
);
--> statement-breakpoint
CREATE TABLE "mtr"."train_runs" (
	"id" text PRIMARY KEY NOT NULL,
	"service_date" date NOT NULL,
	"pattern_id" text NOT NULL,
	"starts_at" timestamp with time zone NOT NULL,
	"ends_at" timestamp with time zone NOT NULL,
	CONSTRAINT "train_runs_check" CHECK ("mtr"."train_runs"."ends_at" > "mtr"."train_runs"."starts_at")
);
--> statement-breakpoint
ALTER TABLE "mtr"."realtime_offsets" ADD CONSTRAINT "realtime_offsets_train_id_fkey" FOREIGN KEY ("train_id") REFERENCES "mtr"."train_runs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mtr"."route_stops" ADD CONSTRAINT "route_stops_pattern_id_fkey" FOREIGN KEY ("pattern_id") REFERENCES "mtr"."route_patterns"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mtr"."route_stops" ADD CONSTRAINT "route_stops_station_code_fkey" FOREIGN KEY ("station_code") REFERENCES "mtr"."stations"("code") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mtr"."train_legs" ADD CONSTRAINT "train_legs_train_id_fkey" FOREIGN KEY ("train_id") REFERENCES "mtr"."train_runs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "mtr"."train_runs" ADD CONSTRAINT "train_runs_pattern_id_fkey" FOREIGN KEY ("pattern_id") REFERENCES "mtr"."route_patterns"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "route_patterns_geom_idx" ON "mtr"."route_patterns" USING gist ("geom");--> statement-breakpoint
CREATE INDEX "route_patterns_line_id_idx" ON "mtr"."route_patterns" USING btree ("line_id");--> statement-breakpoint
CREATE INDEX "route_stops_station_code_idx" ON "mtr"."route_stops" USING btree ("station_code");--> statement-breakpoint
CREATE INDEX "stations_geom_idx" ON "mtr"."stations" USING gist ("geom");--> statement-breakpoint
CREATE INDEX "train_legs_window_idx" ON "mtr"."train_legs" USING btree ("departure_at","arrival_at");--> statement-breakpoint
CREATE INDEX "train_runs_window_idx" ON "mtr"."train_runs" USING btree ("starts_at","ends_at");
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
    delay_seconds double precision
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
            END AS fraction
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
        delay_seconds
    FROM located
    ORDER BY train_id;
$function$;
