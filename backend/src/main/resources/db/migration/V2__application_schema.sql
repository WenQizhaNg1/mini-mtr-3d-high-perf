-- Application schema managed by Flyway.
-- 仅覆盖空间、本体、现有交通计划、地图样式和推演恢复。
-- 真实车辆、行政区天气及事件表暂缓，待实际接入时再定义。
-- 使用现有 PostGIS 扩展。所有表暂归入 app，避免先拆分多个业务 schema。
CREATE SCHEMA app;

-- 空间对象：只负责稳定身份和主几何。
-- SRID 固定为 4326（WGS84），直接由 geometry 类型声明，不重复保存 srid 列。
-- 类型声明不会转换坐标；其他坐标系的数据必须先转换再入库。
CREATE TABLE app.spatial_object (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    geom geometry(Geometry, 4326) NOT NULL,
    CHECK (NOT ST_IsEmpty(geom)),
    CHECK (ST_IsValid(geom)),
    CHECK (ST_NDims(geom) = 2)
);

CREATE INDEX spatial_object_geom_idx ON app.spatial_object USING gist (geom);

-- 本体：独立、只供查询的类别注册表。
-- 无业务表外键、扩展表映射或跨表触发器。
-- 写入服务按语义编码查询，检查是否启用及实际几何类型是否在支持范围内。
-- is_dynamic 仅说明要素是否动态，不决定采用哪张表或如何计算。
CREATE TABLE app.ontology (
    code text PRIMARY KEY,
    name text NOT NULL,
    geometry_types text[] NOT NULL,
    is_dynamic boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    description text NOT NULL DEFAULT '',
    CHECK (cardinality(geometry_types) > 0),
    CHECK (geometry_types <@ ARRAY[
        'Point', 'MultiPoint', 'LineString', 'MultiLineString',
        'Polygon', 'MultiPolygon'
    ]::text[])
);

-- 设施扩展通过空间对象 ID 关联；同一对象可以同时具有多个业务扩展。
-- 运行方案拥有独立业务 ID，可以共享同一条路径几何。
-- 主表不保存来源键。导入去重使用各业务表的代码及业务命名空间。

-- 运营方；时区采用 IANA 名称，例如 Asia/Hong_Kong。
CREATE TABLE app.operator (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    timezone text NOT NULL
);

-- 运营线路；不要求拥有独立几何。
CREATE TABLE app.line (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id bigint NOT NULL REFERENCES app.operator (id),
    code text NOT NULL,
    name text NOT NULL,
    name_en text,
    mode text NOT NULL CHECK (mode IN ('metro', 'rail', 'bus')),
    colour text NOT NULL CHECK (colour ~ '^#[0-9A-Fa-f]{6}$'),
    UNIQUE (operator_id, code)
);

-- 站级对象：地铁站、铁路车站或公交站点集合，可用点或面表达。
-- 与线路一致，code 在运营方内唯一，不依靠手工拼接前缀。
-- 这里的 operator_id 表示站码归属；不用于推断所有停靠线路的运营方。
CREATE TABLE app.station (
    id bigint PRIMARY KEY REFERENCES app.spatial_object (id),
    operator_id bigint NOT NULL REFERENCES app.operator (id),
    code text NOT NULL,
    name text NOT NULL,
    name_en text,
    UNIQUE (operator_id, code)
);

-- 具体停靠点/设施：站台、方向相关的公交停靠点等，几何也可以是面。
-- 同名道路两侧停靠点分别建行；未知的站台或停靠点不伪造。
-- code 在所属站内唯一，命名空间沿 station 归属，不重复维护运营方字段。
CREATE TABLE app.stop (
    id bigint PRIMARY KEY REFERENCES app.spatial_object (id),
    station_id bigint NOT NULL REFERENCES app.station (id),
    code text NOT NULL,
    name text,
    UNIQUE (station_id, code),
    UNIQUE (id, station_id)
);

-- 运行方案：一条线路下的一套有序停站方案，拥有独立业务身份。
-- object_id 引用沿线定位用的路径几何，不设 UNIQUE；不同方案可共享同一几何。
-- 写入服务检查几何为有方向的 LineString；反向运行不能直接复用正向距离定义。
-- 共享几何修改时，须重新校验所有引用它的方案及沿线停站位置。
-- 它不是物理铁轨；首轮不从现有 route_patterns 伪造物理轨道拓扑。
CREATE TABLE app.route (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES app.spatial_object (id),
    line_id bigint NOT NULL REFERENCES app.line (id),
    code text NOT NULL,
    name text,
    direction text,
    UNIQUE (line_id, code)
);

