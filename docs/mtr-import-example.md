# 用当前港铁数据完成一次接入

输入来自仓库已有的 OSM WGS84 数据：98 个站点、24 个有向运行方案，覆盖 10 条线路。发车配置是模拟输入；港铁官方接口提供站点 ETA，目前没有 GPS 或稳定车辆 ID。

## 一次性跑通

确认 Java 已执行 V1，Martin 已启动，根目录 .env 已配置 WORKBENCH_TOKEN：

```powershell
node --env-file=.env scripts/import-mtr.mjs
```

指定其他 API 实例：

```powershell
node --env-file=.env scripts/import-mtr.mjs --api=http://127.0.0.1:3003
```

脚本完全使用通用数据与交通接入接口。首次创建 mtr-paths / mtr-stations；再次运行按来源键合并更新，保留源空间对象 ID，最后覆盖当前港铁接入配置并重算。合并不会删除文件中缺失的要素；彻底替换请使用工作台“更新文件→替换全部”，并先处理删除依赖。

默认模拟计划来自 data/mtr-service.json。导入完成输出线路、站点、方案和当时车辆数量；车辆数随时间变化。

## 空间数据与字段映射

| 输入 | 路径数据 | 站点数据 |
| --- | --- | --- |
| 文件 | data/osm/generated/mtr-route-patterns.geojson | data/osm/generated/mtr-stations.geojson |
| 数据集编码 | mtr-paths | mtr-stations |
| 类别 | route | station |
| 来源唯一键 | id，如 ISL:KET-CHW | code，如 KET |
| 业务字段 | line_id→line；colour→colour；脚本补充 line_name→lineName | code→code；name_zh→name；name_en→nameEn；line_ids→lines |

data/osm/generated/mtr-route-patterns.json 提供各方案的有序站点及 pathCoordinate。脚本将实际停站位置转换为沿线比例，避免用大型车站中心点匹配多条轨道时产生歧义。

站点、路径先进入 dataset / feature / spatial_object。保存交通配置后才建立 line、station、route、route_stop。瓦片和运动计算共同读取这些交通关系，计算使用完整路径。

在工作台“交通接入”选择港铁，可以看到 24 个方案、每站顺序及明确比例，并直接修改。反转路径时须同时检查停站顺序；几何或属性修改造成无效关系时，整个保存会回滚。

## 模拟时刻表与规则

选择 Adapter=mtr、模式=simulation；当前默认服务日起点为 05:30，时区 Asia/Hong_Kong。没有填写自己的时刻表或规则时，MtrAdapter 把已有发车设置转换成标准模拟班次，再交给共用计算器。

需要使用真实排班表时，填写标准时刻表或在表单导入 CSV。每个班次必须覆盖其方案全部停站；短线班次应建立相应的短线方案。两站方案的例子：

```csv
trip,pattern,seq,arrival,departure,serviceDate
T01,my-outbound,0,,23:58,2026-09-05
T01,my-outbound,1,24:03,,2026-09-05
T02,my-outbound,0,,24:08,2026-09-05
T02,my-outbound,1,24:13,,2026-09-05
```

CSV 表单可以映射自己的列名。seq 从 0 开始，首站 arrival 和末站 departure 可空；服务日期省略表示每日。发车规则填写首末班、间隔秒数、速度 km/h 和停站秒数，保存时生成模拟计划。

通用新线路可直接选 file；有标准观测推送时选 feed。只有使用港铁内置规则才依赖港铁预设方案名称；其他数据使用自己的时刻表和方案编号。

## 实时接入

首页切为实时后，调用独立的 MtrAdapter.readRealtime。监测站配置来自 mtr-service.json，官方到站预测进入 MotionFrame.arrivals，trains 保持空数组。ETA 不匹配模拟班次，也不补造车辆位置。过期或请求失败会显示状态，保持当前模式。

其他来源可实现 TransitAdapter 并注册为 Spring 组件；声明 modes，分别提供 loadTimetable 和 readRealtime。数据库 adapter 字段不限制实现名称，能力由服务端注册表校验。适配器只做来源转换。

已有标准数据可使用 feed Adapter，携带管理令牌推送完整当前观测数组：

```http
PUT /api/admin/transit/my-operator/observations
Authorization: Bearer <WORKBENCH_TOKEN>
Content-Type: application/json
```

```json
[
  {
    "kind": "gps", "id": "position-001", "vehicleId": "train-001",
    "lineId": "L1", "observedAt": "2026-09-05T12:00:00Z",
    "lng": 114.165, "lat": 22.285, "bearing": 90
  },
  {
    "kind": "eta", "id": "arrival-001", "lineId": "L1",
    "stationId": "B", "destinationId": "C",
    "observedAt": "2026-09-05T12:00:00Z", "eta": "2026-09-05T12:03:00Z"
  }
]
```

metric 观测使用 kind=metric、vehicleId、patternId、distanceMeters，省略 lng/lat。位置必须有稳定车辆身份；ETA 允许只有记录 ID。示例时间要替换成实际观测时间；旧数据会按 staleAfterSeconds 标记过期。同一批次 ID 不重复，同一车辆只提供一个位置；推送替换当前批次，乱序更新被拒绝。

实时批次在内存中，服务重启或接入配置修改后需来源重新上报。平滑仅在前端展示过程中发生，来源坐标和时刻保留原值。

## 验收结果

- 全新库仅执行 V1 后可完成港铁导入，得到 10 条线路、98 个站点、24 个方案。
- 独立测试线路通过页面画线、放三个站、映射字段、导入两个班次并回放。
- 修改共享路径后，所有关联方案按新路径运行；源空间对象 ID 保持不变。
- 实时 ETA-only 场景输出到站预测，车辆数量为 0；GPS 保持来源坐标，过期和乱序分别处理。
- 时间与几何无效时回滚；日夜切换、图层改名后的交互和车辆跟随仍受浏览器回归测试覆盖。

完整表结构见 [数据库设计](database-design.md)，接口见 [后端说明](../backend/README.md)。
