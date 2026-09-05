# 后端分层与功能迁移计划

> 历史迁移计划：旧 Node 后端已完成 Java 替换并删除。下文保留当时的设计与验收记录，当前实现与启动方式见 [Java 后端说明](../backend/README.md)。

## 状态

阶段 1 至阶段 5 已于 2026-09-05 实施：

- Hono app 已与 TCP 监听和后台任务分离，现有 HTTP/SSE 契约已有独立测试；
- `server/` 已成为独立 TypeScript package，并按 domain、db、integrations、services 和 routes 分层；
- Drizzle schema、初始 migration、现有数据库 baseline 校验和 migration 命令已经建立；
- 空数据库 migration 和旧 importer 数据库 baseline 均已通过隔离数据库验证；
- 当前业务数据库已经校验并登记初始 migration；
- Docker API 镜像已切换到独立 server production dependencies；
- OSM importer 和班次生成已经迁入 `server` command；
- importer 已移除 schema DDL 和整库重建，线路模式变化默认中止；
- importer 与 schedule command 使用同一个 PostgreSQL advisory lock，并在同一事务内重建受影响服务日；
- 香港服务日、网络目录、运营事件、天气缓存和班次存在性已有独立只读 API；
- 列车快照已增加应用实时偏移后的 `previousTime` 和 `nextTime`。

阶段 6 已于 2026-09-05 实施：Vue 时间轴、回放、详情、天气、运营事件与多语言已经接入，旧前端运行链路已删除。阶段 7 为可选范围，尚未实施。

## 目标

- 将 `server/` 建设为动态业务状态、外部 API 和后端计算的唯一入口。
- 将 HTTP、业务编排、纯业务规则、数据库访问和外部 API 访问分开。
- 使用 TypeScript 和 Drizzle 管理 PostgreSQL 普通表、查询、事务和迁移。
- 继续使用 PostGIS 集合式计算列车位置，不把空间计算搬回 Node.js。
- 逐项迁移旧 `src/` 中属于后端的能力，保留明确的前端职责。
- 保持现有列车 HTTP/SSE 协议兼容，分阶段完成迁移。

## 非目标

- 不引入 DI 容器、事件总线、消息队列或后台任务框架。
- 不为每张数据库表创建 Repository、Service 和 DTO 三套包装。
- 不用 ORM 重写适合 PostgreSQL/PostGIS 的复杂 SQL。
- 不在本轮恢复地图、线路、站点和列车视觉样式。
- 不直接迁移旧编辑器，不在缺少认证和权限设计时开放写接口。
- 不处理生产部署、监控和自动化数据更新。

## 重构前的结构问题

### `server/index.mjs` 职责过多

当前入口同时负责：

- 环境变量和业务配置读取；
- PostgreSQL Pool 创建和关闭；
- Hono 中间件和路由；
- 数据库记录到 HTTP DTO 的映射；
- 每秒列车快照调度；
- SSE 客户端和背压等待状态；
- 实时轮询任务的启动、停止和进程信号处理。

模块加载时立即创建 Pool、启动监听并准备后台任务；Pool 通常在首次查询时才建立实际数据库连接。Hono app 仍然无法脱离真实数据库和定时器独立测试。

### `server/realtime.mjs` 混合外部输入、规则和持久化

该模块同时处理：

- 港铁 Next Train API 请求；
- 外部 JSON 解析和香港时间解析；
- 候选计划班次 SQL；
- 实时到站匹配；
- 偏移写入；
- 轮询间隔、生命周期和健康状态。

旧 `src/mtr/RealtimeManager.ts` 仍保留另一套实现。两者的匹配窗口和配对策略已经存在差异，继续双轨维护会产生行为漂移。

### Schema 与数据导入没有分离

`scripts/import-osm-mtr.mjs` 同时执行以下工作：

- 创建 PostGIS extension 和 `mtr` schema；
- 删除并重建线路、站点、班次和实时偏移表；
- 创建索引和 `mtr.train_positions` 函数；
- 导入 OSM 派生数据。

OSM 数据更新不应默认承担 schema migration，也不应隐式删除班次和实时偏移。数据库结构需要独立、版本化的迁移记录。

### Server 依赖没有独立边界

