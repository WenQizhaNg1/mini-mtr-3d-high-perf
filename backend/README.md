# Java 后端

Java 25、Spring Boot 4.0.8、Spring MVC、JDBC/HikariCP、jOOQ、Flyway、JTS 1.20.0。除 JTS 外，依赖版本由 Spring Boot 管理。

已建立 `app` 业务 schema，提供健康检查、路网目录、服务日查询、模拟计划生成、计划回放、实时 ETA 校正、连续运动推演、SSE、运营状态和天气。前端默认连接 Java 3002，Martin 从新表视图发布业务瓦片。OSM 专用导入器不再迁移，后续通过 GeoJSON 导入与要素编辑维护空间数据。

## 目录

```text
src/main/java/dev/minimtr/
  api/           HTTP 参数、响应和异常处理
  config/        Spring 配置、运行参数、一次性导入入口
  model/
    entity/      数据库查询模型
    dto/         服务间传递的数据
    vo/          API 响应
  repo/          jOOQ/JDBC 查询和批量导入 SQL
  service/       业务规则与事务边界
```

不建立通用 BaseService/BaseRepo，也不为没有行为的类配接口。SQL 集中在 repo 和资源文件中。共享 `data/mtr-service.json` 在构建时打入 JAR，运行不依赖 Node 或源码路径。

交通接口合并在 `TransitController`，健康检查保留独立 Controller。

## 启动

本机需要 JDK 25 和 Maven 3.6.3+，以及已经启动的 PostgreSQL/PostGIS。

从仓库根目录运行：

```powershell
npm run server:java
```

或进入本目录运行：

```powershell
mvn spring-boot:run
```

自动读取根目录 `.env` 的 `POSTGRES_*`；不打印密码，不使用 Node 的 `DATABASE_URL` 作为 JDBC URL。默认数据库地址为 `127.0.0.1:15432`。特殊连接可通过 `JDBC_DATABASE_URL` 覆盖，用户名/密码仍来自 `POSTGRES_USER` / `POSTGRES_PASSWORD`。

默认监听 `127.0.0.1:3002`，通过 `JAVA_API_HOST` / `JAVA_API_PORT` 覆盖。

根目录 `npm run server` 也启动 Java；前端用 `npm start`。旧 Node 后端及 Docker 构建入口已删除，Compose 仅管理 PostgreSQL 和 Martin。旧数据库表保留供显式迁移对照测试使用，不参与正常运行。

`GET http://127.0.0.1:3002/health` 通过 jOOQ 执行数据库查询，成功返回 `{"status":"ok","database":"up"}`；运行中数据库不可用返回 503。

## 数据库边界

Flyway 历史表位于 `backend_meta`；V1 检查 PostGIS，V2 建立 `app` 业务表，V3 注册实际使用的空间类别，V4 增加 `line_station`。迁移文件在 `src/main/resources/db/migration/`，已执行的版本不再修改。

现有 `mtr`、Drizzle 元数据保留。V5 创建 `app.map_routes` / `app.map_stations` 只读视图，Martin 保留 `mtr-routes` / `mtr-stations` 数据源及原图层属性；几何与业务属性全部来自 `app`。先启动 Java 应用 Flyway，再运行 `docker compose restart martin` 加载配置。站级面几何以内部点发布到当前站点图层。`spatial_object` 固定 WGS84，注册表 `ontology` 无业务外键。`line_station` 描述线路归属，`route_stop` 描述具体运行方案停站，两者独立。

当前使用 jOOQ 参数化 SQL，尚未启用代码生成。路径通过 `ST_AsBinary` 读取 WKB，交给 JTS 解析，不依赖 jOOQ 商业版的 geometry binding。PostGIS 负责存储和校验，Java 负责回放位置插值。

## 一次性导入旧数据

本地这一轮已完成导入，无需再次执行。另一个含有完整 `mtr` 旧表、但 `app` 业务表为空的数据库，可在仓库根目录执行：

```powershell
mvn -f backend/pom.xml package
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar --spring.main.web-application-type=none --migration.import-legacy=true
```

命令在一致性快照事务中复制路网、线路归属和已有计划，验证数量、几何、距离和每个区间的到发时刻，失败整体回滚。目标已有业务数据时拒绝导入，不覆盖、不清空。它是一次性迁移入口，不是持续同步工具。

`train_runs` → `trip`，`train_legs` → `trip_stop`。旧计划由间隔/速度合成，标为 `simulated`；保留来源班次键与绝对时刻。未知站台不创建，旧 motion checkpoint 不复制，样式表暂不填充。

## 已迁移 API

- `GET /health`
- `GET /api/network`：从新表读取线路、站点与换乘信息，保留原 JSON 字段。
- `GET /api/service-day?at=2026-09-05T00:00:00Z`：`at` 可省略，查询新计划表；无效时间返回 400。
- `GET /api/trains?at=2026-09-05T00:00:00Z`：计划回放快照，保留原 JSON 字段。无效时间返回 400；没有运行班次时返回空数组。
- `GET /api/trains`：最新实时推演快照；运行状态未准备好或数据库所有权失效时返回 503。
- `GET /api/trains/live`：SSE，每秒推送快照，包含未来最多 3 秒的带时间戳路径拐点。
- `GET /api/operations`：实时轮询状态和线路运营提示。
- `GET /api/weather?lang=zh`：当前天文台天气，支持 zh/en，默认 zh。分语言缓存 10 分钟；刷新失败保留旧值、标记 stale，1 分钟后重试；没有旧值时返回 503。上游请求超时 10 秒。

