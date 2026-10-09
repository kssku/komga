# Komga 懒缩略图改造设计文档

> 目标：把「扫描/入库」链路上**最后一处归档读取**（缩略图生成）从默认路径移除，
> 使超大库 + 网盘 FUSE 场景下做到「全链路零归档读取」，与 lrr-custom 的
> `LRR_THUMBNAIL_MODE` 保持同一设计语言。

---

## 1. 背景与现状

### 1.1 已完成（本 fork 已落地）

| 环节 | 文件 | 改动 |
|---|---|---|
| 文件哈希 | `infrastructure/hash/Hasher.kt` | `computePathHash` 只哈希路径，不读内容 |
| 媒体分析 | `infrastructure/mediacontainer/divina/ZipExtractor.kt` | `getEntries` 只读 central directory，零条目读取 |
| 媒体分析 | `domain/service/BookAnalyzer.kt` | `analyzeDivina` 走上述零读路径 |

`ZipExtractor` 的 CUSTOM FORK 注释已明确：
- 类型来自 entry **名字**（内存 Tika 查询）
- `fileSize` 来自 `entry.size`
- `dimension` **恒为 null**（不读图像头）
- 加密靠 general purpose flags 检出

### 1.2 剩余热点：缩略图

调用链（扫描入库时**自动触发**）：

```
Task.ScanLibrary
  └─ TaskHandler.kt:56  taskEmitter.analyzeUnknownAndOutdatedBooks(library)
       └─ Task.AnalyzeBook
            └─ TaskHandler.kt:87-88  bookLifecycle.analyzeAndPersist(book)
                 └─ 返回 Set<BookAction> 含 GENERATE_THUMBNAIL
                      └─ TaskHandler.kt:88  taskEmitter.generateBookThumbnail(book.id)
                           └─ Task.GenerateBookThumbnail
                                └─ TaskHandler.kt:94  bookLifecycle.generateThumbnailAndPersist(book)
                                     └─ BookLifecycle.kt:147  bookAnalyzer.generateThumbnail(...)
                                          └─ BookAnalyzer.kt:244  ← 打开归档，读第一页
```

**问题**：每个新归档入库时都会走一遍 `generateThumbnail`，在 FUSE 上就是一次（或多次）归档读取。十万级增量入库时被反复拖慢。

### 1.3 派生点唯一性确认

`Task.GenerateBookThumbnail` 的**自动**派生点只有一处：
- `TaskHandler.kt:88`（`analyzeAndPersist` 返回 `GENERATE_THUMBNAIL` 时）

手动/API 派生点（保留，不受开关影响）：
- `TaskEmitter.kt:161-175` `generateBookThumbnail(bookId(s))` —— 供手动重新生成、SSE 补图等调用
- `LibraryController.kt:253`、`SeriesController.kt:657`、`BookController.kt:634` —— 用户主动触发的 analyze

---

## 2. 设计目标

1. **默认懒生成**：入库/扫描**不**生成缩略图，首次需要时才生成。
2. **可回退**：一个环境变量切回上游行为（`auto`），便于排障与对照。
3. **最小侵入**：不改数据库 schema，不改 API 契约，前端无需大改。
4. **与 lrr 一致**：环境变量命名、取值语义对齐 `LRR_THUMBNAIL_MODE`。

---

## 3. 方案

### 3.1 开关

```
KOMGA_THUMBNAIL_MODE = lazy | auto
```

| 值 | 行为 |
|---|---|
| `lazy`（**默认**）| 入库不生成；`getThumbnailBytes` 命中空时返回 null（404），前端显示占位；首次进入阅读器触发按需生成 |
| `auto` | 上游行为：入库即生成 |

实现位置：新增 `ThumbnailMode` 配置，或在 `KomgaSettingsProvider` / `application.yml` 里读取。

### 3.2 拦截点

**唯一拦截点**：`BookLifecycle.analyzeAndPersist`（`BookLifecycle.kt:104`）

```kotlin
// 现状
return if (media.status == Media.Status.READY)
  setOf(BookAction.GENERATE_THUMBNAIL, BookAction.REFRESH_METADATA)
else emptySet()

// 改后
return if (media.status == Media.Status.READY)
  buildSet {
    if (thumbnailMode == ThumbnailMode.AUTO) add(BookAction.GENERATE_THUMBNAIL)
    add(BookAction.REFRESH_METADATA)
  }
else emptySet()
```

