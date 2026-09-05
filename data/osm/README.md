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
npm run data:osm:build
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

## PostGIS import

Set a PostgreSQL connection string for the current shell, then run the importer:

```powershell
$env:DATABASE_URL = "postgresql://user:password@localhost:5432/database"
npm run db:migrate
npm run data:osm:import
Remove-Item Env:DATABASE_URL
```

Database objects are created only by `npm run db:migrate`. The importer validates
and transactionally replaces route patterns, route stops, and stations, then
verifies all 24 patterns, 276 route stops, 98 stations, and their PostGIS
geometries. Re-importing unchanged data preserves schedules and realtime offsets.

If route-stop semantics changed for a pattern used by an existing schedule, the
import stops before writing and reports the affected patterns and service dates.
After reviewing the change, explicitly replace the network and regenerate those
service dates in one transaction with:

```powershell
npm run data:osm:import -- --allow-pattern-changes
```

Regenerated runs receive fresh realtime offsets. The importer and schedule
command share a PostgreSQL advisory lock so they cannot mix old and new route
stops. Martin exposes the route and station tables as vector-tile sources;
dynamic train positions are served by the Train API.

For the local Docker stack, copy `.env.example` to `.env`, choose a local
database password, and initialize the services in this order:

```powershell
Copy-Item .env.example .env
npm run server:install
docker compose up -d --wait postgres
$env:DATABASE_URL = "postgresql://mtr:your-password@127.0.0.1:15432/mtr"
npm run db:migrate
npm run data:osm:import
npm run data:mtr:schedule
npm run data:osm:test-positions
Remove-Item Env:DATABASE_URL
docker compose up -d --build martin train-api
```

Martin then serves the dashboard at `http://127.0.0.1:8081/`, the route and
station TileJSON documents at `/mtr-routes` and `/mtr-stations`, and their
built-in composite source at `/mtr-routes,mtr-stations`. The Train API serves
snapshots and SSE at `http://127.0.0.1:3001/api/trains` and
`http://127.0.0.1:3001/api/trains/live`.
