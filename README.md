# Mini MTR High Perf

A geospatial visualization project for complex transit scenarios, using Hong Kong MTR to explore lightweight rendering, spatial data modeling and extensible data integration.

Rebuilt from [7gugu/mini-mtr-3d](https://github.com/7gugu/mini-mtr-3d), inspired by [Mini Tokyo 3D](https://minitokyo3d.com/). Built with Vue 3, MapLibre, Spring Boot, PostGIS and Martin.

[中文说明](./README_zh.md)

![Mini MTR High Perf preview](https://picui.ogmua.cn/s1/2026/09/05/6a9c2e09d0e5f.webp)

![Mini MTR High Perf additional preview](https://picui.ogmua.cn/s1/2026/09/05/6a9c2e09dc919.webp)

## Design and optimization

### Lightweight 2.5D rendering

Native MapLibre polygon extrusion represents vehicles and buildings, removing the separate Three.js scene and map coordinate synchronization pipeline. Pitched views, atmospheric effects and day/night styles preserve visual depth.

Vehicle geometry updates on demand, capped at 30 updates per second in normal mode and one per second in power saving mode. Updates pause while the page is hidden, reducing ongoing computation and data submission.

### Static vector tiles and independent dynamic updates

Basemap, track and station data use vector tiles loaded by viewport and zoom level. PostGIS stores full spatial geometry, while Martin serves tiles, reducing the cost of loading and processing complete static datasets in the browser.

Moving vehicles use a separate GeoJSON source. The backend calculates motion frames and the frontend interpolates their display, allowing static map content and frequent vehicle updates to operate independently.

### Ontology-based spatial modeling

Spatial objects provide a shared foundation, with geometry, feature categories, business relationships and display styles modeled separately. Explicit relationships connect lines, stations, directed paths and trips; field mappings connect general geographic features to transit data.

This structure supports geometry reuse, domain extension and consistent maintenance. Map rendering and motion calculations share spatial data, with styles managed through native layer configuration.

### Standardized transit data exchange · In progress

The initial pipeline supports spatial imports, business field mappings, adapters and unified motion frames. Simulation derives positions from timetables and route distances. Realtime independently consumes GPS, along-route positions or station ETAs.

Import, export and cross-system exchange conventions remain under development; the complete standardized workflow is still unfinished. The current MTR realtime source provides ETAs only, so realtime mode displays arrival predictions.

## Current capabilities

- An MTR example covering 10 lines, 98 stations and 24 directed route patterns.
- Operator selection, simulation replay, timeline controls, vehicle following and day/night styles.
- A workbench for file imports, point and line drawing, geometry and property editing, field mapping and timetable configuration.
- Backend management of spatial data, business relationships and layer styles, with recalculation and frontend refresh after data changes.

## Roadmap

The vision is to evolve into geospatial visualization infrastructure capable of supporting complex scenarios, spanning data integration, editing, maintenance, motion computation and interactive display.

| Area | Next steps |
| --- | --- |
| Editing experience | Refine geometry editing, field mapping, stop ordering and validation feedback to simplify complex configurations |
| Authentication and authorization | Extend the management token mechanism with login, identity management and operation-level access controls |
| Data exchange standards | Complete track, station and timetable import/export conventions; validate reuse across data sources and adapters |
| Data maintenance | Improve validation, dependent updates and error diagnosis for reliable ongoing maintenance |
| Weather layers | Integrate spatial weather data with map overlays and temporal visualization of precipitation, wind fields and other conditions |
| Realtime events | Integrate operational disruptions and service changes with spatial positioning, status updates and affected-line visualization |
| Complex workloads | Establish reproducible performance benchmarks and validate larger datasets, more moving features and multiple business layers |

## Local development

Use Node 24 for the frontend, and Java 25 with Maven for the backend. Configure the root `.env` following the [backend setup](./backend/README.md).

From the repository root, install frontend dependencies and start the database and backend:

```sh
npm --prefix front ci
docker compose up -d postgres
mvn -f backend/pom.xml spring-boot:run
```

After the backend initializes the database, use another terminal to start the tile server, import the MTR example and launch the frontend:

```sh
docker compose up -d martin
node --env-file=.env scripts/import-mtr.mjs
npm --prefix front run dev
```

The frontend defaults to http://127.0.0.1:8080 and the backend to http://127.0.0.1:3002. Build the frontend with `npm --prefix front run build` and package the backend with `mvn -f backend/pom.xml package`.

[Architecture](./docs/architecture.md) · [Database design](./docs/database-design.md) · [MTR import example](./docs/mtr-import-example.md) · [Frontend workbench](./front/README.md)
