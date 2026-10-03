# 任务说明：把小说整理成 MKBook 并发布到 MKread 云书库

交给 Codex。本文是自包含的操作规范：照着做，产出的书 App 能直接导入，也能发布到云书库让所有设备同步。完整的格式定义见 `docs/formats/mkbook-v1.md`，打包工具是 `tools/mkbook/mkbook.py`（Python 3，无第三方依赖）。

## 1. 产出物

每本书交付：

| 文件 | 说明 |
|---|---|
| `<id>.mktxt` | 整理好的写作稿（UTF-8 纯文本），是这本书的「源码」，以后改错字、加章节都改它 |
| `<id>.mkbook` | 由工具打包出的发布包，用于导入 App 或上传云书库 |
| `cover.jpg`（可选） | 封面，与 `.mktxt` 放在同一目录 |
| `<id>.pronunciation.json`（可选） | 读音表，见第 6 节 |

建议每本书一个目录：`books/<id>/`，`.mktxt`、封面、读音表和每个版本的 `.mkbook` 都放在里面，**旧版 `.mkbook` 不要删**，发新版本时要用它。

## 2. 写作稿 `.mktxt` 格式

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

### 2.1 元数据（文件开头两行 `---` 之间，`键: 值` 每行一个）

| 键 | 必填 | 规则 |
|---|---|---|
| `id` | 是 | 全局唯一，只用小写字母、数字、`.`、`_`、`-`，2–64 个字符，以字母或数字开头（正则 `^[a-z0-9][a-z0-9._-]{1,63}$`）。建议用书名拼音，如 `yexing-zhe`。**发布后永远不改。** |
| `title` | 是 | 书名，1–200 字 |
| `author` | 否 | 作者 |
| `tags` | 否 | 逗号分隔，如 `悬疑, 都市` |
| `status` | 否 | `ongoing`（连载中）或 `completed`（已完结） |
| `description` | 否 | 简介，≤ 5000 字，写在一行里 |
| `cover` | 否 | 封面路径（相对 `.mktxt`），JPEG / PNG / WebP，≤ 10 MB |

### 2.2 卷和章

- `# 卷名`：开始一卷，之后的章都属于这一卷，直到下一个 `#`。没有分卷的书可以不写。
- `## 章标题 {#章节id}`：开始一章。
  - 标题 1–200 字，**只写在这一行**，正文里不要再重复标题。
  - `{#c0001}` 是章节 id：本书内唯一，只用字母、数字、`_`、`-`，≤ 64 字符。**强烈建议写上，并且发布后不再改**（改标题、改正文都不换 id）。App 按章节 id 保存阅读进度和朗读缓存，id 变了读者的进度就丢了。
  - 不写 id 时工具按顺序生成 `c0001`、`c0002`……，这样在中间插入新章会让后面的 id 全部错位。所以已发布的书要么固定写 id，要么打包时用 `--previous`（见第 4 节）。
- 每本书 1–20000 章，单章 ≤ 500 万字。

### 2.3 正文

- **一行一段**。段首不要加空格或全角空格（缩进由 App 排版决定）；行首行尾空白会被去掉。
- 空行会被忽略，空不空行都可以。
- 场景分隔：单独一行写 `※※※`。朗读时会被跳过，不会读出符号。
- 编码必须是 **UTF-8**。来源是 GBK、GB18030 的先转码，不要出现乱码或 `�`。
- 不要包含控制字符。

### 2.4 整理时要清理掉的内容

来源文本（尤其是网上下载的 TXT）常见这些问题，整理时去掉：

- 网站水印、广告、「本章未完」「请收藏」「手机阅读」这类行，以及推广链接。
- 作者的「求票」「请假条」「感言」等非正文段落（单独成章的可以保留为番外，或直接删掉）。
- 正文里重复出现的章节标题行。
- 同一章被分页切成「（1/2）」「（2/2）」的，合并成一章。
- 全角、半角混乱的引号可以保持原样，但不要出现 HTML 标签、`&nbsp;` 之类的残留。

## 3. 从现有文件转换

| 来源 | 做法 |
|---|---|
| 规整的 TXT | 可以直接打包：`build 书名.txt --id <id> --title <书名> --author <作者>`。工具按「第X章 / 第X节 / 第X回 / 第X卷 / 序章 / 楔子 / 后记 / 尾声 / 番外」自动识别章节（和 App 导入 TXT 的规则相同，标题行 ≤ 80 字）。**识别后务必用 `info` 检查目录**，不对就改成 `.mktxt` 手动加 `##`。 |
| 不规整的 TXT | 先整理成 `.mktxt`（推荐），再打包。 |
| EPUB | `build 书名.epub --id <id>`，工具按 h1–h3 拆章。同样要用 `info` 检查目录；目录乱的话导出文本整理成 `.mktxt`。 |

