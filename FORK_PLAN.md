# Komga Fork Plan — 全部本地改造

> 上游：`origin/master` @ `c7d353a7`（分叉基点）
> 本地：`kssku/komga`（remote `fork`）
> 目标：让 Komga 在 115 网盘（CloudDrive2 FUSE 挂载）上可用 —— 零 I/O 扫描、
> 与 LANraragi 哈希互认、外部元数据可导入。
> 使用场景：Dell `mx`，库根 `/opt/clouddrive2/115open/comic`，端口 25600。

---

## 0. 改造总览

本 fork 相对上游共 **7 个提交**，归为四项功能改造 + 一项杂项：

| # | 主题 | 提交 | 分支 | 状态 |
|---|------|------|------|------|
| 1 | SHA-1 相对路径哈希（与 LANraragi 互认） | `2b1e76fa` + `f346b30d` | `feat/sha1-relpath-hash` | ✅ 已推 fork |
| 2 | divina ZIP 零 I/O 条目枚举 | `a10e7aad` | `feat/sha1-relpath-hash` | ✅ 已推 fork |
| 3 | 懒缩略图（远程/FUSE 模式） | `dd058334` | `feat/sha1-relpath-hash` | ✅ 已推 fork |
| 4 | 外部 ComicInfo.xml 导入 | `12b56af6` + `20efaccd` | `feat/library-external-comicinfo-xml` | ✅ 已推 fork，PR 未开 |
| — | 忽略本地测试实例数据目录 | `57731e11` | 同上 | ✅ 已推 fork |

> 分节索引：§1–§9 是**改造 1 + 2** 的完整设计与验证；改造 3 见 §10；
> 改造 4 见 §11；待办与上游同步清单见 §12。

---

## 1. 背景与动机

Komga 与 LANraragi 挂载**同一份 115 网盘数据**，但两边的文件身份体系互不相通：

| | Komga | LRR |
|---|---|---|
| 文件身份 | `BOOK.FILE_HASH` = XXH3-128(文件内容) | `id` = SHA-1(相对路径) |
| 算法 | XXH3-128（32 hex） | SHA-1（40 hex） |
| 输入 | 文件字节 | `wnacg/<分片>/<文件>.cbz` |
| 代价 | 每次扫描读全文件（FUSE 上极慢） | 零 I/O |

**核心问题**：Komga 在 CD2/115 挂载上扫描时，`computeHash(path)` 会打开每个文件读内容 —— 15 万本书 = 15 万次 FUSE 读，扫库不可用。

**解法**：改为对**相对路径**算 SHA-1。零 I/O，且与 LRR 的 id 逐字节一致。

---

## 2. 已验证的哈希公式

### 2.1 LRR 侧（实证）

```
id = SHA1( UTF-8( 路径去掉 content 根 ) )
```

LRR 源码 `lib/LANraragi/Utils/Database.pm:633` `compute_id`：

```perl
my $root = LANraragi::Model::Config::get_userdir();   # /home/koyomi/lanraragi/content
my $rel  = File::Spec->abs2rel( $file, $root );
my $ctx  = Digest::SHA->new(1);
$ctx->add( Encode::encode( 'utf8', $rel ) );
return $ctx->hexdigest;
```

### 2.2 实证证据（双边 1849 / 1849 = 100%）

数据源：LRR `appendonly.aof.5.incr.aof` 里的 `HSET LRR_FILEMAP <绝对路径> <id>` 命令。

**这是本方案的核心证据 —— 不是推断，是用生产数据双边对拍的结果。**

```
LRR 侧   SHA1(路径相对 content 根)          命中 1849 / 1849
Komga 侧 SHA1(路径相对 Library.root 的父目录) 命中 1849 / 1849
失败                                       0
```

Komga 侧用的是**数据库里的真实路径**（`BOOK.URL`），模拟 `Hasher.computePathHash(path, libraryRoot)` 的完整逻辑（含 `.normalize()` 与 `Path.relativize` 语义）。

**这证明「互认」在数学上成立**：同一个文件，两边算出**逐字节相同**的哈希。

> ⚠️ 早期一次 `3/3 FAIL` 是**验证脚本自身的 bug**（把 `wnacg` 段重复拼接了一次），不是 Kotlin 逻辑问题。修正脚本后，同一批样例 3/3 通过，进而全量 1849/1849 通过。

样例：