CREATE INDEX route_object_idx ON app.route (object_id);

-- 有序停站方案。只有站级数据时 stop_id 为空。
-- 同一站可在环线中出现多次，使用 seq 区分，并保存每次经过的累积位置。
CREATE TABLE app.route_stop (
    route_id bigint NOT NULL REFERENCES app.route (id),
    seq integer NOT NULL CHECK (seq >= 0),
    station_id bigint NOT NULL REFERENCES app.station (id),
    stop_id bigint,
    distance_m double precision NOT NULL CHECK (distance_m >= 0),
    fraction double precision NOT NULL CHECK (fraction BETWEEN 0 AND 1),
    pickup boolean NOT NULL DEFAULT true,
    dropoff boolean NOT NULL DEFAULT true,
    PRIMARY KEY (route_id, seq),
    FOREIGN KEY (stop_id, station_id) REFERENCES app.stop (id, station_id)
);

CREATE INDEX route_stop_station_idx ON app.route_stop (station_id);

-- 某服务日的一次运行，替代 train_runs；不以高铁车次显示编号作为全局主键。
-- source + source_key 表达班次的来源身份，不放进空间主表。
-- service_date 按来源服务日解释，时刻采用绝对时间，不全局硬编码 05:30 边界。
CREATE TABLE app.trip (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id bigint NOT NULL REFERENCES app.route (id),
    service_date date NOT NULL,
    code text,
    source text NOT NULL,
    source_key text NOT NULL,
    plan_type text NOT NULL CHECK (plan_type IN ('timetable', 'frequency', 'simulated', 'estimated')),
    UNIQUE (source, service_date, source_key),
    UNIQUE (id, route_id)
);

CREATE INDEX trip_service_date_idx ON app.trip (service_date);
CREATE INDEX trip_route_idx ON app.trip (route_id);

-- 每站计划到发时刻，替代 train_legs 作为计划权威。
-- 相邻站间行程由上一站 departure_at 和下一站 arrival_at 派生，不另建可编辑区间表。
-- 首末站不适用时刻、来源缺失时刻允许为空；缺失不代表零停站时间。
-- 跨站时间先后关系由计划写入服务校验。
CREATE TABLE app.trip_stop (
    trip_id bigint NOT NULL,
    route_id bigint NOT NULL,
    seq integer NOT NULL,
    arrival_at timestamptz,
    departure_at timestamptz,
    PRIMARY KEY (trip_id, seq),
    FOREIGN KEY (trip_id, route_id) REFERENCES app.trip (id, route_id),
    FOREIGN KEY (route_id, seq) REFERENCES app.route_stop (route_id, seq),
    CHECK (arrival_at IS NULL OR departure_at IS NULL OR arrival_at <= departure_at)
);

-- 一套地图样式；底图键引用已配置的背景地图资源。
CREATE TABLE app.style (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    basemap text NOT NULL
);

-- 一类要素可由多个图层显示，例如填充、描边、标签。
-- source 是已注册的数据源键；不保存任意 SQL、数据库连接或凭据。
-- type/layout/paint/filter 使用 MapLibre 原生定义，由配置写入/加载服务校验。
-- 不引用本体；本体不是图层或扩展表的依赖结构。
CREATE TABLE app.layer (
    style_id bigint NOT NULL REFERENCES app.style (id),
    code text NOT NULL,
    source text NOT NULL,
    source_layer text,
    type text NOT NULL,
    position integer NOT NULL,
    minzoom double precision NOT NULL DEFAULT 0 CHECK (minzoom BETWEEN 0 AND 24),
    maxzoom double precision NOT NULL DEFAULT 24 CHECK (maxzoom BETWEEN 0 AND 24),
    layout jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(layout) = 'object'),
    paint jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(paint) = 'object'),
    filter jsonb,
    enabled boolean NOT NULL DEFAULT true,
    PRIMARY KEY (style_id, code),
    UNIQUE (style_id, position),
    CHECK (minzoom < maxzoom)
);

-- 单实例实时推演检查点；保存恢复状态，不充当逐帧车辆位置表。
-- 这里列出目标结构，现有 mtr.motion_checkpoint 尚未移动或修改。
CREATE TABLE app.motion_checkpoint (
    id integer PRIMARY KEY CHECK (id = 1),
    state jsonb NOT NULL
);
