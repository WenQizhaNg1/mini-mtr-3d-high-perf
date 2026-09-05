# 架构边界

## 目录

- `front/`：Vue 3 + MapLibre，消费 Java API 和 Martin 瓦片。
- `backend/`：Java 25 + Spring Boot，按 api、config、model、repo、service 分层。
- `data/`、`scripts/`：共享服务配置、已有空间数据与离线构建工具。
- `map/`：Martin、底图与地图资源配置。

旧 Node 后端 `server/` 已删除，可从 Git 历史恢复。当前启动与验证方法见 [Java 后端说明](../backend/README.md)。

## 职责

后端负责路网目录、服务日、计划生成与回放、港铁 ETA 匹配、连续运动推演、检查点恢复、运营状态和天气缓存。JDBC/jOOQ 访问数据库，Flyway 管理迁移；Java/JTS 完成运行位置计算，PostGIS 负责空间存储、校验与查询。

前端负责图层渲染、时间轴、选中项、详情卡片、地图跟随、语言与展示格式，并按服务端时间播放短期轨迹。HTTP 响应类型位于 `front/src/api/types.ts`，不引用后端源码。

回放保持无状态：前端提交目标时间，后端返回该时刻快照。实时 SSE 共享每秒快照，后端不保存浏览器的播放位置或倍速。

## 前端目录与生命周期

- `router/`：Vue Router，`/` 懒加载地图首页；`App.vue` 只承载 `RouterView`。
- `views/MapView.vue`：装配地图与面板，管理选中项、弹窗和播放会话。
- `components/map/`：地图画布、状态面板、时间轴、选中详情和关于弹窗。
- `stores/preferences.ts`：Pinia 语言及省电偏好；语言写入 localStorage，省电偏好保留在当前应用会话。
- `stores/transit.ts`：Pinia 路网、天气、运营资料及各自加载/错误状态；大对象使用 shallowRef。
- `composables/`：播放会话、资料轮询和共用显示格式。SSE、AbortController 和定时器归页面生命周期所有，不进入全局 Store。
- `api/`：HTTP 客户端、交通接口和响应类型。
- `map/`：MapLibre 实例、原生图层与列车几何/插值；不依赖 Pinia。
- `styles/`：基础字体/变量及面板样式，保留现有视觉。

首页不使用 KeepAlive。离开页面时关闭 SSE、取消请求与定时器、停止动画并销毁地图；返回时重新进入实时模式。语言与省电偏好保留，选中项和回放位置重置。

路由使用 History 模式，部署时将前端页面路径回退到 `index.html`，API 和瓦片路径不参与该回退。MapLibre 继续使用 URL hash 保存相机位置。

## 数据与接口

- 业务表位于 `app`，Flyway 元数据位于 `backend_meta`。
- Martin 通过 `app.map_routes` 和 `app.map_stations` 视图发布线路、站点瓦片，底图来自本地 OSM MBTiles。
- Java 默认监听 3002，提供路网、服务日、列车快照/SSE、运营状态及天气接口。
- 旧 `mtr` 表保留供一次性数据导入和显式迁移对照测试使用；正常运行不依赖它。
- 原位置 SQL 仅保留在 Java 测试资源中，作为独立对照样本，不作为 Flyway 迁移执行。

OSM 专用导入器不迁移。GeoJSON 导入、要素与样式编辑属于后续管理能力，尚未实现。
