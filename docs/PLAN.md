# Komga × 115 重做规划

> 版本：v2（2026-10-09）
> 定位：本次重做的唯一权威总纲。改代码前先读，改完同步更新。
> 前身：komga-notes/PLAN.md（已归档，XXH3）+ docs/FORK.md（SHA-1 特性说明）
> 位置：本文件是主库活文档，komga-notes 仓库已于 2026-10-09 冻结
> 状态：**阶段 0 完成，进入阶段 1**

---

## 0. 为什么重做

前一轮工程（2026-09 至 10-01）成果：

| 资产 | 状态 |
|---|---|
| Komga 源码改动（3 提交，SHA-1 路径哈希）| 在 /root/GitHub/komga |
| 规划与诊断文档（1500+ 行）| 在 /root/GitHub/komga-notes |
| 源数据库（jm/pica/wnacg，680 MB）| 在 komga-notes/dbs |
| 迁移脚本链（mkdir/move/inject）| 在 komga-notes |
| Komga 运行实例 | 三台机器均无，需重建 |
| 运行数据库 database.sqlite | 未找到 |

**决策：重建运行环境，复用全部既有资产与经验。**

---

## 1. 目标

> **只有真正看漫画时才碰 115。扫描、页数、元数据、列表全部本地完成。**

| # | 目标 | 验收标准 |
|---|---|---|
| G1 | 扫描零 I/O | 建库时不读任何 cbz 内容 |
| G2 | 列表页零 I/O | 浏览/翻页不触发 115 读取 |
| G3 | 阅读时才读盘 | 打开某页才取图；页清单走 SQLite |

---

## 2. 阶段 0 已确认的关键事实（2026-10-09 实测）

### 2.1 库根已变更（重大）

```
旧：/opt/clouddrive2/115open/comic/        ← 已不存在
新：/opt/clouddrive2/115open/content/      ← 现库根
     ├── wnacg/     (9月29日)
     ├── pika/      (10月2日，按 00-0f 十六进制分片)
     └── jmacg/     (8月22日)
```

**注意**：
- pika 从「按年份」改为「按 hex 分片」，结构变了
- 旧的 comic/ 目录消失，三库平铺在 content/ 下
- **Komga 若仍指向 comic/，必然找不到库**

### 2.2 数据完整性

已确认 cbz 实际存在，例如：
```
content/pika/2016/5821859e5f6b9a4f93dbf759.cbz
content/wnacg/...
```

### 2.3 环境（Dell 本机）

| 项 | 值 |
|---|---|
| Java | 21.0.12 |
| 内存 | 7 GB（编译需 -Xmx4g）|
| 磁盘 | 171 GB 可用 |
| 源码 | /root/GitHub/komga（分支 feat/sha1-relpath-hash）|
| 115 挂载 | /opt/clouddrive2/115open（CloudFS fuse）|

---

## 3. 待拍板的技术决策

| # | 决策点 | 选项 | 现状 |
|---|---|---|---|
| D1 | 哈希算法 | SHA-1 相对路径（与 LRR 互认）vs XXH3-128 | 倾向 SHA-1（已完成且验证）|
| D2 | 库根 | content/（新）| 已确认 |
| D3 | 建库方式 | 注入优先 vs 原生扫描 | 倾向注入（快）|
| D4 | tryRestoreBooks | 保留 / 降级 / 移除 | 待定 |

---

## 4. 已知事实（来自前一轮实测，可直接复用）

### 4.1 数据规模
- wnacg 磁盘 CBZ：150,742
- wnacg DB downloaded=1：150,877，元数据完整度 100%
- pages 字段合法率：99.87%

### 4.2 CBZ 结构（恒定）
- 条目 = 图片数 + 2（ComicInfo.xml + 元数据.json）
- 页名 4 位补零，1..N 连续

### 4.3 性能基线
| 操作 | 耗时 |
|---|---|
| CD2 listdir 单目录 26,417 项 | 14.6 s |
| CBZ 中央目录读取（冷）| 0.4–0.8 s/本 |
| FUSE stat（容器内）| ~31.8 ms/条目 |
| readdir 全目录（替代 File::Find）| 快 ≥17 倍 |

### 4.4 已踩的坑
- MEDIA_TYPE 必须 application/zip（不是 application/vnd.comicbook+zip）
- 页码两套基准：DB 0-based，API 1-based
- config-dir/lucene/fonts 是 @NotBlank
- **FUSE 上绝不加 rshared**（umount 永久阻塞）
- 宿主的 override/Database.pm 是孤儿文件，不生效

---

## 5. 分阶段计划

### 阶段 0：环境与前提确认（已完成）
- [x] 确认 115 库根 → content/
- [x] 确认源数据库可用
- [x] 确认构建环境
- [ ] 哈希算法最终决策（D1）

### 阶段 1：构建可运行的 Komga
- [ ] 编译当前分支 → 产出 jar
- [ ] 用最小配置启动（/opt/data/komga，指向 content/）
- [ ] 建小规模测试库（几百本），验证能跑

### 阶段 2：扫描零 I/O
- [ ] 验证/实现路径哈希
- [ ] 验证零读取扫描
- [ ] 测试库验证：扫描不读 cbz 内容

### 阶段 3：元数据注入
- [ ] 从源数据库生成注入数据
- [ ] 注入 MEDIA + MEDIA_PAGE
- [ ] 验证列表页/页清单不碰 115

### 阶段 4：功能完整性
- [ ] 修复 tryRestoreBooks
- [ ] 补测试

### 阶段 5：全量建库
- [ ] 全量扫描/注入
- [ ] 端到端验收

---

## 6. 验收标准

| # | 标准 | 验证方法 |
|---|---|---|
| V1 | 扫描 1000 本不读内容 | 监控 115 流量 / 日志 |
| V2 | 列表页翻页不碰 115 | 日志检查 |
| V3 | 打开漫画页才读 115 | 对比 CD2 请求 |
| V4 | 路径哈希与 LRR 一致 | 抽样对拍 |

---

## 7. 风险与回退

| 风险 | 缓解 | 回退 |
|---|---|---|
| 库根变更导致扫描失败 | 已确认新路径 | 指向 content/ |
| 编译内存不足 | -Xmx4g | 换机器编译 |
| 全量注入耗时 | 先小库验证 | 分批 |
| 上游同步冲突 | 记录改动文件清单 | 分支隔离 |

---

## 8. 资产索引

| 资产 | 路径 |
|---|---|
| Komga 源码 | /root/GitHub/komga |
| 改动分支 | feat/sha1-relpath-hash（3 提交）|
| 源码规划 | docs/FORK.md |
| 笔记与脚本 | /root/GitHub/komga-notes |
| 源数据库 | komga-notes/dbs/ |
| 旧规划 | komga-notes/PLAN.md（komga-notes 已冻结，只读） |
| 交接文档 | komga-notes/HANDOVER.md（同上，只读） |
| **本文件** | komga/docs/PLAN.md（主库活文档） |
