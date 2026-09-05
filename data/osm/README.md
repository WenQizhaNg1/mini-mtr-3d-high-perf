# Hong Kong MTR OSM snapshot

`hk-mtr-2026-09-04.osm` is a raw OpenStreetMap XML snapshot for the ten MTR
heavy-rail lines currently supported by this project. It contains matching
`route=subway` relations plus all recursively referenced relations, ways, and
nodes. The query also requested `route_master=subway` relations, but none with
the ten MTR line refs were present in this snapshot.

- Source: OpenStreetMap via Overpass API
- OSM base timestamp: `2026-09-04T12:03:06Z`
- Coordinate reference system: WGS84 longitude/latitude (`EPSG:4326`)
- Objects: 9,625 nodes, 983 ways, 56 relations
- SHA-256: `8A2B3BF3F3610C56882354C290721BF2C67755755F3FE570FAFBD72171BA461B`
- License: Open Database License (ODbL); attribution to OpenStreetMap
  contributors is required

The Overpass query used to create the snapshot was:

```overpass
[out:xml][timeout:600];
(
  relation
    ["type"="route"]
    ["route"="subway"]
    ["ref"~"^(ISL|TWL|KTL|TKL|EAL|TML|TCL|AEL|DRL|SIL)$"]
    (22.13,113.82,22.58,114.45);
  relation
    ["type"="route_master"]
    ["route_master"="subway"]
    ["ref"~"^(ISL|TWL|KTL|TKL|EAL|TML|TCL|AEL|DRL|SIL)$"]
    (22.13,113.82,22.58,114.45);
);
(._; >>;);
out meta;
```

## Generated data

Run the checked-in normalizer after replacing or updating the source snapshot:

```shell
npm --prefix scripts ci
npm --prefix scripts run build:osm
```

The command reads `hk-mtr-2026-09-04.osm` and recreates `generated/`:

- `mtr-route-patterns.json`: 24 directional route patterns with ordered stops,
  cumulative distances, and trimmed WGS84 geometry.
- `mtr-route-patterns.geojson`: the same 24 route geometries as MapLibre-ready
  GeoJSON features.
- `mtr-stations.geojson`: 98 logical MTR stations as WGS84 point features.

The initial pattern set contains both terminal branches of the Tseung Kwan O
and East Rail lines, but excludes short turns, deprecated Tuen Ma relations,
and Racecourse variants. Racecourse remains in the station GeoJSON for future
special-service patterns; it is not inserted into normal East Rail routes.

The normalizer fails when relation geometry is discontinuous, a selected route
uses disused or razed track, a stop is more than 10 metres from its route, or
the expected pattern and station counts change. See `AUDIT.md` for the source
review and known normalization decisions.

## Database import and serving

The old Node OSM importer has been removed after the Java backend migration.
`npm --prefix scripts run build:osm` still produces offline GeoJSON, but does not import it
into the database. A general GeoJSON import and feature editor is planned and
has not been implemented yet.

The existing local dataset has already been migrated to `app`. The Java backend
retains an explicit, one-time importer from an existing `mtr` database snapshot;
it does not import raw OSM. See [Java backend setup](../../backend/README.md).

Martin publishes `app.map_routes` and `app.map_stations` through the existing
`/mtr-routes,mtr-stations` source on port 8081. Java provides train snapshots and
SSE on port 3002. Old Node import, migration and schedule commands no longer apply.