**为什么选这里**：
- 它是 `Task.GenerateBookThumbnail` 唯一自动派生的源头，卡这里等于关掉整条自动链。
- 手动触发路径（API / UI「重新生成封面」）不经过此判断，仍然可用。
- 不影响 `REFRESH_METADATA`，元数据照常刷新。

### 3.3 按需生成（阅读器首次打开）

`getThumbnailBytes`（`BookLifecycle.kt:221`）当前**只读已存在**的缩略图，不现场生成——已确认安全。

需要新增：某个入口在「取缩略图但库里没有」时，**异步**排一个 `Task.GenerateBookThumbnail`。

候选入口（择一或都做）：

| 入口 | 位置 | 说明 |
|---|---|---|
| A. 阅读器打开书籍 | `BookController` 的 page/read 接口 | 用户真要看了才生成，最省 |
| B. 列表/详情页取封面 404 时 | `BookController` thumbnail 接口 | 会因列表滚动触发大量生成，需限流 |
| C. 显式 SSE 补图任务 | 现有 `ThumbnailBookSseDto` 链路 | 前端已有补图机制，复用它 |

**推荐 A + C**：
- A：保证「打开就一定有图」。
- C：前端可主动请求补图，批量场景可控。
- 不做 B：避免列表滚动把 FUSE 打爆。

### 3.4 前端表现

- 无缩略图时后端返回 **404**（现有 `getThumbnailBytes` 返回 null 的行为）。
- 前端需确认：404 时显示占位图而非报错。
  - `next-ui`：检查封面组件的 error/fallback 分支。
  - `komga-webui`（legacy）：同上。
- SSE 补图完成后前端刷新对应封面（现有事件机制，`DomainEvent.ThumbnailBookAdded`）。

---

## 4. 改动点清单

| # | 文件 | 改动 | 风险 |
|---|---|---|---|
| 1 | 新增 `ThumbnailMode`（enum + 配置读取）| 读 `KOMGA_THUMBNAIL_MODE`，默认 `lazy` | 低 |
| 2 | `BookLifecycle.kt:104` | `analyzeAndPersist` 按 mode 决定是否含 `GENERATE_THUMBNAIL` | 低（单点）|
| 3 | `BookLifecycle.kt`（新增方法）| 暴露「按需生成」给控制器调用 | 低 |
| 4 | `BookController.kt` | 阅读器入口触发按需生成 | 中（注意幂等/去重）|
| 5 | `next-ui` 封面组件 | 404 → 占位图 | 低 |
| 6 | `komga-webui` 封面组件 | 同上（若仍维护）| 低 |
| 7 | 文档 | README / FORK_CHANGES 补一条 | 低 |

### 待确认项（写代码前）

1. `KOMGA_THUMBNAIL_MODE` 走 `application.yml` + `@Value` 还是 `KomgaSettingsProvider`？
   - 倾向 `@Value`（一次性读，重启生效，与 lrr 的 env 语义一致）。
2. 按需生成的**去重**：同一本书被并发请求，会不会排多个 `GenerateBookThumbnail`？
   - `Task` 系统是否自带去重需确认；否则需在入口加「已有 pending 则跳过」。
3. 阅读器入口具体是哪个接口（`/api/v1/books/{id}/pages/{n}`？还是打开书籍的 GET？）
   - 需读 `BookController` 确认最小侵入点。
4. 已入库的大量旧书（无缩略图）首次上线的表现：
   - 列表页会大面积 404 → 占位图。
   - 是否需要一次性补图任务（可选，手动触发）？

---

## 5. 与 lrr-custom 的对照

| 维度 | lrr-custom | komga（本方案）|
|---|---|---|
| 开关名 | `LRR_THUMBNAIL_MODE` | `KOMGA_THUMBNAIL_MODE` |
| 取值 | `lazy` / `auto` | `lazy` / `auto` |
| 默认 | `lazy` | `lazy` |
| 拦截点 | 入库链路缩略图生成处 | `analyzeAndPersist` 返回值 |
| 按需生成 | 首次阅读器打开 | 阅读器入口 + SSE |
| 回退 | 设 `auto` | 设 `auto` |