后端目前使用根目录 `package.json`。API 容器因此会安装 Three.js、AMap 等旧前端运行依赖。`front/` 已经是独立包，`server/` 也应拥有独立的依赖、TypeScript 配置和构建命令。

## 设计原则

### 四层依赖方向

```text
routes -> services -> db / integrations
                    -> domain
```

- `routes`：Hono 参数、状态码、响应头、JSON/SSE 序列化。
- `services`：用例编排、缓存、轮询和生命周期。
- `domain`：时间、班次、到站匹配和事件提取等纯函数。
- `db` / `integrations`：PostgreSQL 和外部 HTTP API。
- `index.ts`：读取配置并装配具体依赖，是唯一 composition root。

模块使用小型工厂函数或显式构造参数。当前只有一种数据库和一种外部 API 实现，不提前创建成套接口、抽象基类或 DI 容器。

### 数据交付边界

- Hono 是动态列车、实时运营状态、天气、服务日和管理能力的唯一入口。
- Martin 是底图、线路和站点几何以及制图属性的入口。
- 线路颜色、站名等字段可以为了 MapLibre 渲染继续存在于瓦片中；业务面板使用 `/api/network` 契约，不把矢量瓦片当作通用业务 API。
- 两个出口都从 PostGIS 或同一份经过校验的运营配置派生，避免人工维护两套事实。

### 数据库承担集合式计算

`mtr.train_positions(at_time)` 继续放在 PostgreSQL：

- 当前车次和区段筛选是集合查询；
- 位置插值和朝向依赖 PostGIS；
- Hono 只需要调用一次函数并映射稳定 DTO；
- 浏览器继续负责相邻快照的视觉补间。

### 保持无状态回放

服务端不记录每个浏览器的播放位置、暂停状态或倍速。前端请求目标时刻，后端返回该时刻的列车快照。

### 统一服务日语义

现有实现存在冲突：旧 `src/hktime.ts` 使用 05:30 至次日 01:30 的时间轴，并在 03:00 前归属上一服务日；新 `data/mtr-service.json` 和班次生成器实际采用 05:30 至次日 00:00。

新后端统一采用当前数据链路的规则：

- `serviceDate` 是香港时间 05:30 开始运营的自然日；
- 服务窗口为 `[当日 05:30, 次日 00:00)`；
- 00:00 至 05:30 属于最近服务日的停运空窗，`active=false`，列车查询可以返回空数组；
- 服务日归属切点固定为 05:30，不再保留旧 03:00 特例；
- 命令未指定日期时，使用查询时刻所属的 `serviceDate`，凌晨不会错误生成尚未开始的新服务日；
- `/api/service-day` 明确返回 `active`、服务窗口和数据库实际可回放范围。

## ORM 方案

采用：

- TypeScript；
- `drizzle-orm`；
- `drizzle-kit`；
- 现有 `pg` Pool 和 Drizzle `node-postgres` adapter。

Drizzle 负责：

- 普通表、列、外键和索引的类型声明；
- 普通查询、写入、事务和结果类型；
- 版本化 migration；
- 查询参数和数据库字段到 TypeScript 的静态约束。

原生 SQL 继续负责：

- `CREATE EXTENSION postgis`；
- geometry 类型或 Drizzle 无法清晰表达的空间约束；
- GiST 索引；
- `mtr.train_positions` 函数；
- `ST_LineLocatePoint`、`ST_LineInterpolatePoint`、`ST_Azimuth` 等空间查询。

高效且清晰的 PostgreSQL 特有查询也可以保留为参数化原生 SQL，例如当前基于 `unnest` 的批量实时偏移 upsert。Drizzle 查询和原生 SQL 必须复用同一个 transaction client，事务内不能绕回 Pool 创建第二条独立查询链路。

不选择 Prisma。当前核心计算依赖 PostGIS 和数据库函数，Prisma 会增加 schema 逃生口和额外运行时。Kysely 是可行的类型化查询构建器，但 Drizzle 同时覆盖 ORM schema 和 migration，更符合当前目标。

## 目标目录