## 4. 打包、检查、出新版本

在 novelread 仓库根目录执行：

```bash
# 首次打包（revision 默认为 1）
python3 tools/mkbook/mkbook.py build books/yexing-zhe/yexing-zhe.mktxt -o books/yexing-zhe/yexing-zhe-r1.mkbook

# 检查是否合规（App 和云书库用同一套校验）
python3 tools/mkbook/mkbook.py validate books/yexing-zhe/yexing-zhe-r1.mkbook

# 查看书名、章节数、字数和目录，人工核对
python3 tools/mkbook/mkbook.py info books/yexing-zhe/yexing-zhe-r1.mkbook

# 发新版本（改了错字、加了章节）：带上上一版，沿用章节 id，revision 自动 +1
python3 tools/mkbook/mkbook.py build books/yexing-zhe/yexing-zhe.mktxt \
  --previous books/yexing-zhe/yexing-zhe-r1.mkbook -o books/yexing-zhe/yexing-zhe-r2.mkbook

# 对比两个版本改了哪些章节
python3 tools/mkbook/mkbook.py diff books/yexing-zhe/yexing-zhe-r1.mkbook books/yexing-zhe/yexing-zhe-r2.mkbook
```

版本规则：同一个 `id` 的书，新版本的 `revision` 必须更大。App 收到更高的 revision 会原地更新，按章节 id 保留阅读进度；revision 相同或更低则视为重复，不会更新。

包的限制：整个 `.mkbook` ≤ 200 MB，解压后 ≤ 400 MB。

## 5. 发布

- **只给自己读**：把 `.mkbook` 传到手机，在 App 里导入。
- **发到云书库（所有登录用户都能同步）**：在 Server Hub（`https://hub.mkauth.sbs`）左侧的「MKread」→「书籍」→「上传书籍」，选择 `.mkbook`，填写原因（例如「首发」「r2：修正错别字」）。
  - 上传时会做和 App 相同的校验，不合规会提示具体原因。
  - App 联网时每 6 小时自动同步，也可以在 App「更多选项 → 云端书库」里手动同步。新书出现在「云书库」书架。
- **下架**：在同一页面选择书籍下架。已经下载的设备会保留这本书，但不再收到更新；新设备看不到它。

## 6. 读音表（可选）

生僻人名、地名、多音字读错时，在 `.mktxt` 同目录放 `<文件名>.pronunciation.json`，打包时会自动放进包里：

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
- `whole-token`：只在前后不是字母或数字时替换（用于英文缩写）。
- `replacement` 写成「读起来对」的同音字，或用空格分开的字母。

**注意**：当前 App 0.3.0 还**不会使用**书里的读音表，分卷名也暂时不显示（这两项排在后续版本）。现在照规范放进去就行，App 升级后自动生效，不用重新打包。

## 7. 每本书的验收清单

- [ ] `id` 符合规则，且与云书库里已有的其他书不重复；已发布的书 `id` 没有改动。
- [ ] `validate` 通过。
- [ ] `info` 显示的章节数、第一章和最后一章标题，与原书目录一致；没有被切碎或合并错的章。
- [ ] 抽查开头、中间、结尾各 2 章：没有乱码、水印、广告、重复标题，段落没有被拼成一大段。
- [ ] 已发布过的书：用 `--previous` 打包，`diff` 的结果只包含真正改动的章节，章节 id 没有整体错位。
- [ ] 封面（如有）能正常显示，≤ 10 MB。
- [ ] 上传后，在 Server Hub「MKread → 书籍」里能看到这本书，状态为「上架」，章节数、字数正确。

## 8. 不要做

- 不要修改 `tools/mkbook/mkbook.py`、`docs/formats/mkbook-v1.md` 或 App 代码。格式规则要改，先在本仓库提需求（写进 `docs/HANDOFF.md`），由 Claude 修改，保证工具和 App 的校验一致。
- 不要直接在数据中心里改 `mkread_library` 的 `books` 表来「上架」「下架」或改书名。要通过「MKread」页面或重新上传新版本完成，否则 App 的同步游标不会更新，设备收不到变化。
- 不要把来源不明、没有授权的书发布到云书库。
