# ![app icon](./.github/readme-images/app-icon.png) Komga（个人独立分支）

> **这是一个独立分支，不是上游 Komga 的补丁集。**
>
> 基于 [gotson/komga](https://github.com/gotson/komga) 分叉，专为**网盘挂载场景**
> （115 网盘 / CloudDrive2 FUSE）改造，与上游各自演进。
> 上游的社区渠道（Discord、OpenCollective 赞助、Weblate 翻译）**与本分支无关**，
> 相关问题请勿打扰上游维护者。

Komga 是一个漫画、日漫、BD、杂志和电子书媒体服务器。

## 本分支相对上游的改动

| 改造 | 说明 |
|------|------|
| **SHA-1 相对路径哈希** | 文件身份改为 `SHA1(相对路径)`，零 I/O，与 LANraragi 逐字节互认 |
| **divina ZIP 零 I/O 枚举** | 去掉逐条目 `getInputStream`，改为按条目名判类型 + 中央目录读尺寸 |
| **懒缩略图模式** | `KOMGA_THUMBNAIL_MODE=lazy` 关闭入库时自动抽页生成 |
| **外部 ComicInfo.xml 导入** | 支持读取书本同级目录的 `ComicInfo.xml`（library 级开关） |

完整设计与验证记录见 [`docs/FORK.md`](./docs/FORK.md)。

## 使用场景

- 库根：`/opt/clouddrive2/115open/comic`（115 网盘经 CloudDrive2 挂载）
- 部署：Dell MX Linux，裸 jar 运行，端口 25600
- 与 LANraragi 挂载同一份数据，两边文件身份互通

## Features

- Browse libraries, series and books via a responsive web UI that works on desktop, tablets and phones
- Organize your library with collections and read lists
- Edit metadata for your series and books
- Import embedded metadata automatically
- Webreader with multiple reading modes
- Manage multiple users, with per-library access control, age restrictions, and labels restrictions
- Offers a REST API, many community tools and scripts can interact with Komga
- OPDS v1 and v2 support
- Kobo Sync with your Kobo eReader
- KOReader Sync
- Download book files, whole series, or read lists
- Duplicate files detection
- Duplicate pages detection and removal
- Import books from outside your libraries directly into your series folder
- Import ComicRack `cbl` read lists

## Installation

本分支为**裸 jar 部署**，不走上游官方安装渠道。构建与运行步骤见 [`docs/PLAN.md`](./docs/PLAN.md)。

## Documentation

本分支自己的文档在 [`docs/`](./docs) 目录：

- [`docs/FORK.md`](./docs/FORK.md) —— fork 改动与设计
- [`docs/PLAN.md`](./docs/PLAN.md) —— 重做总纲与阶段计划

上游官方文档（komga.org）描述的是上游版本，与本分支的改动不完全一致，仅供参考。

## Develop in Komga

Check the [development guidelines](./DEVELOPING.md).

## Powered by

[![Jetbrains_logo](./.github/readme-images/jetbrains.svg)](https://www.jetbrains.com/?from=Komga)

Thanks to [JetBrains](https://www.jetbrains.com/?from=Komga) for providing the development environment that helps us develop Komga.

[![Chromatic logo](https://user-images.githubusercontent.com/321738/84662277-e3db4f80-af1b-11ea-88f5-91d67a5e59f6.png)](https://www.chromatic.com)

Thanks to [Chromatic](https://www.chromatic.com/) for providing the visual testing platform that helps us review UI changes and catch visual regressions.

## Credits

The Komga icon is based on an icon made by [Freepik](https://www.freepik.com/home) from www.flaticon.com
