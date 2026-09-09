-- 台账宽度修复（实跑 PG 暴露，H2 测试未覆盖）：
-- mode 原 VARCHAR(16) 装不下 "python-deep-degraded"(19 字符) → updateRunMode 失败，
-- 降级运行的 mode 永远停在插入时的初值（java-direct），台账口径失真。
-- 加宽到 VARCHAR(32) 容纳全部 mode 取值（java-direct / python-deep / python-deep-degraded / stream-fallback）。
ALTER TABLE agent_run ALTER COLUMN mode TYPE VARCHAR(32);