---

## 6. 验证计划

1. **单元**：`BookLifecycle` 在 `lazy` 下 `analyzeAndPersist` 不含 `GENERATE_THUMBNAIL`；`auto` 下含。
2. **集成**：扫描一个含新归档的库，观察任务队列**不出现** `GenerateBookThumbnail`。
3. **功能**：手动打开一本书，确认按需生成并落库；再打开列表确认封面出现。
4. **回退**：设 `auto` 重启，确认恢复入库即生成。
5. **FUSE 实测**：在 115 挂载库上增量入库，用 `strace`/挂载层日志确认扫描期间**无归档 open**。

---

## 7. 封面来源（已定：外部封面根 + fileHash 命名）

> **决策（2026-10）**：放弃「懒生成抽页」，封面改为从 115 上一个**独立封面目录**读取，
> 文件名为 **`book.fileHash`**（= `SHA1(相对路径)`，40 位小写十六进制，与 lrr `compute_id` 一致）。
> 浏览时读的是小图，接受其网盘开销；**cbz 本体仍只在阅读时读取**。

### 7.1 命名与哈希格式

哈希实现见 `infrastructure/hash/Hasher.kt`：

- 算法 **SHA-1**
- 输出 **40 位十六进制小写**（`Hasher.kt:99-106`，逐字节 `toString(16).padStart(2,'0')`）
- 哈希内容 = `UTF-8( 相对 <library root>/.. 的路径 )`，例：`wnacg/1-50000/foo.cbz`
- **与 LANraragi `compute_id` 字节一致**，两系统可互相识别

封面路径规则：

```
{外部封面根}/{book.fileHash}.{ext}
```

### 7.2 时序陷阱（必须处理）

`book.fileHash` **不是入库即有**，由 `hashAndPersist` 异步补（`BookLifecycle.kt:113-116`），
且受 library 的 `hashFiles` 开关控制。Provider 必须处理：

- `fileHash` 有值 → 拼路径查找
- `fileHash` 为空 → 返回空，**不得**拼出 `/.jpg` 这类无效路径

### 7.3 为何不用上游 SIDECAR

`LocalArtworkProvider`（`LocalArtworkProvider.kt:36-47`）只支持**书同目录、同名前缀**：

- 只 `Files.list(bookPath.parent)`，不递归、不跨目录
- 文件名 = cbz 名（去扩展名）+ 可选 `-数字`

本场景封面集中在**独立目录树**，上游机制不适用，需**新增 Provider**。

### 7.4 新增 Provider 设计

| 项 | 设计 |
|---|---|
| 类名（拟） | `ExternalCoverArtworkProvider` |
| 输入 | `book.fileHash` + 外部封面根（配置） |
| 输出 | `ThumbnailBook(type = SIDECAR, url = 封面文件 URL)` |
| 分片 | **必须分片**（如 `{hash 前2位}/{hash}.jpg`），避免十万文件平铺目录 |
| 扩展名 | 待定（`.jpg` / `.webp` / `.png`，需确认查找顺序）|
| 触发 | 扫描/刷新本地艺术时调用，写入 DB（`url` 列）|

**分片是硬需求**：clouddrive2 FUSE 上，单目录十万项会让 `Files.list` 慢到不可用；
lrr 侧已有分片先例。

### 7.5 仍待你确认

1. **外部封面根的绝对路径** = ？（如 `/opt/clouddrive2/115open/covers`）
2. **封面文件是否分片、分片规则** = ？（`aa/xxxx.jpg` 前 2 位？还是别的）
3. **扩展名与查找顺序** = ？（`.jpg` 优先？多扩展名都试？）

### 7.6 与「懒生成」的关系

- `KOMGA_THUMBNAIL_MODE = lazy` 仍然保留：**关掉入库时自动抽页生成**（那是唯一读 cbz 的热点）。
- 封面优先来自本 Provider（外部根）；无外部封面时才考虑按需生成或占位。
- 两条路径互补，不冲突。

---

## 8. 未决问题（更新）

1. 7.5 的三个参数（路径 / 分片 / 扩展名）——**写 Provider 前必须给**。
2. 阅读器按需生成入口是否保留？还是完全依赖外部封面？
3. legacy `komga-webui` 是否还维护？（不维护跳过前端改动）
