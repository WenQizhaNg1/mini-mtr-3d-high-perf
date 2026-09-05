# 架构边界

`front/` 使用 Vue 3 与 MapLibre；`backend/` 使用 Java 25、Spring Boot、jOOQ、JTS；PostGIS 存储完整空间数据和交通关系，Martin 提供静态瓦片。底图资源由 map/ 管理。

## 数据流

```text
GeoJSON / CSV / WKT / 地图绘制
    → DatasetService → TransitMappingService → TransitNetwork
                                     ↓             ↓
TransitAdapter.loadTimetable → TimetableCompiler → MotionSampling
TransitAdapter.readRealtime → RealtimeFrames
                                     ↓
                         TransitFrames → MotionFrame → MapLibre
```

模拟输入为标准到发时刻表，缺少时刻表时可配置发车规则。按到发时刻对相邻停站里程做线性插值，再沿完整路径定位。港铁默认服务规则标为模拟数据。

实时输入为 GPS、沿线里程或站点 ETA。GPS 保留来源坐标，里程按引用路径定位，ETA 只输出到站信息。两种模式显式选择，实时不可用或过期时不切换模拟。稳定车辆身份用于跟随；无车辆身份的 ETA 只保留记录身份。

前端插值只影响展示，暂停回放冻结当前帧。模拟帧包含未来最多 3 秒的路径拐点，倍速切换会重设展示时钟。

## 数据与更新

数据库只保存当前数据。feature 用 dataset_id + source_key 匹配导入更新，几何修改保留 spatial_object.id。station 与 route 的业务身份独立于空间对象；多个运营方和方案可共享源几何。

业务映射将完整路径、有序停站及投影位置写入 route / route_stop。站点编号按运营方隔离；方案编号在运营方配置内唯一。方向可反转，环线歧义由显式沿线比例解决。

空间编辑、映射和派生计划在事务内更新。所有引用该数据集的配置重算；校验失败整体回滚。有引用的要素先解除绑定再删除。提交后失效相关内存缓存并发送变更事件，前端重新加载静态来源和动态帧。

运营方配置保存原始时刻表和规则，trip / trip_stop 保存按服务日装载的当前计划。模拟按所选运营方的 IANA 时区解释服务日，支持 24:xx 跨午夜。实时观测、展示缓冲和计算窗口保留在单实例内存。

## 地图与页面

- 原生 style / layer 保存 paint、layout、filter 和 metadata。业务组使用 metadata.group，输出角色使用 metadata.role（routes、stations、vehicles）。
- 一组交通业务包含轨道、车站、车辆和标签等多个原生显示部分。角色决定交互和动态来源绑定，图层改名不影响选择。
- Martin 的 transit 函数源接收 operator 参数，输出 routes / stations；features 函数源接收 dataset 参数。业务函数源关闭服务端瓦片缓存。
- 首页分别选择运营方与模拟/实时模式。切换取消旧 HTTP/SSE，清空车辆和选中状态；数据事件订阅独立于播放订阅。
- 日夜样式按运营方时区在 06:00 / 18:00 切换，保留相机、跟随和选中状态。默认香港中心 zoom 14、倾斜视角，天空与薄雾随日夜配色变化。
- 工作台提供文件导入更新、点线绘制、顶点/属性编辑、字段映射、停站顺序、Adapter、时刻表 CSV 和发车规则。
- 管理令牌与草稿仅驻留页面内存。离开页面释放请求、订阅、动画和地图实例。

数据库由唯一 [V1](../backend/src/main/resources/db/migration/V1__application.sql) 初始化；无数据版本、发布快照或推演检查点。旧 ETA 校准模拟班次链路已删除。

[数据库设计](database-design.md) · [港铁导入示例](mtr-import-example.md) · [后端接口](../backend/README.md)
