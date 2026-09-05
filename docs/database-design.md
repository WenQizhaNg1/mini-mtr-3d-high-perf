# 数据库设计

完整 SQL 位于 [V1__application.sql](../backend/src/main/resources/db/migration/V1__application.sql)。PostgreSQL 18 / PostGIS 3.6，应用数据在 app，Flyway 结构迁移记录在 backend_meta。V1 包含 16 张业务表、2 个视图、2 个瓦片函数。

数据库保存当前状态，修改直接覆盖当前记录。Flyway 管理结构迁移；业务数据没有 revision、历史快照或发布版本。动态观测与播放缓冲不入库。

## 表和字段

除特别说明外，字段非空。所有业务数字 ID 为 bigint identity 主键；几何固定为二维 WGS84（EPSG:4326）。带“?”的字段可空。

| 表 | 完整字段 | 关系与约束 |
| --- | --- | --- |
| spatial_object | id bigint；geom geometry(Geometry,4326) | 非空、有效、二维几何；GiST 空间索引 |
| ontology | code text PK；name text；geometry_types text[]；is_dynamic boolean=false；enabled boolean=true；description text='' | 静态类别注册表；至少一个合法几何类型，无业务表外键 |
| dataset | id bigint；code text；name text；ontology_code text；fields jsonb；published boolean=false | code 全局唯一；fields 为属性名到类型的对象；类别由写入服务校验 |
| feature | dataset_id bigint；object_id bigint；source_key text；properties jsonb | PK(dataset_id,object_id)；UNIQUE(dataset_id,source_key)；来源键 1–200 字符；FK dataset、spatial_object |
| operator | id bigint；code text；name text；timezone text | code 全局唯一；timezone 由服务校验为 IANA 时区 |
| line | id bigint；operator_id bigint；code text；name text；name_en text?；mode text；colour text | FK operator；UNIQUE(operator_id,code)；mode=metro/rail/bus；颜色为 #RRGGBB |
| station | id bigint；object_id bigint；operator_id bigint；code text；name text；name_en text? | FK spatial_object、operator；UNIQUE(operator_id,code) |
| stop | id bigint；object_id bigint；station_id bigint；code text；name text? | 具体站台/停靠点；FK spatial_object、station；UNIQUE(station_id,code)、UNIQUE(id,station_id) |
| line_station | line_id bigint；station_id bigint | 联合主键，分别 FK line、station；线路归属独立于停站方案 |
| route | id bigint；object_id bigint；line_id bigint；code text；name text?；direction text?；reversed boolean=false | FK spatial_object、line；UNIQUE(line_id,code)；服务额外要求方案代码在运营方内唯一；共享几何允许多个方案 |
| route_stop | route_id bigint；seq integer；station_id bigint；stop_id bigint?；distance_m float8；fraction float8；pickup boolean=true；dropoff boolean=true | PK(route_id,seq)；FK route、station、(stop_id,station_id)；seq≥0、米制距离≥0、比例 0–1 |
| trip | id bigint；route_id bigint；service_date date；code text?；source text；source_key text；plan_type text | FK route；UNIQUE(source,service_date,source_key)、UNIQUE(id,route_id)；plan_type=timetable/frequency/simulated |
| trip_stop | trip_id bigint；route_id bigint；seq integer；arrival_at timestamptz?；departure_at timestamptz? | PK(trip_id,seq)；FK(trip_id,route_id)、(route_id,seq)；到达≤出发；跨站先后由服务校验 |
| transit_config | operator_id bigint PK；adapter text；mode text；mapping jsonb；simulation_params jsonb；realtime_params jsonb | FK operator；mode=simulation/realtime；三个 JSON 均为对象；Adapter 由服务注册表校验 |
| style | id bigint；code text；name text；basemap text；metadata jsonb={} | code 唯一；受控底图键；metadata 保存业务组信息 |
| layer | style_id bigint；code text；source text；source_layer text?；type text；position integer；minzoom float8=0；maxzoom float8=24；layout jsonb={}；paint jsonb={}；filter jsonb?；enabled boolean=true；metadata jsonb={} | PK(style_id,code)；UNIQUE(style_id,position)；FK style；0≤minzoom<maxzoom≤24；原生 MapLibre 属性 |

数据库表支持独立站台，当前映射入口使用 Point 站点和 LineString 路径；没有站台输入时不生成 stop。环线可重复经过同一站，由 seq 和累计里程区分每次停站。

显式站位 fraction 使用 WGS84 平面线长度比例，与 ST_LineInterpolatePoint 一致；distance_m 使用沿线米制累计距离。车辆计算使用 distance_m。reversed=true 时先反转路径，再解释比例和停站顺序。

## 接入配置 JSON

| 列 | 内容 |
| --- | --- |
| mapping | routesDataset、stationsDataset；routeFields(line/colour/lineName)、stationFields(code/name/nameEn/lines)；patterns[{code,featureKey,direction,reversed,stops[{stationKey,fraction?}]}]；snapToleranceMeters |
| simulation_params | serviceDayStart；rules[{id,pattern,first,last,headwaySeconds,speedKmph,dwellSeconds}]；timetable[{key,pattern,serviceDate?,stops[{seq,arrival?,departure?}],type?}] |
| realtime_params | monitors[{line,station}]（港铁）；staleAfterSeconds |

时刻表时间为 HH:mm[:ss]，支持 24–71 时；也可提供带偏移的 ISO 时间。serviceDate 空值表示每日计划。夏令时重复/不存在的本地时刻必须改用明确的 ISO 偏移。

班次从该服务日起点之后开始，并在三个服务日内结束；凌晨发生在次日的班次使用 24:xx。运行缓存分别保留当前时间与回放窗口，最多缓存 8 个窗口。

transit_config 保留当前输入，trip / trip_stop 是按服务日装载的当前计划。重新映射保持真实时刻表到发时间，重新校验速度和顺序；规则生成计划按新里程重算。班次来源为“运营方:schedule”，避免不同运营方的同名班次相撞。

## 查询和更新

- map_routes：运营方、线路颜色、起终站、有向路径与长度。
- map_stations：运营方、站名、线路归属、换乘标记和站点几何。
- transit_tile(z,x,y,query)：query.operator 必填，生成 routes / stations 两个 MVT 图层。
- dataset_tile(z,x,y,query)：query.dataset 必填，只输出已发布数据集，图层名 features，含 source_key。
- Martin 对这两个函数源关闭缓存。数据事件让浏览器重新加载来源；完整计算几何通过数据库查询，与瓦片裁切和缩放层级无关。

外键默认阻止被引用数据删除。要素删除接口先返回依赖；解除业务绑定后才能删除。运营方解除绑定按依赖顺序删除计划、停站关系和业务对象，保留空间数据集。

空间写入与相关配置重建在一个事务完成，失败整体回滚。运营方锁协调计划装载与映射修改；提交后清理缓存并通知页面。当前内存观测与通知服务采用单 Java 实例。

## 初始化

先创建启用 PostGIS 的空库，再启动 Java 执行唯一 V1。V1 只放入本体与日夜样式，不包含港铁交通数据。随后按 [港铁示例](mtr-import-example.md) 通过通用 API 导入。

本轮已重建本地 mtr 应用库。未来普通内容修改使用工作台/API，无需修改迁移或重建库。
