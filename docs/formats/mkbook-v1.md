# MKBook 书籍格式 v1

MKread 的标准书籍格式。目标：

1. **导入零歧义**：章节、卷、标题、正文都是显式声明的，不靠猜测（TXT 要靠正则猜章节，EPUB 要剥 HTML）。
2. **朗读友好**：正文是干净的纯文本，可附带本书专用的读音表（人名、地名、多音字）。
3. **可增量更新**：每本书有全局稳定的 `id` 和递增的 `revision`，每章有稳定的 `id` 和 `sha256`。云端更新时只下载变化的章节，阅读进度按章节 `id` 保留。

有两种形态：

| 形态 | 扩展名 | 用途 |
|---|---|---|
| 发布包 | `.mkbook` | App 导入、云端分发。ZIP 容器。 |
| 写作稿 | `.mktxt` | 人工编写/整理。单个 UTF-8 文本文件，用 `tools/mkbook` 打包成 `.mkbook`。 |

---

## 1. 发布包 `.mkbook`

`.mkbook` 是一个 ZIP 文件：

```
mimetype                    必须是第一个条目，不压缩（STORED），内容固定为
                            application/vnd.mkread.book+zip
mkbook.json                 清单（必需）
cover.jpg | cover.png | cover.webp   封面（可选）
chapters/<章节id>.txt        每章一个文件（必需）
pronunciation.json          本书读音表（可选）
```

不允许出现其他路径；路径只能用 `/` 分隔，不能含 `..`、绝对路径或反斜杠。

### 1.1 清单 `mkbook.json`

```json
{
  "format": "mkbook",
  "formatVersion": 1,
  "id": "yexing-zhe",
  "revision": 3,
  "updatedAt": "2026-10-02T10:00:00Z",
  "metadata": {
    "title": "夜行者",
    "author": "某某",
    "language": "zh-CN",
    "description": "简介，可多行。",
    "tags": ["悬疑", "都市"],
    "status": "ongoing",
    "cover": "cover.jpg"
  },
  "chapters": [
    {
      "id": "c0001",
      "title": "第1章 夜访",
      "volume": "第一卷 起始",
      "path": "chapters/c0001.txt",
      "chars": 3021,
      "sha256": "9f2c…（正文文件 UTF-8 字节的 SHA-256，小写十六进制）"
    }
  ],
  "narration": {
    "pronunciation": "pronunciation.json"
  }
}
```

| 字段 | 必需 | 规则 |
|---|---|---|
| `format` | 是 | 固定 `"mkbook"` |
| `formatVersion` | 是 | 固定 `1`。App 遇到更大的版本号会拒绝导入并提示升级 |
| `id` | 是 | 书的全局唯一标识，`^[a-z0-9][a-z0-9._-]{1,63}$`。**发布后不可更改**，云端靠它识别"同一本书的新版本" |
| `revision` | 是 | 正整数，每次发布新版本 +1 |
| `updatedAt` | 否 | ISO 8601 UTC 时间 |
| `metadata.title` | 是 | 1–200 字 |
| `metadata.author` | 否 | |
| `metadata.language` | 否 | BCP 47，默认 `zh-CN` |
| `metadata.description` | 否 | ≤ 5000 字 |
| `metadata.tags` | 否 | 字符串数组 |
| `metadata.status` | 否 | `ongoing`（连载中）或 `completed`（已完结） |
| `metadata.cover` | 否 | 包内封面路径；≤ 10 MB；JPEG/PNG/WebP |
| `chapters` | 是 | 1–20000 项，**数组顺序即阅读顺序** |
| `chapters[].id` | 是 | 本书内唯一，`^[A-Za-z0-9_-]{1,64}$`。**章节发布后 id 不变**（改标题、改正文都不换 id），这样更新后阅读进度和朗读缓存能对上 |
| `chapters[].title` | 是 | 1–200 字，不要重复写进正文 |
| `chapters[].volume` | 否 | 所属卷名；相邻章节卷名相同即同一卷 |
| `chapters[].path` | 是 | `chapters/<id>.txt` |
| `chapters[].chars` | 是 | 正文 Unicode 码点数 |
| `chapters[].sha256` | 是 | 正文文件字节的 SHA-256 |
| `narration.pronunciation` | 否 | 包内读音表路径 |

### 1.2 章节正文 `chapters/<id>.txt`