```text
server/
  package.json
  tsconfig.json
  drizzle.config.ts
  migrations/

  src/
    index.ts
    app.ts
    config.ts

    domain/
      time.ts
      realtime.ts
      schedule.ts
      types.ts

    db/
      client.ts
      schema.ts
      network-queries.ts
      train-queries.ts
      realtime-queries.ts

    integrations/
      mtr-client.ts
      weather-client.ts

    services/
      train-service.ts
      live-train-service.ts
      realtime-service.ts
      schedule-service.ts
      weather-service.ts

    routes/
      health.ts
      trains.ts
      network.ts
      operations.ts
      weather.ts

    commands/
      import-osm.ts
      generate-schedule.ts
```

目录按能力合并查询和服务，不为单表机械拆分文件。若某个模块在实施时只有几行代码，可以继续与相邻模块合并。

该目录是规模上限。实施时只创建当前阶段实际使用的模块，不预先生成空的 weather、operations 或 schedule 层。

## 运行时组件

### `createApp(dependencies)`

`app.ts` 创建并返回 Hono app，不监听端口、不创建 Pool、不启动定时器。HTTP 测试可以注入替代 service，并使用 Hono 自带 request API 验证协议。

### `train-service`

- 查询指定时刻的列车位置；
- 映射数据库记录到稳定 API DTO；
- 提供当前服务日元数据和列车详情字段。

### `live-train-service`

- 每秒只生成一次共享全网快照；
- 管理 SSE subscriber；
- 新 subscriber 立即收到最近快照；
- 最后一个订阅者断开时仍可保持共享快照循环，避免为每个连接创建数据库查询；
- 提供显式 `start()` 和 `stop()`。

首版继续沿用当前内存 subscriber 集合，不引入 Redis 或 pub/sub。

### `realtime-service`

- 按配置顺序轮询每条港铁线路的监测站；
- 调用纯函数规范化响应、提取事件和匹配班次；
- 通过数据库查询模块加载候选班次并写入实时偏移；
- 暴露最近轮询、健康状态和线路事件；
- 提供显式 `start()` 和 `stop()`。

线路事件先保存在内存。服务重启后的下一轮轮询会恢复状态，当前没有事件历史或多实例一致性需求。

### `weather-service`

- 统一请求香港天文台 API；
- 按语言缓存结果十分钟；
- 保留最后一次成功结果和失败状态；
- 将天气原始数据转换成稳定 DTO。

天气图标到 emoji 或图形资源的映射属于前端展示逻辑。

### `schedule-service`

- 根据服务日、线路运营参数、班距和 route pattern 生成班次；
- 使用事务替换指定服务日数据；
- 保留命令行入口；
- 提供当前和下一服务日是否存在的只读检查。

2026-09-05 修正：为恢复旧前端自动准备当前服务日班次的能力，API 启动时先补齐当前和下一服务日，再开启快照与实时匹配。独立的 schedule-preparation service 每分钟检查一次，事务内取得现有共享锁后只生成缺失日期，已有班次与实时偏移保持不变。首次准备失败中止启动；运行期间失败记录错误并在下一轮重试。手动 schedule command 仍明确重建指定日期。

## API 规划

### 保持兼容

- `GET /health`
- `GET /api/trains?at=<ISO-8601>`
- `GET /api/trains/live`

现有字段和 SSE 默认 message 事件在结构重构阶段保持不变。

### 新增只读接口

#### `GET /api/network`

返回前端交互需要的小型业务目录：

- 线路 id、API code、双语名称、颜色和事件锚点站；
- 站点 code、双语名称、换乘状态和所属线路；
- 服务参数中需要向前端公开的部分。

线路和站点几何继续由 Martin 瓦片提供，不在该接口重复返回。

`data/mtr-service.json` 已加入 `nameEn`、`nameZh`、`colour` 和 `incidentAnchorStation`。`monitorStation` 只用于实时数据匹配。接口将这份运营配置与数据库站点目录组合，不返回几何。

#### `GET /api/service-day?at=<ISO-8601>`

返回指定时刻所属服务日及时间轴范围：

- `serviceDate`；
- `startsAt`；
- `endsAt`；
- `active`；
- 数据库实际可用的回放范围；
- 当前和下一服务日的班次存在性。

#### `GET /api/operations`

返回：

- 实时 API 健康状态；
- 最近轮询时间；
- 各线路当前事件；
- 最近更新班次数。

该状态更新频率较低，不并入每秒列车快照。

#### `GET /api/weather?lang=en|zh`

