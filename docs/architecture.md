# 架构边界

## 目录

- `front/`：Vue 3 + MapLibre，消费 Java API 和 Martin 瓦片。
- `backend/`：Java 25 + Spring Boot，按 api、config、model、repo、service 分层。
- `data/`、`scripts/`：共享服务配置、已有空间数据与离线构建工具。
- `map/`：Martin、底图与地图资源配置。

旧 Node 后端 `server/` 已删除，可从 Git 历史恢复。当前启动与验证方法见 [Java 后端说明](../backend/README.md)。

## 职责

后端负责路网目录、服务日、计划生成与回放、港铁 ETA 匹配、连续运动推演、检查点恢复、运营状态和天气缓存。JDBC/jOOQ 访问数据库，Flyway 管理迁移；Java/JTS 完成运行位置计算，PostGIS 负责空间存储、校验与查询。

前端负责图层渲染、时间轴、选中项、详情卡片、地图跟随、语言与展示格式，并按服务端时间播放短期轨迹。HTTP 响应类型位于 `front/src/contracts.ts`，不引用后端源码。

回放保持无状态：前端提交目标时间，后端返回该时刻快照。实时 SSE 共享每秒快照，后端不保存浏览器的播放位置或倍速。

## 数据与接口

- 业务表位于 `app`，Flyway 元数据位于 `backend_meta`。
- Martin 通过 `app.map_routes` 和 `app.map_stations` 视图发布线路、站点瓦片，底图来自本地 OSM MBTiles。
- Java 默认监听 3002，提供路网、服务日、列车快照/SSE、运营状态及天气接口。
- 旧 `mtr` 表保留供一次性数据导入和显式迁移对照测试使用；正常运行不依赖它。
- 原位置 SQL 仅保留在 Java 测试资源中，作为独立对照样本，不作为 Flyway 迁移执行。

OSM 专用导入器不迁移。GeoJSON 导入、要素与样式编辑属于后续管理能力，尚未实现。
