# Mini MTR

Hong Kong MTR visualization with Vue 3, MapLibre, Spring Boot and PostGIS. Inspired by [Mini Tokyo 3D](https://minitokyo3d.com/); original project by [7gugu](https://github.com/7gugu/mini-mtr-3d).

[中文说明](./README_zh.md)

- OSM basemap and MTR route/station vector tiles from Martin.
- Shared live train snapshots, server-side position calculation and stateless replay.
- Hong Kong timeline, pause, seek and 1–60× playback.
- Train/station details, current incidents, weather, bilingual UI and power save.

Positions are estimates based on generated schedules and live arrival corrections, not onboard GPS. Replay does not provide historical weather or incident records.

## Development

Use Node 24, Java 25 and Maven:

```sh
npm ci
npm run front:install
docker compose up -d postgres
npm run server
# In another terminal, after Java has applied Flyway migrations:
docker compose up -d martin
npm start
npm run build
npm test
```

Configure root .env PostgreSQL settings using [backend setup](./backend/README.md). Frontend: http://127.0.0.1:8080; Java API: http://127.0.0.1:3002. Copy front/.env.example to front/.env if the service URLs differ. Build output is front/dist; a hosted frontend requires accessible Martin/API URLs at build time. The old Node backend has been removed; npm test runs frontend and Java unit tests.

[Architecture](./docs/architecture.md) · [Migration plan](./docs/server-refactor-plan.md) · [Frontend functionality and checks](./docs/frontend-migration.md)

The old AMap/Three.js application and its build chain have been removed. Git history retains the original code. The editor is deferred; map and train visual redesign remains future work.
