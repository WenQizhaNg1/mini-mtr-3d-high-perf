# 数据集、Martin 与样式发布

## 边界

Java 管理导入、语义校验、发布和样式；PostGIS 保存几何并生成 MVT；Martin 传输瓦片；MapLibre 消费完整 style.json。底图不可编辑。实时列车继续使用 SSE/GeoJSON，不进入瓦片。

## 数据

- `dataset(code, name, ontology_code, fields, published)`：一个数据集选择一个语义类别；fields 保存映射后的属性名及 string/number/boolean 类型。ontology_code 无外键，写入服务查询轻量本体。
- `feature(dataset_id, object_id, properties)`：空间对象的通用属性扩展，引用 spatial_object。每次导入创建新的空间对象，不修改 station/route 等交通业务表。
- 空间主表和本体保持不变。选择 route 语义不会自动生成运行方案和停站关系。
- 本轮不实现删除、覆盖导入、任意数据库/SQL 数据源、字段规则引擎或动态要素导入。

## 导入接口（管理令牌保护）

`POST /api/datasets/preview` 和 `POST /api/datasets` 使用相同 JSON：

```json
{
  "code": "my-stations", "name": "车站", "ontology": "station",
  "format": "geojson", "content": "{\"type\":\"FeatureCollection\",\"features\":[]}",
  "geometryColumn": "wkt", "longitudeColumn": "lng", "latitudeColumn": "lat",
  "mapping": {"站名": "name"}
}
```

format 为 geojson/csv/wkt；纯 WKT 每行一个几何；CSV 为带表头的逗号分隔文件，可选 WKT 列或经纬度列。mapping 空时保留全部非几何字段；非空时只导入列出的字段，映射目标不能重复。CSV 属性保留字符串（不猜测代码/数值）；GeoJSON 保留标量类型；嵌套属性拒绝。空值保留，非空值类型必须一致。

预览返回要素数、字段和最多 10 个样例，不写数据库；确认重新验证并事务导入，失败不留下空间对象。数据必须显式是二维 WGS84：检查有效性、有限坐标及范围；Web Mercator 发布限定纬度 ±85.05112878。不猜测或转换 GCJ02。限 5 MiB 文本、10000 个要素、总计 200000 个坐标点；不做全中国大文件导入。

`GET /api/ontology` 查询类别。`GET /api/datasets`、`GET /api/datasets/{code}` 仅返回已发布数据集；管理工作台使用 `GET /api/admin/datasets` 查看草稿。

`PUT /api/datasets/{code}/publication`，body `{"published":true}`，发布前重新校验本体；取消发布不删除数据。瓦片是公开数据服务，发布不是私有数据授权机制，不能撤回用户已下载的数据。

## Martin

一次性配置函数源 `features`，签名 `app.dataset_tile(z integer,x integer,y integer,query json) RETURNS bytea`。URL 为 `/features/{z}/{x}/{y}?dataset=编码`，MVT 内 source-layer 固定为 `features`。

固定参数查询只读取 published 数据集，使用空间索引和 64/4096 buffer；输出 object_id 为 feature ID、标量 JSONB 为属性。函数读取表，标记 STABLE，不标记 IMMUTABLE。未知、未发布或不合法瓦片坐标返回空瓦片。无动态 SQL，无每数据集建表/改配置。

首版通过 `cache: disable` 关闭函数源的服务端瓦片缓存，底图缓存保留。本机 Martin 1.12 已验证支持此项，但不支持单源 cache_control 响应头配置，因此不设置该无效项。取消发布保证后续到达函数的查询不返回数据，不保证清除客户端/代理已有缓存；若需要严格的浏览器缓存策略，在反向代理对 /features 设置 no-store。初次 Flyway 建函数后重启 Martin 一次，之后新增数据集不需重启。

## 样式

继续复用 style/layer。数据集源键为 `dataset:编码`，source-layer 固定 features。旧 mtr 和 mtr-trains 源保留。

`PUT /api/styles/{code}` 保存整套业务样式（管理令牌保护），沿用现有 detail JSON：name/basemap/layers。校验图层 ID 唯一、来源已发布、类型/几何适配、缩放范围和 JSON 结构。使用事务整体替换该样式图层。paint/layout 使用 MapLibre 原生对象；本轮校验结构，不声称实现完整 MapLibre 表达式类型检查，工作台后续接官方 validator。

`GET /api/styles/{code}/style.json` 输出标准 version:8 文档，拼接受控底图模板、绝对资源 URL、注册 sources 和有序业务 layers。旧 MTR 路线继续放在底图建筑之前，其他业务层放在底图之后。未发布数据集的图层不输出。所有样式接口 no-store；保存后重新请求生效，不推送现有页面。

底图模板从受控本地 map/style 目录读取，只允许 positron/osm-liberty-dark；不接受用户远程 URL，避免 SSRF。`MAP_DIRECTORY` 指向 map 目录，`MARTIN_PUBLIC_URL` 是浏览器可访问地址。完整 style 能独立显示静态图层；实时列车声明空 GeoJSON source，动画与交互仍由首页维护。

## 安全与目录

`WORKBENCH_TOKEN` 空值时关闭所有管理接口，设置后使用 `Authorization: Bearer ...`。不记录令牌。管理请求在读取 JSON 前校验令牌；写请求必须声明 Content-Length，JSON 请求体最多 6 MiB。写操作不支持跨域浏览器请求，工作台同源/开发代理接入；公开 GET 保留现有 CORS。

api 放 DatasetController/StyleController；service 放解析、发布与样式拼装；repo 放固定参数 SQL；输入 DTO、输出 VO 按现有目录组织。不引入额外框架。

## 验证

解析单元测试覆盖 GeoJSON/CSV/WKT、映射与错误；数据库集成测试验证事务、隔离数据集、发布状态和 MVT；HTTP 测试覆盖令牌和 style.json；对本机 Martin 执行真实参数化瓦片请求。现有首页和 API 保持兼容，本轮不做工作台 UI。

现有首页仍使用旧的 layers 接口和固定源，不会自动显示工作台新增数据集；下一轮将首页切换至完整 style.json 后再将自定义源加入首页样式。本轮可新建独立样式供 MapLibre 预览。

## 本轮验证结果

- V7 已应用于本地 PostGIS。已有交通数据保持不变。
- 单元测试覆盖三种格式、坐标/几何错误、属性映射、管理令牌。
- 集成测试覆盖公开 API、写入鉴权、未发布隔离、样式拼装、取消发布。
- 独立 Martin 1.12 容器验证同一个 URL 从 204 → 200 → 204，MVT 内容与数据库直接调用一致。
- 完整日夜 style.json 已通过当前前端安装的 MapLibre 官方 validateStyleMin 校验器；此项是开发验证，不代表保存接口已实现完整表达式校验。

运行真实 Martin 测试（目标必须已加载 V7 函数和新版 martin.yaml）：

```powershell
mvn -B -ntp -f backend/pom.xml -Pintegration '-Dit.test=BackendIT,StyleIT,DatasetIT,MartinIT' '-Dmartin.test.url=http://127.0.0.1:8081' verify
```

MartinIT 仅创建随机编码的测试数据集，结束时清理该数据集及其空间对象；其他集成测试使用事务回滚。
