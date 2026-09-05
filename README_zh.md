# Mini MTR

基于 Vue 3、MapLibre、Spring Boot 与 PostGIS 的港铁地图。原作来自 [7gugu](https://github.com/7gugu/mini-mtr-3d)，灵感来自 [Mini Tokyo 3D](https://minitokyo3d.com/)。

- Martin 提供 OSM 底图及线路、站点矢量瓦片。
- 后端计算列车位置，提供共享实时快照和无状态回放。
- 香港时间轴支持日期跳转、拖动、暂停和 1–60 倍速。
- 列车/站点详情、当前线路事件、天气、中英文界面与省电模式。

列车位置由生成班次和实时到站修正估算，并非车载 GPS 定位。回放不包含历史天气和运营事件归档。

## 开发

使用 Node 24、Java 25 和 Maven：

```sh
npm ci
npm run front:install
docker compose up -d postgres
npm run server
# Java 完成 Flyway 迁移后，在另一个终端执行：
docker compose up -d martin
npm start
npm run build
npm test
```

按[Java 后端说明](./backend/README.md)配置根目录 .env 的 PostgreSQL 参数。前端地址为 http://127.0.0.1:8080，Java API 为 http://127.0.0.1:3002。服务地址变化时，将 front/.env.example 复制为 front/.env 并调整。构建产物为 front/dist，托管时需在构建阶段配置浏览器可访问的 Martin/API 地址。旧 Node 后端已删除；npm test 运行前端与 Java 单元测试，数据库集成验证命令见后端说明。

[架构](./docs/architecture.md) · [迁移计划](./docs/server-refactor-plan.md) · [前端功能与验证](./docs/frontend-migration.md)

旧 AMap/Three.js 前端及构建链路已移除，原代码可从 Git 历史查看。编辑器留待独立管理功能设计，地图和列车样式后续统一调整。