```
路径      /home/koyomi/lanraragi/content/wnacg/300001-350000/340203.cbz
相对路径   wnacg/300001-350000/340203.cbz
LRR id    2983fddb8fd86b5bcafaaaeac51ae683ff7a5f8e
公式算    2983fddb8fd86b5bcafaaaeac51ae683ff7a5f8e  ✅
```

**注意**：相对路径**包含 `wnacg/` 前缀**。因为 LRR 的 content 根是 `/home/koyomi/lanraragi/content`，而 `wnacg` 是挂载进 content 的子目录。

### 2.3 Komga 侧基准推导（关键）

```
Komga LIBRARY.ROOT = file:/opt/clouddrive2/115open/comic/wnacg/
Komga BOOK.URL     = file:/opt/clouddrive2/115open/comic/wnacg/1-50000/10000.cbz

相对 Library.root          → "1-50000/10000.cbz"           ❌ 缺 wnacg/
相对 Library.root 的父目录  → "wnacg/1-50000/10000.cbz"    ✅ 命中
```

**⚠️ 实现时必须取 `Library.root` 的父目录作基准，不能直接用它本身。**

### 2.4 交叉验证（1848 / 1848 = 100%）

在 Komga `database.sqlite` 里查找 LRR 记录的同一批文件：

```
Komga 库中同时存在、可对拍的书   1848
与 LRR id 完全一致               1848
不一致                           0
```

样例：

```
Komga ID    0RPK09CTKRX3D
Komga URL   file:/opt/clouddrive2/115open/comic/wnacg/300001-350000/340203.cbz
相对路径     wnacg/300001-350000/340203.cbz
LRR id      2983fddb8fd86b5bcafaaaeac51ae683ff7a5f8e
公式算      2983fddb8fd86b5bcafaaaeac51ae683ff7a5f8e  ✅
```

**结论：互认在数学上已成立。**

---

## 3. 四类哈希的处置决定

`Hasher` 被三类用途共用，换算法会全部波及：

| # | 用途 | 调用点 | 决定 | 理由 |
|---|---|---|---|---|
| ① | API key 脱敏 | `HeaderApiKeyAuthenticationConverter.kt:29`<br>`UriRegexApiKeyAuthenticationConverter.kt:30` | **SHA-1 + 迁移重建** | 换算法必然波及；需重算已存 key |
| ② | `BOOK.FILE_HASH` | `BookLifecycle.kt:113`<br>`LibraryContentLifecycle.kt:175,303,375` | **SHA-1 + 相对路径** | 与 LRR 对齐，零 I/O |
| ③ | `MEDIA_PAGE.FILE_HASH` | `BookAnalyzer.kt:427` | **SHA-1**（输入仍是图片字节） | 语义是内容身份，用于跨书去重 |
| ④ | `BOOK_FILE_HASH`（SyncPoint） | `KoreaderHasher` | **不动** | 须与 Kobo 设备端一致 |

### 3.1 ⚠️ 必须正视的冲突：② 的语义变更

**上游把 `BOOK.FILE_HASH` 当「内容身份」用**，这从两个调用点可见：

```kotlin
// LibraryContentLifecycle.kt:303 —— 用哈希判断新书与被删书是否为同一本
val match = deletedCandidates.find { (_, books) ->
  books.map { it.fileHash }.containsAll(newBooksWithHash.map { it.fileHash }) &&
  newBooksWithHash.map { it.fileHash }.containsAll(books.map { it.fileHash })
}

// LibraryContentLifecycle.kt:375 —— 用哈希匹配被删的书
val match = deletedCandidates.find { it.fileHash == bookWithHash.fileHash }
```

这是 `tryRestoreBooks`（回收站恢复）的核心逻辑：**文件换路径但内容不变 → 识别为同一本书**。

**改成相对路径哈希后，这个能力会退化**：

```
文件 /a/1.cbz 改名为 /a/2.cbz
  内容哈希：不变  → tryRestoreBooks 认得出 ✅
  路径哈希：变了  → tryRestoreBooks 认不出 ❌
```

**LRR 没有这个问题**（它的 id 就是路径身份，改名=新书），但 **Komga 有这个功能且必须保留**。

### 3.2 冲突的解法：双哈希并存

建议新增一列专用于「内容身份」，把两种语义拆开：

```sql
ALTER TABLE BOOK ADD COLUMN FILE_HASH_CONTENT varchar NOT NULL DEFAULT '';
```