返回 `temperatureC`、`humidityPercent`、天气图标编号、警告、官方更新时间和缓存时间。按 `en|zh` 分别缓存十分钟；刷新失败且存在旧值时返回 `stale=true` 和错误信息。

### 列车快照补充字段

为恢复列车详情弹窗，列车 DTO 增加：

- `previousTime`；
- `nextTime`。

当前快照已经包含线路、运行状态、上一站、下一站、终点站和实时偏移。几十至百余辆列车增加两个时间字段的成本很小，无需为每次点击单独请求列车详情。

两个时间字段使用 ISO 8601 字符串并包含已经应用的实时偏移：

- 运行中，`previousTime` 是上一站实际/修正发车时刻，`nextTime` 是下一站实际/修正到站时刻；
- 停站中，`previousTime` 是当前站实际/修正到站时刻，`nextTime` 是下一站实际/修正到站时刻；
- 没有下一站时 `nextStation` 和 `nextTime` 均为 `null`。

首轮不增加当前没有消费者且数据库未直接存储的 `direction`。PostgreSQL `RETURNS TABLE` 的字段变化通过事务内 drop/recreate function migration 完成。

## `src/` 功能迁移矩阵

| 旧模块 | 当前能力 | 处理方式 | 目标位置 |
| --- | --- | --- | --- |
| `src/hk_mtr_data.ts` | 线路运营参数、班距、支线选择、班次生成、线路/站点显示目录、初始中心、AMap 坐标转换 | 迁移运营规则和缺失的线路目录；站点目录由 PostGIS 提供；废弃 AMap/GCJ02 和旧初始中心 | `domain/schedule.ts`、`services/schedule-service.ts`、network config/API |
| `src/hktime.ts` | 香港时间、服务日、地图昼夜主题 | 迁移时间解析和服务日；地图主题留在前端 | `domain/time.ts` |
| `src/mtr/api.ts` | 港铁 API、响应规范化、事件提取、天气 API、天气图标 | 迁移外部请求、规范化、事件和天气数据；图标映射留前端 | `integrations/*`、`domain/realtime.ts`、`weather-service.ts` |
| `src/mtr/RealtimeManager.ts` | 轮询、匹配、偏移平滑、事件、健康状态 | 以现有 server 匹配为基线，补齐事件；明确废弃旧六秒时间偏移平滑，新前端的一秒空间补间只负责快照视觉连续性 | `realtime-service.ts`、`domain/realtime.ts` |
| `src/FleetManager.ts` | 活跃车次筛选、位置更新、对象池、拾取和选中 | 筛选和位置已由 PostGIS 替代；对象池、拾取和选中属于前端渲染 | 不迁移类本身 |
| `src/Train.ts` | 位置插值、运行/停站状态、停站详情、Three.js Mesh | 后端补齐详情字段；Mesh、颜色、选中和视觉效果留前端 | SQL function、train DTO、Vue/MapLibre |
| `src/types/RailData.ts` | 旧轨道和班次内存模型 | 由 Drizzle schema、domain types 和 API DTO 取代 | `db/schema.ts`、`domain/types.ts` |
| `src/data.ts` | 默认数据加载和 localStorage 持久化 | 废弃；PostGIS 成为业务数据源 | 无直接迁移 |
| `src/main.ts` | AMap/Three.js 初始化、旧组件装配、地图交互和渲染循环 | 不迁移入口；动态数据装配由 server 承担，地图交互与渲染由 Vue/MapLibre 重写 | `server/src/index.ts`、`front/` |
| `src/config.ts` | AMap key 和 security code | 随 AMap 链路废弃 | 无直接迁移 |
| `src/utils.ts` | Three.js/AMap 几何与插值工具 | 列车位置已由 PostGIS 替代；仍有价值的纯制图算法按需在数据预处理或前端重写 | `scripts/`、PostGIS/Martin 或 `front/` |
| `src/mtr/stations.generated.ts` | 旧前端静态 OSM 站点坐标和名称 | PostGIS 站点表与 network API 取代 | 删除旧生成链路 |
| `src/mtr/tracks.generated.ts` | AMap/GCJ02 线路和站点生成数据 | OSM/PostGIS/Martin 取代 | 删除旧生成链路 |
| `src/PlaybackController.ts` | 播放、暂停、倍速、跳转和直播切换 | 保留为浏览器本地状态；服务日范围来自 API | `front/` |
| `src/trackLayout.ts` | 重叠线路错位、站点聚合和站点外观尺寸 | 归入制图预处理或 MapLibre style；不进入 Hono | `scripts/`、PostGIS/Martin 或 `front/` |
| `src/Editor.ts` | 浏览器直接编辑轨道和班次并写 localStorage | 不直接迁移；需要保留时重新设计为管理功能 | 后续独立范围 |
| `src/ui/*` | 时间轴、天气、线路事件、列车/站点弹窗 | 数据源迁后端，状态和展示迁 Vue | `front/` |
| `src/i18n.ts` | 语言检测、文案和用户偏好 | 保留前端 | `front/` |

