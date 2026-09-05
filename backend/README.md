# Java 后端

Java 25、Spring Boot 4、Spring MVC、jOOQ、Flyway、PostGIS 与 JTS。静态空间数据、模拟计划和实时观测分别处理；车辆位置通过统一 MotionFrame 输出。

## 启动

在根目录运行 `mvn -f backend/pom.xml spring-boot:run`。默认监听 127.0.0.1:3002，读取根目录 .env 中的 POSTGRES_* 和 WORKBENCH_TOKEN。JDBC_DATABASE_URL 可覆盖连接地址，JAVA_API_PORT 可覆盖端口。

先启动 PostgreSQL/PostGIS，再启动 Java 应用 V1，最后启动 Martin：

```powershell
docker compose up -d postgres
mvn -f backend/pom.xml spring-boot:run
# Java 就绪后，在另一个终端执行：
docker compose up -d martin
node --env-file=.env scripts/import-mtr.mjs
npm --prefix front run dev
```

空库可正常启动。唯一迁移是 `src/main/resources/db/migration/V1__application.sql`，初始化 16 张业务表、2 个视图、2 个瓦片函数，以及本体和日夜样式。运营方及计划通过接入 API 导入。已有旧版数据库需要先清空重建；完整定义见 [数据库设计](../docs/database-design.md)。

MARTIN_PUBLIC_URL 默认 http://127.0.0.1:8081。底图、字体和精灵资源位于 map/。外部数据请求先直连；网络需要代理时，可仅在本次启动参数中指定 `--outbound.proxy-url=http://127.0.0.1:1083`。

## 接口

| 接口 | 用途 |
| --- | --- |
| GET /health | 数据库健康检查 |
| GET /api/operators | 运营方、时区和 Adapter 支持的运行模式 |
| GET /api/network?operator=mtr | 线路、站点、范围、时区和默认模式 |
| GET /api/service-day?operator=mtr&at=… | 所选时刻的服务日与计划存在状态 |
| GET /api/trains?operator=mtr&mode=simulation&at=… | 按时刻表与沿线里程计算位置；at 省略取当前时间 |
| GET /api/trains?operator=mtr&mode=realtime | 真实观测的位置和 ETA；拒绝 at 参数 |
| GET /api/trains/live?operator=mtr&mode=… | 每秒 SSE；慢连接只保留最新帧 |
| GET /api/transit/events?operator=mtr | 数据变更通知，暂停回放也可订阅 |
| GET /api/styles/{code}/style.json?operator=mtr | 完整原生样式，静态交通源按运营方查询 |
| GET /api/styles、/api/styles/{code}、/api/map-sources | 样式和来源目录 |
| GET /api/weather?lang=zh | 当前香港天气；按语言缓存，失败保留上次结果并标记过期 |

管理写入沿用 WORKBENCH_TOKEN 的 Bearer 鉴权；令牌为空时关闭工作台写入。

| 管理接口 | 用途 |
| --- | --- |
| POST /api/datasets/inspect、/preview、/api/datasets | 文件检查、预览、创建数据集 |
| GET /api/admin/datasets/{code}/features[/{key}] | 读取完整几何、属性、来源键和空间对象 ID |
| PUT /api/admin/datasets/{code}/features/{key} | 新增或修改单个 GeoJSON Feature |
| DELETE /api/admin/datasets/{code}/features/{key} | 删除要素；有业务引用返回 409 和依赖 |
| POST /api/admin/datasets/{code}/import?mode=merge或replace | 按来源键更新，replace 删除文件缺失的要素 |
| PUT /api/datasets/{code}/publication | 更新通用数据集发布状态 |
| PUT /api/styles/{code} | 保存原生业务样式和 metadata |
| GET /api/admin/transit、/adapters、/{code} | 接入配置与 Adapter 能力 |
| PUT、DELETE /api/admin/transit/{code} | 保存并重算，或解除全部业务绑定；源要素保留 |
| POST /api/admin/transit/timetable/preview | CSV 字段映射并转换成标准时刻表 |
| PUT /api/admin/transit/{code}/observations | feed Adapter 的完整当前观测批次 |

## 实现关系

- DatasetService / DatasetRepo：来源键、要素 CRUD、发布与删除依赖检查。
- TransitMappingService：有向路径、停站顺序、投影歧义与累计米制里程。
- TransitConfigService：配置校验、事务重建、Adapter 注册和按服务日装载。
- TimetableCompiler / MotionSampling / RoutePath：标准时刻表或发车规则 → 到发时刻 → 沿线里程 → 位置。
- RealtimeFrames：独立读取 GPS、metric、ETA，检查身份及时间、标记过期；ETA 不产生车辆位置。
- TransitFrames / MotionStreamService：缓存路网和当前回放窗口，生成统一结果及 SSE。
- MapStyleService / StyleRepo：原生样式及业务组、输出角色；Martin 发布静态交通瓦片。

修改空间要素时，在同一事务内重建所有相关运营方的业务关系和派生计划；真实时刻表保持原到发时间并重新校验。提交后清理实时缓冲、通知页面更新。实时数据及窗口缓存仅驻留单个 Java 进程；写入请使用 API，直接 SQL 不会触发重算与通知。

## 验证

`mvn -f backend/pom.xml test` 运行单元测试。独立 PostGIS 测试库可执行：

```powershell
mvn -f backend/pom.xml -Pintegration "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:15432/transit_v1" verify
```

Martin 连到同一测试库时，加 `-Dmartin.test.url=http://127.0.0.1:8083` 检查实际瓦片。测试覆盖来源身份、几何修改与回滚、跨午夜、运营方隔离、GPS 原值、过期观测、ETA-only、原生样式和鉴权。

[港铁导入示例](../docs/mtr-import-example.md) · [前端操作](../front/README.md)