| 列 | 语义 | 算法/输入 | 用途 |
|---|---|---|---|
| `FILE_HASH` | **路径身份** | SHA-1(相对路径) | 与 LRR 互认、扫描快速去重 |
| `FILE_HASH_CONTENT` | **内容身份** | SHA-1(文件字节) | `tryRestoreBooks` 恢复匹配 |

但注意：`FILE_HASH_CONTENT` 需要读文件内容 —— **在 FUSE 上依然是慢操作**。

**折中方案**（推荐）：

- `FILE_HASH` = SHA-1(相对路径) —— 主哈希，零 I/O
- `tryRestoreBooks` 匹配改用 **`(fileSize, fileName)` 组合** —— 不读内容，且足够区分（上游 `BookPage.restoreHashFrom` 已在用这个思路）

这样既拿到零 I/O，又保留恢复能力，代价是极端情况下（同名同大小不同内容）可能误判。

---

## 4. 实施方案

> **实施状态：已完成，编译通过（`./gradlew :komga:compileKotlin` → exit 0），哈希公式经 1849 条生产数据双边验证。**
>
> 下面保留的是设计意图；实际落地的两处关键细节见 §4.0。

### 4.0 实施中确认的两个细节

**① URL → Path 需要显式 import**

```kotlin
import kotlin.io.path.toPath   // 必须，否则 url.toURI().toPath() 报 Unresolved reference
```

这不是 workaround —— 上游 `Book.kt:26` 和 `Library.kt:60` 就是同样的写法：

```kotlin
val path: Path by lazy { this.url.toURI().toPath() }
```

漏掉这个 import 会导致编译失败（已实际踩到并由编译检查捕获）。

**② `root.parent` 规则只写在一处**

`computePathHash(path: Path, libraryRoot: URL)` 这个重载**独占**「取父目录」的逻辑，4 处调用点只传 `(book.path, library.root)`。这样最脆弱的一行不会被复制到四个地方 —— 上游 `Hasher.kt` 一旦被覆盖，只需重新检查这一个方法。

---

### 阶段 1：`Hasher` 加 SHA-1 + 路径哈希方法

**文件**：`komga/src/main/kotlin/org/gotson/komga/infrastructure/hash/Hasher.kt`

```kotlin
import java.security.MessageDigest

/**
 * SHA-1 of a UTF-8 path relative to the library root's PARENT directory.
 *
 * Aligned with LANraragi's compute_id (lib/LANraragi/Utils/Database.pm) so both
 * systems produce byte-identical hashes for the same file.
 *
 * UTF-8 is explicit: non-ASCII filenames (the library has many) must hash
 * consistently regardless of the JVM default charset.
 */
fun computePathHash(relativePath: String): String =
  MessageDigest.getInstance("SHA-1")
    .digest(relativePath.toByteArray(Charsets.UTF_8))
    .toHexString()
```

**同时**把 `computeHash(stream)` 的算法从 XXH3-128 换成 SHA-1（用于 ①③）。

### 阶段 2：抽出相对路径计算

需要一个能拿到 `Library.root` 的辅助方法：

```kotlin
fun Book.relativeHashPath(libraryRoot: String): String {
  // libraryRoot = file:/opt/clouddrive2/115open/comic/wnacg/
  // 取其父目录作基准，得到 wnacg/...
}
```

**4 处调用点**都要改：

- `BookLifecycle.kt:113`
- `LibraryContentLifecycle.kt:175`
- `LibraryContentLifecycle.kt:303`
- `LibraryContentLifecycle.kt:375`

### 阶段 3：Flyway 迁移

参照上游 `V20230626150454__xxhash128.sql` 的先例（它直接清空哈希）：

```sql
-- 换算法后旧哈希失效，清空触发重算
update BOOK set FILE_HASH = '';
update MEDIA_PAGE set FILE_HASH = '';
```

注意：`MEDIA_PAGE` 只需重算**采样页**（`pageHashing=3` → 前后各 3 页），不是全部 788 万行。

### 阶段 4：构建 + 对拍验证

用第 2 节的 1848 条基准做回归。

**构建注意**：

```bash
JAVA_TOOL_OPTIONS="-Xmx4g" ./gradlew :komga:build -x test --no-daemon
```

---

## 5. 回退方案

