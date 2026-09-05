# 列车位置后端化

> 历史设计记录：旧 Node 后端已删除，本文的 Node/Drizzle 命令不再适用。当前实现为 Java 25 + Spring Boot，启动、导入与验证以 [Java 后端说明](../backend/README.md) 为准。

## 结论

PostGIS 保存业务数据并计算计划回放位置；后端 TypeScript 维护实时身份关联和连续运行轨迹，Hono 负责接口发布。浏览器按服务端时间消费短期沿线轨迹。Martin 继续提供静态矢量瓦片，不参与动态列车计算。实时机制于 2026-09-05 调整，详见 [实时连续推演](realtime-motion.md)。

新前端位于独立的 `front/`，使用 Vue 3 和 MapLibre。阶段 6 已删除旧 AMap、Three.js 和前端车队代码，历史实现可从 Git 历史查看。

## 已实现

- `mtr.route_stops` 保存方向线路上的停站顺序、里程和插值比例。
- `mtr.train_runs`、`mtr.train_legs` 保存服务日班次和站间时刻。
- `mtr.realtime_offsets` 是保留的旧表，实时与回放路径均不再读取它。
- `mtr.motion_checkpoint` 保存单实例实时推演恢复检查点。
- `mtr.train_positions(at_time)` 返回指定时刻的全网计划位置、朝向和运行状态。
- Versioned migration 管理数据库结构；OSM 导入和服务日班次生成由独立 server command 执行。
- Train API 每轮结束后间隔 30 秒查询十条线路各一个监测站，延续已有 ETA 关联，拒绝过期、歧义和不可达校正。
- `GET /api/trains?at=<ISO-8601 time>` 提供初始化和回放快照。
- `GET /api/trains/live` 使用 SSE 每秒广播一次共享的全网快照。
- `GET /api/network` 返回线路双语目录、颜色、事件锚点和站点业务目录。
- `GET /api/service-day` 返回香港服务日、运营窗口、回放范围和当前/下一服务日班次状态。
- `GET /api/operations` 返回实时 API 健康状态、更新数量和各线路当前事件。
- `GET /api/weather?lang=en|zh` 返回后端缓存的香港天文台天气。
- MapLibre 使用 GeoJSON source 接收列车，浏览器以最多 30 FPS 播放实时快照附带的 3 秒轨迹；计划回放保留快照补间。

尚无观测的列车按计划推演；已校准列车按原连续轨迹处理观测过期，不回退到计划位置。监测站之后明确标记为行程预测；单条线路请求失败不会中断其他线路或列车快照。

## 职责边界

- PostGIS：线路、停站顺序、班次、恢复检查点及计划位置查询。
- TypeScript 推演模块：观测关联、连续轨迹及位置计算；Hono：对外 API 与快照广播。
- Martin：OSM 底图、MTR 线路和站点矢量瓦片。
- 浏览器：Vue 交互状态、MapLibre 图层、SSE 消费和视觉补间。

首版不引入 WebSocket、Redis、消息队列或额外地图抽象层。Deck.gl 留到需要模型或复杂三维图层时再接入。

## 位置计算

PostgreSQL 函数：

```sql
mtr.train_positions(at_time timestamptz)
```

每辆列车先确定当前站间区段，再计算：

```text
时间进度 = (查询时间 - 计划发车时间) / (计划到达时间 - 计划发车时间)
沿线比例 = 起点比例 + 时间进度 × (终点比例 - 起点比例)
```

`ST_LineInterpolatePoint` 得到坐标，当前位置附近的第二个插值点用于 `ST_Azimuth` 朝向计算。停站期间固定在站点位置。

## 本地运行

复制环境变量模板并设置本地数据库密码：

```powershell
Copy-Item .env.example .env
```

首次初始化或更新 OSM 数据后执行：

本地开发启动 `npm run server` 会自动读取仓库根目录 `.env` 的 `DATABASE_URL`。该连接使用宿主机地址 `127.0.0.1:15432`，用户名、密码和数据库须与 `POSTGRES_*` 配置一致。Compose 容器继续使用其显式配置的内部数据库连接。以下数据库维护命令仍通过当前终端的环境变量传入连接。

```powershell
npm run server:install
docker compose up -d --wait postgres
$env:DATABASE_URL = "postgresql://mtr:your-password@127.0.0.1:15432/mtr"
npm run db:migrate
npm run data:osm:import
npm run data:mtr:schedule
npm run data:osm:test-positions
Remove-Item Env:DATABASE_URL
```

Migration 是数据库结构的唯一写入路径。首次初始化空数据库时先运行 migrate，再导入 OSM 网络并生成服务日班次。阶段 3 以前创建的既有数据库先运行 `npm run db:baseline -- --check`，确认结构一致后依次执行 baseline 和 migrate。

OSM importer 只替换线路、站点和 route stops。同一份数据可以安全重复导入。若新数据会改变已有班次引用的站序、站点、里程或线路定位比例，命令默认中止并列出受影响的 pattern 和服务日。确认变更后运行：

```powershell
npm run data:osm:import -- --allow-pattern-changes
```

确认模式会在同一个事务内替换网络并重新生成全部受影响服务日；这些服务日原有的实时偏移会随车次重建而清除。Importer 和 schedule command 共用 advisory lock，避免并发时读取到混合的新旧 route stops。

启动 Martin、Train API 和前端：

```powershell
docker compose up -d --build --wait martin train-api
npm run front:install
npm start
```

如需修改前端服务地址，将 `front/.env.example` 复制为 `front/.env`。

默认地址：

- 前端：`http://127.0.0.1:8080`
- Martin：`http://127.0.0.1:8081`
- Train API：`http://127.0.0.1:3001`

`REALTIME_ENABLED=false` 可关闭外部 API 轮询，回到纯计划时刻。指定服务日可运行 `npm run data:mtr:schedule -- 2026-09-04`。

列车快照中的 `previousTime` 和 `nextTime` 是 ISO 8601 时刻；回放使用计划时间，实时使用推演时间。天气按语言缓存十分钟；上游刷新失败时，只要已有成功值，接口会返回该值并标记 `stale` 和 `error`。

## 验收结果

- OSM 导入：24 个方向线路、276 个停站锚点、98 个站点。
- 2026-09-04 服务日：3473 个车次、41035 个站间区段。
- 08:00 香港时间：112 辆运行或停站列车，全网快照查询约 15 ms。
- 数据库集成测试覆盖运行、停站、朝向和实时偏移。
- 浏览器端验证无控制台异常；相邻快照间同一运行列车坐标持续变化。

## 后续工作

- API 已在启动时准备当前和下一服务日，并每分钟补齐缺失日期。已有日期不会重建；启动准备失败会明确报错，运行期间失败会重试。无需每天手动生成班次。手动 `data:mtr:schedule` 仍用于明确重建指定日期。
- 将圆点占位样式升级为带朝向的伪 3D 列车；列车详情交互已经恢复。