## 编辑器边界

旧编辑器修改的是浏览器内存和 localStorage。迁到后端后，操作会变成共享数据库写入，风险和语义都发生变化。

如确认需要保留，应另行定义：

- 编辑 OSM 派生数据还是独立 override；
- 编辑运营规则还是某天的具体班次；
- 并发修改和审计方式；
- 管理员认证与授权；
- 修改后重新生成瓦片或班次的流程。

这些问题确定前，不创建 `/api/admin` 写接口，也不把旧 Editor 代码搬入 server。

## 数据库迁移与导入边界

### Migration

版本化 migration 负责：

- extension、schema、表、约束和索引；
- 数据库函数；
- 可回顾的 schema 演进。

checked-in migrations 是唯一可执行 DDL 来源。`db/schema.ts` 提供应用层类型模型，不能在运行时自动修改 schema。

### 既有数据库 baseline

首个 migration 必须同时覆盖两条路径：

- 空数据库：正常应用完整 migration，建立 extension、schema、表、索引和函数；
- 已有数据库：baseline 命令只读校验 extension、表、列、约束、索引和 function signature，与首个 migration 完全一致后登记 migration journal，不重复执行 DDL。

校验不一致时立即中止，不自动修补、不登记 baseline。需要重建时，使用另一条显式的备份和重建流程，不能把重建隐藏在正常 migrate 命令内。baseline、Drizzle schema 和手写 PostGIS migration 的预期结构必须在同一测试中核对。

### OSM importer

OSM importer 负责：

- 验证生成的 GeoJSON/JSON；
- 在事务内导入线路、站点和 route stops；
- 输出导入计数和几何验证结果。

它不再创建或删除 schema。网络数据替换涉及班次外键时，命令必须显式报告并处理受影响的服务日，不能隐式删除无关业务数据。

Importer 在写入前比较每个 pattern 的 stop sequence、station、distance 和定位比例。检测到会改变既有 `train_legs` 语义的 pattern 时，默认中止并报告受影响 pattern 和服务日。只有显式确认后，命令才在同一事务和同一 advisory lock 内替换网络数据并重新生成受影响服务日。

### Schedule command

班次命令负责：

- 读取数据库 route stops 和运营参数；
- 生成指定服务日的 runs 和 legs；
- 在事务内只替换指定服务日；
- 输出生成计数。

班次命令在事务开始后取得与 importer 相同的 PostgreSQL advisory lock，再读取 route stops 并写入 runs/legs，消除“事务外读取旧 stops、事务内写入新班次”的并发窗口。

`data/mtr-service.json` 在初期继续作为经过类型校验的 seed/config 输入，避免结构重构同时引入运营配置管理。需要管理 API 时，再将可编辑运营配置提升为数据库表。

## 实施阶段

### 阶段 1：固定现有行为

- 先从当前入口提取最小 `createApp(dependencies)` seam，不改变路由实现和进程启动方式。
- 为 `GET /health`、`GET /api/trains` 和 SSE 首帧增加协议测试。
- 为香港时间解析、实时匹配和 DTO 映射增加纯函数测试。
- 明确新 server 匹配实现是唯一基线，旧前端测试只作为缺失能力参考。

验收：测试可以在不监听 TCP 端口的情况下调用 Hono app；现有前端不需要修改。

协议测试至少固定：

- `/health` 的数据库探测和 `realtime` 字段；
- `/api/trains` 缺少 `at` 时使用共享快照；
- 400、404、500 和 CORS 行为；
- SSE 的 `retry: 3000`、默认 message 事件和已有快照立即发送。