| 层级 | 手段 |
|---|---|
| 代码 | `git checkout master` —— 分支独立，零污染 |
| 旧补丁 | `komga-notes/legacy-inject/patches/bookanalyzer-cache-shortcircuit-20260924.patch` |
| 数据库 | `config/database.sqlite.bak-pre-inject-20260922-150936`（91 MB，注入前快照） |
| 哈希 | 迁移只清空不转换，重算即可恢复 |

---

## 6. 上游同步清单

merge `gotson/komga` 上游时必须逐行比对的文件：

| 文件 | 风险 |
|---|---|
| `infrastructure/hash/Hasher.kt` | 算法被覆盖 → 哈希全变 |
| `domain/service/BookAnalyzer.kt` | 页面哈希逻辑 |
| `domain/service/BookLifecycle.kt` | `hashAndPersist` 调用点 |
| `domain/service/LibraryContentLifecycle.kt` | 4 处调用点 + tryRestoreBooks |
| `src/flyway/resources/db/migration/sqlite/` | 新增迁移可能冲突 |

**特别标注**：`Hasher.computeHash(stream)` 若被上游改回 XXH3，所有哈希立即失效。

---

## 7. 实现后已确认的事项

（原「待确认」三项，均已在实现阶段查证。）

### 7.1 API key —— **不受影响，无需迁移**

`Hasher` 换算法**不会**使现有 API key 失效。证据：

```
KomgaUserLifecycle.kt:130   userRepository.insert(plainTextKey.copy(key = tokenEncoder.encode(plainTextKey.key)))
PasswordEncoderConfiguration.kt:15   TokenEncoder { Sha512DigestUtils.shaHex(rawPassword) }
```

存储的 `USER_API_KEY.API_KEY` 列来自 **`TokenEncoder`（SHA-512 hex）**，与 `Hasher` 无关。

但有一处**会变**：`HeaderApiKeyAuthenticationConverter.kt:29` 与 `UriRegexApiKeyAuthenticationConverter.kt:30` 用 `hasher.computeHash(it)` 生成 `maskedToken`，作为认证时的 **username**（`ApiKeyAuthenticationToken.unauthenticated(maskedToken, hashedToken)`）。这个值会随算法改变 —— 但它**不落库**，只是同一次请求内的临时标识，因此不影响已签发的 key。

**结论：API key 迁移 = 不需要。**

### 7.2 `tryRestoreBooks` 的匹配策略 —— 见 §3.2 的权衡

路径哈希使「文件改路径、内容不变」无法被识别为同一本书（哈希变了）。这是本改造**已知且接受**的能力损失，与 LRR 行为一致（LRR 的 id 也是路径身份，改名即新书）。

### 7.3 `MEDIA_PAGE.FILE_HASH` 换 SHA-1 —— 已决定换

理由：统一哈希体系，避免同库内混用两种算法。代价可控 —— 它是**采样哈希**（`KomgaProperties.pageHashing = 3`，前后各 3 页），不是全部 788 万页。迁移清空后由 `PageHashLifecycle` 懒重算。

### 7.4 测试 mock 必须同步更新（**已踩到**）

改 `Hasher` 的调用形态后，`LibraryContentLifecycleTest.kt` 里 **35 处 mock** 全部失效：

```
改前： every { mockHasher.computeHash(any<Path>()) } returns "..."
改后： every { mockHasher.computePathHash(any(), any()) } returns "..."

报错： io.mockk.MockKException: no answer found for
       Hasher.computePathHash(/book1, file:/default)
       among the configured answers: (Hasher.computeHash(slotCapture<Path>()))
```

**22 个测试失败，全部是这一个原因** —— MockK 严格模式找不到匹配的 mock 答案就抛异常。

修复：35 处全部改成 `computePathHash(any(), any())`，其中 6 处 `capture(slot)` 的形式改为
`computePathHash(capture(slot), any())`（只捕获第一个参数，第二个用 `any()`）。

> 教训：**改方法签名 = 必须同步改测试 mock**。`compileKotlin` 只编译 main 源集，
> 不会发现测试里的 mock 失配 —— 只有跑测试才会暴露。

### 7.5 `SYNC_POINT_BOOK.BOOK_FILE_HASH` —— 明确不动

该列须与 Kobo 设备端一致，改算法会破坏所有 Kobo 同步点。迁移脚本中**特意排除**。

---

## 8. 第二项改造：`ZipExtractor` 零 I/O 条目枚举