为了让排版和朗读都稳定，正文必须是"规范化纯文本"：

- UTF-8 编码，**无 BOM**；Unicode NFC 规范化。
- 换行只用 `\n`（LF）。
- **一行一段**；段首不要缩进空格或全角空格（缩进由 App 排版决定）。
- 不留空行，文件末尾不留空行。
- 不包含章节标题（标题在清单里）。
- 场景分隔行写 `※※※`（单独一行）。朗读时会被跳过，不会读出符号。
- 单章 ≤ 500 万字。
- 不含控制字符（`\t` 除外，但不建议用）。

数字、单位、英文照原样写即可（如 `2024年`、`65kg`），朗读引擎会统一转换成中文读法。

### 1.3 读音表 `pronunciation.json`（可选）

给本书专有的人名、地名、多音字指定读法，格式与 App 内置读音表相同：

```json
{
  "schemaVersion": 1,
  "overrides": [
    { "source": "单于", "replacement": "缠于", "match": "literal" },
    { "source": "MKread", "replacement": "M K read", "match": "whole-token" }
  ]
}
```

- `literal`：任意位置精确匹配替换。
- `whole-token`：只在前后不是字母/数字时替换（用于英文缩写）。
- `replacement` 写成"读起来对"的同音字或空格分开的字母。

---

## 2. 写作稿 `.mktxt`

方便人工编辑的单文件格式，打包工具会把它转成 `.mkbook`。

```text
---
id: yexing-zhe
title: 夜行者
author: 某某
tags: 悬疑, 都市
status: ongoing
description: 一句话简介
cover: cover.jpg
---

# 第一卷 起始

## 第1章 夜访 {#c0001}

夜色渐深，林默推开那扇吱呀作响的木门。
屋里只点着一盏油灯。

※※※

老人抬起头，缓缓说道："你终于来了。"

## 第2章 旧事 {#c0002}

……
```

规则：

- 文件开头 `---` 之间是元数据（`键: 值`，每行一个）。`id` 和 `title` 必填；`tags` 用逗号分隔；`cover` 是相对于 `.mktxt` 文件的图片路径。
- `# 标题`：卷。之后的章节都属于这一卷，直到下一个 `#`。
- `## 标题`：章。标题末尾可以写 `{#章节id}` 固定章节 id；不写的话打包工具按顺序生成 `c0001`、`c0002`……
  - **已经发布过的书，建议固定 id**，或者打包时用 `--previous 旧版.mkbook`，工具会按标题把旧 id 沿用过来。
- 正文：空行会被忽略，每个非空行是一段；行首行尾空白会被去掉。
- 如果整篇没有 `##` 标题，工具会按常见的"第X章/第X回/序章/楔子/番外"自动识别章节（和 App 导入 TXT 的规则一致）。
- 同目录下的 `<文件名>.pronunciation.json` 会自动作为本书读音表打包进去。

---

## 3. 打包工具 `tools/mkbook`

纯 Python 3 标准库，无需安装依赖。

```bash
# .mktxt / .txt / .epub → .mkbook
python3 tools/mkbook/mkbook.py build 夜行者.mktxt -o 夜行者.mkbook

# 发布新版本：沿用旧版章节 id，revision 自动 +1
python3 tools/mkbook/mkbook.py build 夜行者.mktxt --previous 夜行者.mkbook -o 夜行者-r2.mkbook

# 检查一个 .mkbook 是否合规
python3 tools/mkbook/mkbook.py validate 夜行者.mkbook

# 查看信息 / 对比两个版本改了哪些章节
python3 tools/mkbook/mkbook.py info 夜行者.mkbook
python3 tools/mkbook/mkbook.py diff 夜行者.mkbook 夜行者-r2.mkbook
```

从 `.txt` / `.epub` 构建时需要用 `--id` 指定书的 id（`--title`、`--author` 可选，默认取文件信息）。

---

## 4. App 的处理方式

- 导入时按 `mimetype` 条目识别 `.mkbook`（与扩展名无关），逐项校验清单、路径、哈希和字数，任何一项不符都拒绝导入并提示原因。
- 同一 `id` 的书再次导入时：`revision` 更高则原地更新，按章节 `id` 保留阅读进度；`revision` 相同或更低则视为重复。
- 云端书库下发的就是 `.mkbook`，与本地导入走同一套校验。
