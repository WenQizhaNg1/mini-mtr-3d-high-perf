# 新架构边界

## 目录

- `front/`：新的 Vue 3 + MapLibre 应用，只消费 Martin 瓦片和 Hono API。
- `server/`：业务数据入口，负责班次、实时数据、回放计算和状态聚合。
- `data/`、`scripts/`：OSM 原始数据、归一化产物和无数据库的数据构建工具。
- 原根目录 `src/` 和 `legacy:*` 命令已删除；历史实现由 Git 历史保留。

新前端不得引用 `src/` 中的 `hk_mtr_data`、`FleetManager`、`RealtimeManager` 或编辑器数据。需要共享的业务事实应由 `server/` 提供稳定接口。

## 职责

后端负责：

- 服务日、计划班次和指定时刻的列车状态。
- 港铁实时数据匹配、线路事件和数据健康状态。
- TypeScript 持续维护实时列车身份和沿线轨迹；PostGIS 承担静态空间数据和计划回放查询。
- 天气等外部数据的统一请求与缓存。
- 需要持久化的管理操作。

前端负责：

- 地图和图层渲染。
- 时间轴、选中项、弹窗、地图跟随等本地交互状态。
- 实时沿线短轨迹的定时播放与计划回放快照的视觉补间。
- 界面文案、语言和展示格式。

回放保持无状态：前端提交目标时间，后端返回该时刻快照。后端不保存每个浏览器的播放位置或倍速。

## 当前迁移状态

- Vue 3、MapLibre 基础入口已建立。
- OSM 底图、MTR 线路、站点和实时列车已迁入 `front/`。
- Train API 已迁移到独立的 TypeScript + Hono package，现有 HTTP 和 SSE 协议保持不变。
- 后端按 domain、db、integrations、services 和 routes 分层；Drizzle 管理普通 schema 和 migration，PostGIS 计算继续使用原生 SQL。
- OSM 导入和班次生成已迁入 `server` command，共享事务级数据库锁；数据构建脚本不再执行 schema DDL。
- 服务日、网络目录、线路事件、运营健康和天气已经由 Hono 提供只读 API。
- API 在启动时准备当前/下一服务日，运行期间每分钟检查补缺；复用生成器与共享数据库锁，不覆盖已有班次或实时偏移。
- 列车快照已经包含详情面板需要的前后站修正时刻。
- 实时快照增加 `motion` 和 `estimate`；检查点支持单实例恢复，实时与计划回放均不再读取旧 `realtime_offsets`。详见 [实时连续推演](realtime-motion.md)。
- Vue 时间轴、回放、列车/站点详情、线路事件、天气、多语言和省电显示已完成。
- `front/src/playback.ts` 管理 SSE 与无状态回放；`map.ts` 只接收快照并渲染。前端通过 type-only import 复用 server DTO，不引入后端运行时代码。
- 原编辑器不直接迁移；如需保留，应改造成调用后端管理 API 的独立功能。
