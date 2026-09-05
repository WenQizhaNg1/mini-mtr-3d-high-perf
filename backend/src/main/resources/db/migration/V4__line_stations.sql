-- Line membership is independent of the route patterns currently imported.
-- For example, Racecourse belongs to EAL even when no imported pattern stops there.
CREATE TABLE app.line_station (
    line_id bigint NOT NULL REFERENCES app.line (id),
    station_id bigint NOT NULL REFERENCES app.station (id),
    PRIMARY KEY (line_id, station_id)
);

CREATE INDEX line_station_station_idx ON app.line_station (station_id);