### 8.1 问题（CD2/115 FUSE 场景下最大单点开销）

上游 `ZipExtractor.getEntries` 对**每个条目**都：

```kotlin
zip.getInputStream(entry).buffered().use { stream ->   // ← 每条目一次 open
  val mediaType = contentDetector.detectMediaType(stream)  // 读魔数
  val dimension = if (analyzeDimensions && isImage) imageAnalyzer.getDimension(stream)
  ...
}
```

在 CloudDrive2 挂载的 115 网盘上，**每次 `open` 是数百毫秒的网络往返**。
一本 200 页的 CBZ = **200 次 open**，仅为了构建页面列表。

### 8.2 改动

| 文件 | 改动 |
|---|---|
| `ContentDetector.kt` | 新增 `detectMediaTypeByName(fileName)` —— Tika `mimeRepository.getMimeType()` **纯内存查表，零 I/O** |
| `ZipExtractor.kt` | 去掉 `getInputStream`，`mediaType` 取自**条目名**；`dimension` 恒为 `null`；构造参数去掉 `imageAnalyzer` |

**`entry.size` 本就不需要读取** —— 它来自 ZIP 中央目录，`setPath()` 时已加载。

### 8.3 ⚠️ 三项必须正视的代价

| # | 代价 | 实情评估 |
|---|---|---|
| 1 | **`dimension` 恒为 null** | 阅读器无法预知页面尺寸。**已确认可接受** —— 本库 7,899,110 页全部是同一格式 |
| 2 | **按文件名判类型** | 名实不符的文件会被误判。**本库不构成风险** —— 全部 `.jpg` → `image/jpeg`，与读魔数结果一致 |
| 3 | **加密 ZIP 会静默变 READY** | **已修，见 §8.4** |

**为什么方案 2 在本库成立**：实测 `MEDIA_PAGE.FILE_NAME` 的扩展名分布 = **`jpg` × 7,899,110（100%）**，
`BOOK_PAGE` 的 `mediaType` 分布 = **`image/jpeg` × 7,899,110（100%）**。
**库是均质的**，文件名与内容类型不存在分歧。换到异构库（含 `.avif`/`.jxl`/无扩展名）**不能照搬此方案**。

> Tika 的 `getMimeType(name)` 实测覆盖 `.jpg/.png/.webp/.gif/.avif/.jxl/.heic/.tiff` 等，
> 未知扩展名返回 `application/octet-stream`（**不返回 null**）。

### 8.4 加密 ZIP 的处理（本次暴露并修复的真实缺陷）

**问题**：加密 ZIP 的条目名与尺寸在中央目录里是**明文可见**的。上游靠「解密失败 → 判不出图片 → 无页面 → `ERROR`」发现加密包；
改成按名判断后，这条路径消失 → 加密包被标为 **`READY`**，直到**阅读时**才失败 ——
**把「导入时明确报错」降级成了「运行时静默失败」**。

**修法**：中央目录的 `generalPurposeBit.usesEncryption()`（flag bit 0）**零 I/O** 可读：

```kotlin
if (entries.any { it.generalPurposeBit.usesEncryption() }) {
  throw IllegalStateException("Encrypted ZIP archives are not supported")
}
```

**为什么抛 `IllegalStateException` 而非 `MediaUnsupportedException`**：
后者会被 `BookAnalyzer` 映射为 `UNSUPPORTED`（RAR 加密走的就是这条路，`ERR_1002`），
但 `BookAnalyzerTest` 对加密 ZIP 期望的是 **`ERROR`**。抛通用异常 → 落到
`catch (ex: Exception)` → `ERROR` + `ERR_1008`，**与上游状态一致**。

### 8.5 验证

| 项 | 结果 |
|---|---|
| `compileKotlin` | ✅ exit 0 |
| `BookAnalyzerTest` + `divina.*` | ✅ **BUILD SUCCESSFUL**（含加密 ZIP 用例） |
| `ZipExtractorTest` | ✅ 已同步（构造参数、`dimension` 断言改为 `null`） |

---

## 9. 2026-10 目录结构变更的影响

### 9.1 新结构（实测）

```
/opt/clouddrive2/115open/
├── content/                    ← 漫画本体（新增层级）
│   ├── wnacg/
│   │   ├── oneshots/           ← 新增：单本
│   │   │   ├── 1-50000/
│   │   │   └── 100001-150000/
│   │   └── series/             ← 新增：系列
│   │       ├── 1-50000/
│   │       └── ...
│   ├── pika/
│   └── jmacg/
├── thumb/                      ← 封面（旧，将重建）
│   └── {hash[0:2]}/{hash}.jpg  ← 按哈希前 2 位分片
└── pika/
```