当前 API 保持 MTR 兼容范围，尚未开放多运营方查询。计划回放不混入实时延误，`delaySeconds` 为 0。实时结果带 `estimate`（planned / observed / predicted / stale / conflict）和 `motion`；它是基于到站信息的推演，并非列车 GPS 定位。前端响应类型定义在 `front/src/contracts.ts`，不再引用旧 Node 源码；公开只读 API 支持跨域 GET（不携带凭据），后续编辑接口需要独立的授权与跨域策略。

## 计划生成与回放

正常启动时准备当前及下一服务日的模拟班次，之后每分钟检查一次。按服务日事务加锁，已有 MTR 计划则跳过，不覆盖导入数据；生成失败整体回滚。启动准备失败会中止启动，运行期间的失败记录原因并在下一轮重试。一次性旧数据导入命令不启动该任务。

生成器沿用 `mtr-service.json` 的首末班、分时段间隔、支线比例、速度和停站时间；使用 `route_stop.distance_m` 的米制距离估算到发时刻，写入 `trip / trip_stop`，来源为 `mtr-simulator`，计划类型为 `simulated`。首末站不适用时刻保持 NULL。

只读检查或尚未导入路网时，可用 `--schedule.enabled=false --live.enabled=false` 禁用计划准备和实时运行。默认启动要求已存在配置所需的路网。

回放查询只从数据库筛选运行/停站窗口，不新增位置计算存储函数。JTS 在缓存的路径上按 fraction 插值，保持旧 PostGIS 的 WGS84 平面 fraction 口径；该长度不是米。车头方向保持旧地图的 Web Mercator 口径。终点到达即退出回放，停站保留下一站的到达时刻。

路径缓存按空间对象 ID 保存在进程内，当前外部修改路径后需重启 Java 服务；未来要素编辑入口必须同时处理缓存失效、停站定位和计划影响。

## 实时运行

启动顺序是准备计划、读取路径/班次、恢复检查点、启动推演及港铁轮询。推演每秒生成一次共享快照；每分钟刷新附近班次，每 10 秒及接受轮询数据后保存检查点。几何从 WKB 读取，将平面 fraction 转成沿线米制距离后进行速度约束；不在 SQL 中重排轨迹，不按车辆逐帧查询数据库。

ETA 匹配沿用方向、路径、终点、班次顺序和歧义限制。校正只修改未来轨迹，保持当前位置、历史和停站时长。超速/无法匹配的观测不强行应用。远期观测超过 180 秒仍未到达目标时冻结；新观测从冻结位置恢复。经过唯一监测站后沿已校正轨迹继续，并明确标记 predicted。

每轮依次请求各监测站，间隔 220ms，完成后 30 秒发起下一轮；连接超时 5 秒、请求超时 10 秒。`--realtime.enabled=false`（或 `.env` 中 `REALTIME_ENABLED=false`）只关闭第三方轮询，仍提供 planned 推演和 SSE。DRL 的“成功但无预测、时间为 -”响应不构成异常；有预测却没有有效源时间仍报错。

Java 独占 `app:live-motion` 会话锁，检查点写入使用同一数据库连接；同一库不能启动两个 Java 实时实例。锁和 `app.motion_checkpoint` 与旧 Node 的 `mtr` 状态独立。检查点格式版本为 2，恢复时校验有效期、路径 WKB 指纹及原计划；旧 Node 检查点不直接复用。数据库会话失效后不再发布旧快照，需要恢复数据库后重启后端。

SSE 每个订阅只保留最新一帧，socket 写入使用独立虚拟线程，慢客户端不拖住全局推演。连接每 60 秒重建，服务端提示 3 秒重试；浏览器 EventSource 自动重连。关闭服务先停止轮询/广播并关闭订阅，再保存轨迹和释放锁。

港铁和天文台请求共用后端 HTTP 客户端。默认直连；需要代理时，在根目录 `.env` 中添加：

```powershell
OUTBOUND_PROXY_URL=http://127.0.0.1:1083
```

然后重启 `npm run server`。留空或删除该项即可恢复直连。仅支持无认证的 HTTP 代理（HTTPS 上游通过 CONNECT 隧道访问）；格式必须为 `http://host:port`，不支持 SOCKS。配置仅作用于 Java 的港铁/天气请求，不修改系统代理，不影响数据库、Martin 或浏览器。代理不可用时保留请求错误，不自动绕过代理；默认直连也不读取 JVM 全局代理参数。

## 验证

根目录 `npm test` 运行前端与 Java 单元测试；`npm run server:build` 构建 Java。旧位置 SQL 已作为只读对照样本移到 `src/test/resources/legacy/`，测试构建不再引用 `server/`。

```powershell
mvn test
mvn -Pintegration verify
mvn -Pintegration '-Dlegacy.verify=true' verify
```

以上测试命令在 `backend/` 执行。普通测试无需数据库，HTTP 客户端测试使用本地模拟服务。`integration` 会运行 Flyway 建表，验证数据库约束和 HTTP/SSE；SSE 测试使用可控推演快照，不请求真实港铁接口。测试默认关闭后台计划准备和真实实时运行。`legacy.verify` 额外逐站对照旧计划时刻、用原 PostGIS SQL（关闭实时偏移）验证 JTS 回放位置，验证计划事务、数据库单实例锁及检查点往返；测试数据和检查点写入均回滚，不适用于没有旧表及路网的数据库。运行独占锁测试前请停止该库的 Java 实时实例。

打包后从仓库根目录运行：

```powershell
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar
```