实时规则测试至少覆盖 `valid=N`、空 destination、支线终点、时间窗口边界、同距 tie、延误正负号和停止过程中仍在途的轮询。

### 阶段 2：TypeScript 与分层骨架

- 创建独立 `server/package.json` 和 `tsconfig.json`。
- 将 `index.mjs` 拆成 composition root、`createApp`、routes、services 和 lifecycle。
- 将 `realtime.mjs` 拆成纯规则、外部 client、数据库查询和轮询 service。
- 保持现有 URL、字段、错误状态码和轮询行为。
- 同步修改根目录 scripts/lockfile、server lockfile、`Dockerfile.api` 和 compose 启动命令。

验收：本地命令和容器均可启动新 server；现有接口和浏览器列车运动无行为变化；后端 production dependencies 不含旧地图依赖。

### 阶段 3：Drizzle 与版本化 migration

- 建立 Drizzle schema 和 node-postgres client。
- 将表、约束、索引和 SQL function 移入 migration。
- 将运行期 SQL 集中到按能力划分的 `db/*-queries.ts`。
- 保留 `mtr.train_positions` 原生 SQL实现。
- 为已有数据库执行只读结构校验并登记 baseline；结构不符时验证命令无写入。

验收：空数据库可以只通过 migration 建立 schema；现有数据库可以安全 baseline；两条路径得到相同结构；API 可以通过 Drizzle client 查询现有数据。

### 阶段 4：拆分命令与数据导入

已于 2026-09-05 完成。

- 将班次生成移动到 server command 和 schedule service。
- 将数据库导入命令移动到 server command。
- 保留 OSM XML 到规范化 GeoJSON 的构建脚本在 `scripts/`。
- 移除 importer 中的 DDL 和整库删除行为。
- 为 importer 和 schedule command 增加共享 advisory lock、pattern 变化检测和默认中止行为。

验收：重新导入 OSM 与生成单个服务日是两项明确、独立的操作；并发执行不会产生新旧 route stops 混合的班次。

### 阶段 5：迁移缺失业务能力

已于 2026-09-05 完成。

按依赖顺序迁移：

1. 香港时间与服务日；
2. 网络业务目录；
3. 线路事件和运营健康状态；
4. 天气及缓存；
5. 列车详情时间字段；
6. 当前和下一服务日班次存在性的只读检查。

验收：新前端不需要从 `src/` 导入任何业务事实或直接访问第三方交通、天气 API。

### 阶段 6：前端功能对等与旧代码清理

已于 2026-09-05 完成。范围及验证见 `docs/frontend-migration.md`。

- Vue 实现时间轴和回放请求；
- Vue 实现列车详情、线路事件、天气和多语言；
- MapLibre 保留地图、图层、选中和视觉补间；
- 功能对等后删除旧 AMap、Three.js 和前端业务计算链路。

验收：默认构建不依赖旧 `src/`；旧应用仅在明确需要时保留为历史参考。

### 阶段 7：可选管理能力

仅在确认编辑需求和认证方案后实施管理 API、覆盖数据模型和管理前端。该阶段不阻塞只读地图和实时列车功能迁移。

## 建议的首轮编码范围

首轮只实施阶段 1 至阶段 3：

- 固定当前协议；
- 建立可测试的 TypeScript 分层；
- 接入 Drizzle 和版本化 migration；
- 保持地图和实时列车行为不变。

天气、事件、新接口、编辑器和前端功能恢复留在后续阶段。这样可以先建立稳定承载结构，再迁移新能力，减少结构调整与功能变化同时发生造成的定位成本。

## 首轮完成条件

- `server/src/index.ts` 只负责配置、装配、监听和关闭。
- `createApp()` 可在无 TCP 监听条件下测试。
- routes 不包含 SQL 和轮询循环。
- domain 不导入 Hono、Drizzle、`pg` 或外部 HTTP client。
- 运行时 server SQL 只存在于 migration 和 `db/` 查询模块；旧 importer DDL 在阶段 4 删除。
- 空数据库可运行 migration；现有 PostGIS 位置函数行为保持一致。
- `GET /health`、`GET /api/trains`、`GET /api/trains/live` 保持兼容。
- 新前端仍能显示全部站点、线路和移动列车。
- server production dependencies 不包含 AMap、Three.js、Webpack 或 Vue。