**与旧结构（`FORK_PLAN` §2 记录）的差异**：

| | 旧 | 新 |
|---|---|---|
| 库根 | `.../comic/wnacg/` | `.../content/wnacg/` |
| 层 | `wnacg/{分片}/{id}.cbz` | `wnacg/{oneshots,series}/{分片}/{id}.cbz` |
| 来源 | 仅 wnacg | wnacg + pika + jmacg 并列 |

### 9.2 对哈希算法的影响：**逻辑无问题，代码无需改**

`Hasher.computePathHash(path, libraryRoot)` 的实现是**结构无关**的：

```kotlin
val base = libraryRoot.toURI().toPath().normalize().parent ?: root
val relative = base.relativize(path.normalize()).toString()
return SHA1(UTF-8(relative))
```

任何目录结构都能算，只要「基准目录」与「文件位置」在同一台机器上一致。

### 9.3 后果：新旧哈希不通用（**已接受**）

结构变了 → 相对路径变了 → **哈希值必然改变**。

- 决策（2026-10）：**以新逻辑为准**，不追求与旧哈希兼容。
- 迁移方式：沿用 §4 阶段 3 的**清空重算**（`update BOOK set FILE_HASH=''`）。
- §2 的 1849 条对拍基准**对应旧结构，已失效**；如需与 lrr 重新互认，须用新结构重新对拍。

### 9.4 待确认（不影响算法成立，只影响具体哈希值）

1. **Komga library root 指向哪一层？**（`content/wnacg/`？`content/`？）
   —— 决定「父目录基准」，从而决定具体哈希值。
2. **`oneshots` / `series` 是否属于文件身份的一部分？**
   —— 若属于（即参与哈希），则它们必须稳定；若只是物理归类，则换层会改哈希。

> 这两项**不影响「逻辑有无问题」的结论**（逻辑没问题），只影响「算出来的具体值是什么」。

---

## 10. 第三项改造：懒缩略图模式（远程 / FUSE 挂载）

> 提交 `dd058334` `feat(thumbnail): lazy thumbnail mode for remote/FUSE mounts`
> 详细设计另见 `THUMBNAIL_LAZY_DESIGN.md`（251 行，本节的来源）。

### 10.1 问题

上游在**入库扫描时**就抽页生成缩略图 —— 这是扫描路径上唯一**读 cbz 本体**的热点。
在 CD2/115 上，一本书一次缩略图 = 一次网络读，扫库时叠加成不可接受的开销。

### 10.2 改动

| 文件 | 改动 |
|---|---|
| `KomgaProperties.kt` | 新增 `KOMGA_THUMBNAIL_MODE`（+21 行），`lazy` 关闭入库时自动生成 |
| `BookLifecycle.kt` | 生成时机改为按需（+33 行） |
| `LibraryContentLifecycle.kt` | 跟随调整（+10 行） |
| `Hasher.kt` | 配套微调（+5 行） |
| `tools/make-lazy-testdata.sh` | 造测试数据（+66 行） |
| `tools/run-test-instance.sh` | 起本地测试实例（+22 行） |

### 10.3 外部封面 Provider（设计已定，实现待做）

浏览时读**独立封面目录**的小图，cbz 本体仍只在阅读时读。封面路径规则：

```
{外部封面根}/{book.fileHash}.{ext}
```

`book.fileHash` = `SHA1(相对路径)`，40 位小写十六进制（与 LRR `compute_id` 一致，见 §7.1）。

**时序陷阱（必须处理）**：`fileHash` 由 `hashAndPersist` 异步补（`BookLifecycle.kt:113-116`），
受 library `hashFiles` 开关控制。Provider 遇到空 `fileHash` 必须返回空，**不得**拼出 `/.jpg` 这类无效路径。

**为何不用上游 SIDECAR**：`LocalArtworkProvider.kt:36-47` 只支持「书同目录、同名前缀」，
不递归、不跨目录；本场景封面集中在独立目录树，需**新增 Provider**（拟名 `ExternalCoverArtworkProvider`）。

