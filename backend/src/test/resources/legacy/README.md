# 旧实现对照样本

`0001_train-times.sql` 保留自删除前的 Node 后端迁移文件，供 `LegacyScheduleIT` 提取原位置查询、对照 Java 回放结果。它只属于测试资源，不被 Flyway 执行，也不参与运行时建表。

旧表对照测试通过 `-Dlegacy.verify=true` 显式启用；需要数据库中仍有旧 `mtr` 快照。普通测试及 Java 运行不依赖旧 Node 后端。
