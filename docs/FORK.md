# Komga Fork — 1.0

> 本仓库是 [gotson/komga](https://github.com/gotson/komga) 的个人 fork。
> 目的：让 Komga 在 **115 网盘（CloudDrive2 FUSE 挂载）** 上可用。
>
> 本文档只记录**代码与磁盘能证实的事实**。任何推测都明确标注为「待确认」。

---

## 1. 部署环境

| 项 | 值 |
|---|---|
| 节点 | Dell MX（hostname `mx`）|
| 挂载 | `/opt/clouddrive2/115open`（CloudFS, fuse）|
| 漫画根 | `/opt/clouddrive2/115open/content/` |
| 端口 | 25600 |

### 1.1 实际目录结构（2026-10-09 实测）

```
/opt/clouddrive2/115open/
├── content/                    ← 漫画本体
│   ├── jmacg/
│   ├── pika/
│   └── wnacg/
│       ├── oneshots/
│       │   └── 1-50000/13748.cbz
│       └── series/
└── （电视剧/电影/动漫/backup/bilibili 等无关目录）
```

> **注意**：旧文档记载的库根 `.../comic/wnacg/` **在磁盘上已不存在**。
> `.../comic` 这一层从未存在过。

---

## 2. 四项改造

分支 `master` 相对上游 `c7d353a7`（分叉基点）共 **9 个提交**。

### 2.1 SHA-1 相对路径哈希

**提交**：`2b1e76fa` + `f346b30d`

**问题**：上游用 XXH3-128 哈希**文件内容**。在 FUSE 挂载上，每本书都要读一遍全文，
全库扫描不可用。

**解法**：改为哈希**相对路径**，零 I/O。

```kotlin
// Hasher.kt:75-84
fun computePathHash(path: Path, libraryRoot: URL): String {
  val root = libraryRoot.toURI().toPath().normalize()
  val base = root.parent ?: root          // ← 取父目录，只此一处
  val relative = base.relativize(path.normalize()).toString()
  return computePathHash(relative)        // SHA1(UTF-8(relative))
}
```

**4 处调用点**均只传 `(book.path, library.root)`：

- `BookLifecycle.kt:128`
- `LibraryContentLifecycle.kt:176`
- `LibraryContentLifecycle.kt:306`
- `LibraryContentLifecycle.kt:383`

**与 LANraragi 互认的条件**：

| | 哈希输入 |
|---|---|
| LRR | `wnacg/<分片>/<文件>.cbz`（相对其 content 根）|
| Komga | `base.relativize(path)`，`base = libraryRoot.parent` |

两者相等的**前提是库根指向 `content/wnacg/`**。见 §4。

**迁移**：`V20260924120000__sha1_relpath_hash.sql` —— 清空 `BOOK.FILE_HASH`、
`MEDIA_PAGE.FILE_HASH`、`PAGE_HASH`、`PAGE_HASH_THUMBNAIL`；保留 
`SYNC_POINT_BOOK.BOOK_FILE_HASH`（须与 Kobo 设备同步）。

### 2.2 divina ZIP 零 I/O 条目枚举

**提交**：`a10e7aad`

**问题**：构建页面列表时，每个 ZIP 条目都要 `getInputStream` 读图片头取尺寸 ——
一本 200 页的 CBZ = 200 次网络往返。

**解法**：

- `ContentDetector.detectMediaTypeByName(fileName)` —— Tika 内存查表，零 I/O
- `ZipExtractor` 不再读流；`dimension` 恒为 `null`

**代价（已接受）**：

1. 阅读器无法预知页面尺寸 —— 本库 7,899,110 页全部同一格式，可接受
2. 按文件名判类型 —— 本库 `jpg` × 100%，文件名与内容一致，可接受
3. 加密 ZIP 会静默变 READY —— **已修**：检查中央目录 
   `generalPurposeBit.usesEncryption()`，抛 `IllegalStateException`，
   落到 `catch (ex: Exception)` → `ERROR` + `ERR_1008`，与上游一致

> 换到异构库（含 .avif/.jxl/无扩展名）**不能照搬此方案**。

### 2.3 懒缩略图模式

**提交**：`dd058334`

**问题**：入库扫描时抽取首页生成缩略图 —— 扫描路径上唯一读 cbz 本体的热点。

**解法**：`KomgaProperties.thumbnailMode`，**默认 `LAZY`**。

```kotlin
// KomgaProperties.kt:46
var thumbnailMode: ThumbnailMode = ThumbnailMode.LAZY

enum class ThumbnailMode { LAZY, AUTO }
```

- `LAZY`：入库不生成，首次请求时按需生成（`BookLifecycle.kt:242`）
- `AUTO`：上游行为

**环境变量**：`KOMGA_THUMBNAIL_MODE`

> ⚠️ **升级上游时的静默风险**：此 fork **默认行为已改**。若上游覆盖 
> `KomgaProperties.kt` 的这一行，默认会回到 `AUTO`，扫库立刻退化成逐本读 cbz。
> 合流时必须逐行核对此文件。

### 2.4 外部 ComicInfo.xml 导入

**提交**：`12b56af6` + `20efaccd`

**动机**：部分下载源把元数据放 cbz **同级**的 `ComicInfo.xml`，而非内嵌。

**开关**：`Library.importComicInfoExternalXml`，默认 `false`。
迁移：`V20261009120000__library_comicinfo_external_xml.sql`。

**核心逻辑**（`ComicInfoProvider.kt:202-207`）：

```kotlin
private fun readExternalComicInfo(book: BookWithMedia): ComicInfo? {
  val external = book.book.path.resolveSibling(COMIC_INFO)
  if (!Files.isRegularFile(external)) return null
  return Files.newInputStream(external).use { mapper.readValue(it, ComicInfo::class.java) }
}
```

外部文件优先于内嵌。测试：31 个全绿（新增 3 个）。

---

## 3. 构建与部署

```bash
# 编译
JAVA_TOOL_OPTIONS="-Xmx4g" ./gradlew :komga:compileKotlin --no-daemon

# 测试
./gradlew :komga:test --rerun-tasks

# 打包
JAVA_TOOL_OPTIONS="-Xmx4g" ./gradlew :komga:build -x test --no-daemon
```

### 3.1 ⚠️ 版本号曾兼任构建开关（1.0.0 出包踩坑）

**现象**：切到 `version=1.0.0` 后，`:komga:kspKotlin` 以 `PROCESSING_ERROR` 失败，
报 89 处上游 `@Deprecated` 为 error（`Deprecated code should be removed`），
分布在 `ReferentialV1Controller.kt`、`ReferentialDao.kt`、`SeriesController.kt` 等 10 个文件。
**均为上游代码，非本 fork 改动。**

**根因**：上游 `komga/build.gradle.kts` 把注解处理器的开关写成了版本号判断：

```kotlin
if (version.toString().endsWith(".0.0")) {
  ksp("com.github.gotson.bestbefore:bestbefore-processor-kotlin:0.2.0")
}
```

上游版本号一直是 `1.x.y`，条件**恒假**，processor 从未加载。
本 fork 改成 `1.0.0` 后条件**首次为真**，processor 在 Kotlin 2.4 下
把上游那些刻意保留的 `@Deprecated` 全判为 error。

**反直觉之处**：改版本号本身没错，错在版本号被当成构建开关用。
不先看 `build.gradle.kts` 就追 KSP/编译参数，会一路白追。

**修复**（提交 `4fcec1ec`）：判据与版本号解耦，默认关闭（与上游 1.x 实际行为一致）：

```kotlin
if (providers.gradleProperty("bestbefore").orNull == "true") {
  ksp("com.github.gotson.bestbefore:bestbefore-processor-kotlin:0.2.0")
}
```

需要该 processor 时显式传 `-Pbestbefore=true`。

**同轮修复**：上游从 kapt 迁到 ksp 时漏给 `kspKotlin` 声明对 `generateTasksJooq`
的依赖，Gradle 9 把隐式依赖升级为硬失败；已在 `tasks.whenTaskAdded` 中补 `dependsOn`。

**验证**：`./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar :komga-tray:jar`
→ `BUILD SUCCESSFUL in 43s`，产物 `komga-1.0.0.jar` (117 MB) / `komga-tray-1.0.0.jar` (58 KB)。

---

## 4. ⚠️ 待确认：库根层级

**这是当前唯一悬空的关键项。**

```kotlin
val base = libraryRoot.parent
```

代入两种可能：

| 库根指向 | `base` | 相对路径 | 与 LRR 对得上？|
|---|---|---|---|
| `.../content/wnacg/` | `.../content/` | `wnacg/oneshots/1-50000/13748.cbz` | ✅ |
| `.../content/` | `.../115open/` | `content/wnacg/oneshots/1-50000/13748.cbz` | ❌ 多一层 |

**结论**：库根**必须**指向 `.../content/wnacg/`。

**但**：

1. 生产实例当前**未运行**（25600 无监听），`LIBRARY.ROOT` 无从读取
2. `.test-komga/` 里的库根是 `test/pika-format/`，**是测试实例，不是生产库**
3. `oneshots`/`series` 这层**已进入相对路径**，LRR 侧是否同构待查

**旧基准已失效**：早期文档记载的 1849 条对拍，对应 `comic/wnacg/` 旧结构，
该结构在磁盘上已不存在。**新结构下必须重做对拍。**

---

## 5. 上游同步清单

merge 上游时**必须逐行比对**：

| 文件 | 风险 |
|---|---|
| `infrastructure/hash/Hasher.kt` | 算法被覆盖 → 哈希全变 |
| `infrastructure/configuration/KomgaProperties.kt` | `thumbnailMode` 默认值被改回 AUTO |
| `domain/service/BookLifecycle.kt` | 调用点 + 懒缩略图判断 |
| `domain/service/LibraryContentLifecycle.kt` | 3 处调用点 |
| `domain/service/ComicInfoProvider.kt` | 外部 xml 逻辑 |
| `src/flyway/resources/db/migration/sqlite/` | 迁移编号冲突 |

---

## 6. 已知遗留

| # | 事项 | 状态 |
|---|---|---|
| 1 | 库根层级待确认（§4）| ⬜ 未结 |
| 2 | 新旧结构哈希基准需重做 | ⬜ 未做 |
| 3 | 13 个 lucene 二进制在 `dd058334` 历史中 | ✅ 已决策：接受，不重写历史 |
| 4 | 外部封面 Provider（见下）| ⬜ 未做 |
| 5 | PR 未开 | ⬜ 未开 |

### 6.1 外部封面 Provider（设计已定，未实现）

浏览时读独立封面目录，cbz 只在阅读时读。路径规则：

```
{外部封面根}/{book.fileHash}.{ext}
```

**时序陷阱**：`fileHash` 由 `hashAndPersist` 异步补，受 library `hashFiles` 开关控制。
Provider 遇空 `fileHash` **必须返回空**，不得拼出 `/.jpg`。

**须先定三个参数**：封面根绝对路径、分片规则、扩展名查找顺序。

**分片是硬需求**：FUSE 上单目录十万项会让 `Files.list` 慢到不可用。

---

## 7. 回退

| 层 | 手段 |
|---|---|
| 代码 | `git checkout master`（各改造分支独立）|
| 数据库 | `config/database.sqlite.bak-*`（迁移前快照）|
| 哈希 | 迁移只清空不转换，重扫即恢复 |

---

## 8. 版本

**1.0** —— 2026-10-09

- master @ `480d82ee`
- 4 项改造全部落地，编译通过，31 测试全绿
- 库根层级待确认（§4）