**分片是硬需求**：FUSE 上单目录十万项会让 `Files.list` 慢到不可用，须按 `{hash 前 2 位}/{hash}.jpg` 分片。

**仍待确认**：外部封面根绝对路径、分片规则、扩展名查找顺序。

---

## 11. 第四项改造：外部 ComicInfo.xml 导入

> 提交 `12b56af6`（功能）+ `20efaccd`（测试）
> 分支 `feat/library-external-comicinfo-xml`

### 11.1 动机

部分下载源把元数据放在 **cbz 同级目录**的 `ComicInfo.xml`，而非内嵌在压缩包里。
上游只读内嵌的，导致这类书元数据丢失。

### 11.2 改动链路（完整贯穿各层）

| 层 | 文件 | 改动 |
|---|---|---|
| 迁移 | `V20261009120000__library_comicinfo_external_xml.sql` | 新增 library 开关列 |
| 领域模型 | `Library.kt` | +1 字段 |
| 持久化 | `LibraryDao.kt` | +3（读写该列） |
| Provider 抽象 | `SeriesMetadataFromBookProvider.kt` | +1（接口参数） |
| **核心实现** | `ComicInfoProvider.kt` | +25/-2：读同级外部 xml，优先于内嵌 |
| 兄弟实现 | `EpubMetadataProvider.kt` | +1（跟随接口） |
| API | `LibraryController.kt` | +2 |
| DTO | `LibraryCreationDto.kt` / `LibraryDto.kt` / `LibraryUpdateDto.kt` | 各 +1/+2/+1 |

### 11.3 测试（+73 行，2 个用例）

- 开开关 → 用外部文件（`external series`）
- 关开关 → 忽略外部文件，仍用内嵌

**踩坑记录（已沉淀进知识库）**：mockk 按**重载签名**分别 stub ——
外部路径走 `readValue(InputStream, …)`，而既有测试只 stub 了 `readValue(ByteArray, …)`，
未 stub 的重载静默返回 `null`，报错却指向 `!!` 的 NPE。修法：外部路径这条测试
改用**真 `XmlMapper()`**（它本就该验证真实解析），不补 mock。

### 11.4 验证

`./gradlew :komga:test --rerun-tasks` → **BUILD SUCCESSFUL in 1m 10s**，31 测试全绿
（新增 3 个，既有 28 个未破）。

---

## 12. 待办与上游同步

### 12.1 待办

| # | 事项 | 状态 |
|---|---|---|
| 1 | 开 PR：`feat/library-external-comicinfo-xml` → `fork` | ⬜ 未开 |
| 2 | 外部封面 Provider 实现（§10.3，须先定三个参数） | ⬜ 未做 |
| 3 | `.test-komga/lucene/` 13 个二进制残留在 `dd058334` 历史中 | ⬜ 待决策 |
| 4 | §9.4 两个待确认项（library root 层级、`oneshots/series` 是否入哈希） | ⬜ 待确认 |
| 5 | `THUMBNAIL_LAZY_DESIGN.md` 可考虑并入 `docs/` | ⬜ 可选 |

> 关于 #3：`57731e11` 把 `.test-komga/` 加进 `.gitignore` 并**删除**了文件，
> 但删除在新增的**后一条**提交 —— 那 13 个二进制**永久留在 git 历史**里，
> 会撑大 clone、rebase 时反复冲突。分支已推 fork 且 PR 未开，是重写历史代价最小的窗口。

### 12.2 上游同步清单

| 改造 | 是否适合上游 | 说明 |
|---|---|---|
| SHA-1 相对路径哈希 | ❌ 本 fork 专属 | 语义变更（见 §3.1），上游视 `FILE_HASH` 为内容身份 |
| divina 零 I/O 枚举 | ⚠️ 视情况 | 均质库成立（§8.3），异构库不可照搬 |
| 懒缩略图模式 | ✅ 通用 | 远程/FUSE 场景普遍受益 |
| 外部 ComicInfo.xml | ✅ 通用 | 独立开关，不破坏既有行为 |

---

## 附录：验证脚本

对拍数据与脚本见 `komga-notes/legacy-inject/`：

- `/tmp/lrr-pairs.txt` —— 1849 条 LRR 真实 `id → 路径`（**对应旧结构**）
- 提取方法：解析 `appendonly.aof.5.incr.aof` 的 RESP token 流

新结构下的候选哈希脚本：`/tmp/hash_candidates.sh`（本次会话产出）
