#!/usr/bin/env python3
"""MKBook packaging tool: build, validate, inspect and diff .mkbook packages.

Spec: docs/formats/mkbook-v1.md. Standard library only so it runs anywhere,
including the cloud server, which imports this module for upload validation.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import html.parser
import json
import posixpath
import re
import sys
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

MIMETYPE = "application/vnd.mkread.book+zip"
FORMAT = "mkbook"
FORMAT_VERSION = 1
MANIFEST = "mkbook.json"

BOOK_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{1,63}$")
CHAPTER_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
MAX_CHAPTERS = 20_000
MAX_CHAPTER_CHARS = 5_000_000
MAX_COVER_BYTES = 10 * 1024 * 1024
MAX_PACKAGE_BYTES = 200 * 1024 * 1024
MAX_EXPANDED_BYTES = 400 * 1024 * 1024
COVER_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
SCENE_BREAK = "※※※"

# Same heading rule the app uses for TXT imports (TxtChapterDetector.kt).
TXT_HEADING = re.compile(
    r"^\s*(?:(?:第\s*[零〇一二两三四五六七八九十百千万0-9]{1,12}\s*[章节卷回部篇])"
    r"|(?:[卷部篇]\s*[零〇一二两三四五六七八九十百千万0-9]{1,12})|(?:序章|楔子|后记|尾声|番外.{0,20}))"
    r"(?:[\s:：._-]+.{0,60})?\s*$",
    re.IGNORECASE,
)
SCENE_BREAK_LINE = re.compile(r"^[\s*＊※☆★◇◆·•\-—=~～#]{3,}$")


class MkBookError(Exception):
    """Raised when a source or package violates the MKBook spec."""


@dataclass
class Chapter:
    title: str
    text: str
    id: str | None = None
    volume: str | None = None


@dataclass
class Book:
    id: str
    title: str
    chapters: list[Chapter]
    author: str | None = None
    language: str = "zh-CN"
    description: str | None = None
    tags: list[str] = field(default_factory=list)
    status: str | None = None
    cover: tuple[str, bytes] | None = None  # (extension, bytes)
    pronunciation: bytes | None = None
    revision: int = 1


# ---------------------------------------------------------------- text rules

def normalize_text(raw: str) -> str:
    """Applies the chapter body rules: NFC, LF, one trimmed paragraph per line."""
    text = unicodedata.normalize("NFC", raw.replace("﻿", ""))
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace(" ", "\n").replace(" ", "\n")
    lines = []
    for line in text.split("\n"):
        line = "".join(ch for ch in line if ch == "\t" or unicodedata.category(ch)[0] != "C")
        line = line.strip(" \t　 ")
        if not line:
            continue
        if SCENE_BREAK_LINE.match(line) and not any(ch.isalnum() for ch in line):
            line = SCENE_BREAK
        lines.append(line)
    return "\n".join(lines)


def check_text(text: str, where: str) -> None:
    if not text:
        raise MkBookError(f"{where}: 正文为空")
    if text != normalize_text(text):
        raise MkBookError(f"{where}: 正文不符合规范（需 NFC、LF、一行一段、无空行、无首尾空白）")
    if len(text) > MAX_CHAPTER_CHARS:
        raise MkBookError(f"{where}: 超过 {MAX_CHAPTER_CHARS} 字")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- readers

def decode_text_file(data: bytes) -> str:
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8")
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16")
    for encoding in ("utf-8", "gb18030", "big5"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise MkBookError("无法识别文本编码（支持 UTF-8 / UTF-16 / GB18030 / Big5）")


def split_detected_chapters(text: str) -> list[Chapter]:
    lines = text.split("\n")
    heads = [i for i, line in enumerate(lines) if len(line) <= 80 and TXT_HEADING.match(line)]
    if not heads:
        return [Chapter(title="正文", text=normalize_text(text))]
    chapters = []
    preface = normalize_text("\n".join(lines[: heads[0]]))
    if preface:
        chapters.append(Chapter(title="前言", text=preface))
    for n, start in enumerate(heads):
        end = heads[n + 1] if n + 1 < len(heads) else len(lines)
        body = normalize_text("\n".join(lines[start + 1 : end]))
        if body:
            chapters.append(Chapter(title=lines[start].strip(), text=body))
    return chapters


HEADING_ID = re.compile(r"\s*\{#([A-Za-z0-9_-]{1,64})\}\s*$")


def read_mktxt(path: Path) -> Book:
    text = decode_text_file(path.read_bytes()).replace("\r\n", "\n").replace("\r", "\n")
    meta: dict[str, str] = {}
    if text.startswith("---\n"):
        end = text.find("\n---", 4)
        if end < 0:
            raise MkBookError("元数据区缺少结束的 ---")
        for line_no, line in enumerate(text[4:end].split("\n"), start=2):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, sep, value = line.partition(":")
            if not sep:
                raise MkBookError(f"第 {line_no} 行：元数据应写成 键: 值")
            meta[key.strip().lower()] = value.strip()
        text = text[end + 4 :].lstrip("\n")

    chapters: list[Chapter] = []
    volume = None
    current: Chapter | None = None
    body: list[str] = []
    has_markup = any(line.startswith("## ") for line in text.split("\n"))

    def flush() -> None:
        if current is not None:
            current.text = normalize_text("\n".join(body))
            if current.text:
                chapters.append(current)
            else:
                print(f"警告：章节《{current.title}》没有正文，已跳过", file=sys.stderr)

    if has_markup:
        for line in text.split("\n"):
            if line.startswith("## "):
                flush()
                title = line[3:].strip()
                match = HEADING_ID.search(title)
                chapter_id = match.group(1) if match else None
                if match:
                    title = title[: match.start()].strip()
                current, body = Chapter(title=title, text="", id=chapter_id, volume=volume), []
            elif line.startswith("# "):
                flush()
                current, body = None, []
                volume = line[2:].strip() or None
            elif current is not None:
                body.append(line)
            elif line.strip():
                current, body = Chapter(title="前言", text="", volume=volume), [line]
        flush()
    else:
        chapters = split_detected_chapters(text)

    book = Book(
        id=meta.get("id", ""),
        title=meta.get("title", path.stem),
        author=meta.get("author") or None,
        language=meta.get("language", "zh-CN"),
        description=meta.get("description") or None,
        tags=[t.strip() for t in re.split(r"[,，]", meta.get("tags", "")) if t.strip()],
        status=meta.get("status") or None,
        chapters=chapters,
    )
    if meta.get("cover"):
        cover = (path.parent / meta["cover"]).resolve()
        book.cover = (cover.suffix.lower(), cover.read_bytes())
    sidecar = path.with_suffix(".pronunciation.json")
    if sidecar.is_file():
        book.pronunciation = sidecar.read_bytes()
    return book


def read_txt(path: Path) -> Book:
    text = normalize_text_keep_lines(decode_text_file(path.read_bytes()))
    return Book(id="", title=path.stem, chapters=split_detected_chapters(text))


def normalize_text_keep_lines(raw: str) -> str:
    return unicodedata.normalize("NFC", raw.replace("﻿", "")).replace("\r\n", "\n").replace("\r", "\n")


class _XhtmlText(html.parser.HTMLParser):
    BLOCKS = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "section", "blockquote", "tr", "hr"}
    HEADINGS = {"h1", "h2", "h3"}
    SKIP = {"script", "style", "head", "title"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.heading: str | None = None
        self._in_heading = False
        self._heading_parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
        if tag in self.BLOCKS:
            self.parts.append("\n")
        if tag in self.HEADINGS and self.heading is None:
            self._in_heading = True

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        if tag in self.BLOCKS:
            self.parts.append("\n")
        if tag in self.HEADINGS and self._in_heading:
            self._in_heading = False
            self.heading = "".join(self._heading_parts).strip() or None

    def handle_data(self, data):
        if self._skip:
            return
        self.parts.append(data)
        if self._in_heading:
            self._heading_parts.append(data)


def read_epub(path: Path) -> Book:
    import xml.etree.ElementTree as ET

    with zipfile.ZipFile(path) as zf:
        container = ET.fromstring(zf.read("META-INF/container.xml"))
        opf_path = container.find(".//{*}rootfile").get("full-path")
        opf = ET.fromstring(zf.read(opf_path))
        base = posixpath.dirname(opf_path)
        manifest = {item.get("id"): item for item in opf.iter("{*}item")}
        title = (opf.findtext(".//{*}title") or path.stem).strip()
        author = (opf.findtext(".//{*}creator") or "").strip() or None
        chapters = []
        for ref in opf.iter("{*}itemref"):
            item = manifest.get(ref.get("idref"))
            if item is None or "html" not in (item.get("media-type") or ""):
                continue
            href = posixpath.normpath(posixpath.join(base, item.get("href")))
            parser = _XhtmlText()
            parser.feed(zf.read(href).decode("utf-8", errors="replace"))
            text = normalize_text("".join(parser.parts))
            heading = parser.heading
            if heading and text.startswith(heading):
                text = text[len(heading) :].lstrip("\n")
            if text:
                chapters.append(Chapter(title=heading or f"第{len(chapters) + 1}节", text=text))
        cover = None
        for item in manifest.values():
            props = item.get("properties") or ""
            if "cover-image" in props or (item.get("id") or "").lower() in {"cover", "cover-image"}:
                ext = Path(item.get("href")).suffix.lower()
                if ext in COVER_TYPES:
                    cover = (ext, zf.read(posixpath.normpath(posixpath.join(base, item.get("href")))))
                    break
    return Book(id="", title=title, author=author, chapters=chapters, cover=cover)


# ---------------------------------------------------------------- package I/O

def assign_chapter_ids(book: Book, previous: dict | None) -> None:
    """Keeps published chapter ids stable: explicit ids win, then ids reused by title, then new ids."""
    used = {c.id for c in book.chapters if c.id}
    reuse: dict[str, list[str]] = {}
    if previous:
        for entry in previous["chapters"]:
            if entry["id"] not in used:
                reuse.setdefault(entry["title"], []).append(entry["id"])
    taken = set(used) | {e["id"] for e in previous["chapters"]} if previous else set(used)
    counter = 0
    for chapter in book.chapters:
        if chapter.id:
            continue
        candidates = reuse.get(chapter.title)
        if candidates:
            chapter.id = candidates.pop(0)
            continue
        while True:
            counter += 1
            candidate = f"c{counter:04d}"
            if candidate not in taken:
                break
        chapter.id = candidate
        taken.add(candidate)


def write_package(book: Book, out: Path) -> dict:
    if not BOOK_ID.match(book.id or ""):
        raise MkBookError("书的 id 不合规：需匹配 ^[a-z0-9][a-z0-9._-]{1,63}$（用 --id 或 .mktxt 元数据指定）")
    if not book.chapters:
        raise MkBookError("没有任何章节正文")
    seen = set()
    entries = []
    files: list[tuple[str, bytes]] = []
    for chapter in book.chapters:
        if not CHAPTER_ID.match(chapter.id or "") or chapter.id in seen:
            raise MkBookError(f"章节 id 无效或重复：{chapter.id}")
        seen.add(chapter.id)
        check_text(chapter.text, f"章节《{chapter.title}》")
        data = chapter.text.encode("utf-8")
        path = f"chapters/{chapter.id}.txt"
        files.append((path, data))
        entry = {"id": chapter.id, "title": chapter.title[:200]}
        if chapter.volume:
            entry["volume"] = chapter.volume
        entry.update({"path": path, "chars": len(chapter.text), "sha256": sha256(data)})
        entries.append(entry)

    metadata: dict = {"title": book.title[:200], "language": book.language or "zh-CN"}
    if book.author:
        metadata["author"] = book.author
    if book.description:
        metadata["description"] = book.description[:5000]
    if book.tags:
        metadata["tags"] = book.tags
    if book.status:
        if book.status not in {"ongoing", "completed"}:
            raise MkBookError("status 只能是 ongoing 或 completed")
        metadata["status"] = book.status
    if book.cover:
        ext, data = book.cover
        if ext not in COVER_TYPES or len(data) > MAX_COVER_BYTES:
            raise MkBookError("封面需为 JPEG/PNG/WebP 且不超过 10MB")
        cover_path = "cover" + (".jpg" if ext == ".jpeg" else ext)
        metadata["cover"] = cover_path
        files.append((cover_path, data))

    manifest = {
        "format": FORMAT,
        "formatVersion": FORMAT_VERSION,
        "id": book.id,
        "revision": book.revision,
        "updatedAt": _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "metadata": metadata,
        "chapters": entries,
    }
    if book.pronunciation:
        validate_pronunciation(book.pronunciation)
        manifest["narration"] = {"pronunciation": "pronunciation.json"}
        files.append(("pronunciation.json", book.pronunciation))

    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + ".partial")
    with zipfile.ZipFile(tmp, "w") as zf:
        zf.writestr(zipfile.ZipInfo("mimetype"), MIMETYPE, compress_type=zipfile.ZIP_STORED)
        zf.writestr(MANIFEST, json.dumps(manifest, ensure_ascii=False, indent=2), compress_type=zipfile.ZIP_DEFLATED)
        for path, data in files:
            stored = zipfile.ZIP_STORED if path.startswith("cover") else zipfile.ZIP_DEFLATED
            zf.writestr(path, data, compress_type=stored)
    tmp.replace(out)
    validate_package(out)
    return manifest


def validate_pronunciation(data: bytes) -> None:
    try:
        doc = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MkBookError(f"读音表不是有效的 UTF-8 JSON：{error}") from None
    if doc.get("schemaVersion") != 1 or not isinstance(doc.get("overrides"), list):
        raise MkBookError("读音表需要 schemaVersion: 1 和 overrides 数组")
    for n, entry in enumerate(doc["overrides"]):
        if set(entry) != {"source", "replacement", "match"} or entry["match"] not in {"literal", "whole-token"}:
            raise MkBookError(f"读音表第 {n + 1} 项格式错误")


def validate_package(path: Path) -> dict:
    """Checks every rule in the spec; returns the manifest. Raises MkBookError on violations."""
    if path.stat().st_size > MAX_PACKAGE_BYTES:
        raise MkBookError("包超过 200MB")
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        raise MkBookError("不是有效的 ZIP 文件") from None
    with zf:
        infos = zf.infolist()
        if not infos or infos[0].filename != "mimetype" or infos[0].compress_type != zipfile.ZIP_STORED:
            raise MkBookError("第一个条目必须是不压缩的 mimetype")
        if zf.read("mimetype").decode("ascii", "replace") != MIMETYPE:
            raise MkBookError("mimetype 内容不对")
        names = set()
        expanded = 0
        for info in infos:
            name = info.filename
            if name in names:
                raise MkBookError(f"重复条目：{name}")
            names.add(name)
            if name.startswith("/") or "\\" in name or ".." in name.split("/") or name.endswith("/"):
                raise MkBookError(f"非法路径：{name}")
            expanded += info.file_size
        if expanded > MAX_EXPANDED_BYTES:
            raise MkBookError("解压后超过 400MB")
        try:
            manifest = json.loads(zf.read(MANIFEST).decode("utf-8"))
        except KeyError:
            raise MkBookError("缺少 mkbook.json") from None
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise MkBookError(f"mkbook.json 解析失败：{error}") from None

        if manifest.get("format") != FORMAT:
            raise MkBookError("format 必须是 mkbook")
        if manifest.get("formatVersion") != FORMAT_VERSION:
            raise MkBookError(f"不支持的 formatVersion：{manifest.get('formatVersion')}")
        if not isinstance(manifest.get("id"), str) or not BOOK_ID.match(manifest["id"]):
            raise MkBookError("id 不合规")
        revision = manifest.get("revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise MkBookError("revision 必须是正整数")
        meta = manifest.get("metadata")
        if not isinstance(meta, dict) or not isinstance(meta.get("title"), str) or not 1 <= len(meta["title"]) <= 200:
            raise MkBookError("metadata.title 必填且 1–200 字")
        if meta.get("status") not in (None, "ongoing", "completed"):
            raise MkBookError("metadata.status 只能是 ongoing / completed")
        allowed = {"mimetype", MANIFEST}
        cover = meta.get("cover")
        if cover is not None:
            if cover not in {"cover.jpg", "cover.png", "cover.webp"} or cover not in names:
                raise MkBookError("封面路径无效")
            if zf.getinfo(cover).file_size > MAX_COVER_BYTES:
                raise MkBookError("封面超过 10MB")
            allowed.add(cover)
        chapters = manifest.get("chapters")
        if not isinstance(chapters, list) or not 1 <= len(chapters) <= MAX_CHAPTERS:
            raise MkBookError("chapters 需为 1–20000 项")
        ids = set()
        for n, entry in enumerate(chapters, start=1):
            where = f"第 {n} 章"
            if not isinstance(entry, dict):
                raise MkBookError(f"{where}: 格式错误")
            cid = entry.get("id")
            if not isinstance(cid, str) or not CHAPTER_ID.match(cid) or cid in ids:
                raise MkBookError(f"{where}: id 无效或重复")
            ids.add(cid)
            title = entry.get("title")
            if not isinstance(title, str) or not 1 <= len(title) <= 200:
                raise MkBookError(f"{where}: 标题必填且 1–200 字")
            if entry.get("path") != f"chapters/{cid}.txt" or entry["path"] not in names:
                raise MkBookError(f"{where}: path 必须是 chapters/{cid}.txt 且存在")
            allowed.add(entry["path"])
            data = zf.read(entry["path"])
            if sha256(data) != entry.get("sha256"):
                raise MkBookError(f"{where}《{title}》: sha256 不匹配")
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                raise MkBookError(f"{where}《{title}》: 不是 UTF-8") from None
            check_text(text, f"{where}《{title}》")
            if entry.get("chars") != len(text):
                raise MkBookError(f"{where}《{title}》: chars 与正文不符")
        narration = manifest.get("narration") or {}
        if narration.get("pronunciation"):
            if narration["pronunciation"] != "pronunciation.json" or "pronunciation.json" not in names:
                raise MkBookError("读音表路径无效")
            validate_pronunciation(zf.read("pronunciation.json"))
            allowed.add("pronunciation.json")
        extra = names - allowed
        if extra:
            raise MkBookError(f"包含未声明的文件：{sorted(extra)[:5]}")
    return manifest


def read_manifest(path: Path) -> dict:
    with zipfile.ZipFile(path) as zf:
        return json.loads(zf.read(MANIFEST).decode("utf-8"))


def diff_manifests(old: dict, new: dict) -> dict:
    before = {c["id"]: c for c in old["chapters"]}
    after = {c["id"]: c for c in new["chapters"]}
    return {
        "added": [c["id"] for c in new["chapters"] if c["id"] not in before],
        "removed": [c["id"] for c in old["chapters"] if c["id"] not in after],
        "changed": [cid for cid, c in after.items() if cid in before and before[cid]["sha256"] != c["sha256"]],
        "retitled": [cid for cid, c in after.items() if cid in before and before[cid]["title"] != c["title"]],
        "reordered": [c["id"] for c in old["chapters"] if c["id"] in after] != [c["id"] for c in new["chapters"] if c["id"] in before],
    }


# ---------------------------------------------------------------- CLI

def cmd_build(args) -> None:
    source = Path(args.source)
    suffix = source.suffix.lower()
    if suffix == ".mktxt":
        book = read_mktxt(source)
    elif suffix == ".txt":
        book = read_txt(source)
    elif suffix == ".epub":
        book = read_epub(source)
    else:
        raise MkBookError("只支持 .mktxt / .txt / .epub")
    if args.id:
        book.id = args.id
    if args.title:
        book.title = args.title
    if args.author:
        book.author = args.author
    previous = None
    if args.previous:
        previous = validate_package(Path(args.previous))
        if previous["id"] != book.id:
            raise MkBookError(f"--previous 是另一本书（{previous['id']}）")
        book.revision = previous["revision"] + 1
    if args.revision:
        book.revision = args.revision
    assign_chapter_ids(book, previous)
    out = Path(args.output) if args.output else source.with_suffix(".mkbook")
    manifest = write_package(book, out)
    total = sum(c["chars"] for c in manifest["chapters"])
    print(f"已生成 {out}：《{manifest['metadata']['title']}》 r{manifest['revision']}，"
          f"{len(manifest['chapters'])} 章，{total} 字")
    if previous:
        print_diff(diff_manifests(previous, manifest))


def print_diff(d: dict) -> None:
    print(f"  新增 {len(d['added'])} 章，删除 {len(d['removed'])} 章，正文修改 {len(d['changed'])} 章，"
          f"改标题 {len(d['retitled'])} 章{'，章节顺序有变化' if d['reordered'] else ''}")


def cmd_validate(args) -> None:
    manifest = validate_package(Path(args.package))
    print(f"✓ 合规：{manifest['id']} r{manifest['revision']}《{manifest['metadata']['title']}》"
          f"{len(manifest['chapters'])} 章")


def cmd_info(args) -> None:
    manifest = validate_package(Path(args.package))
    meta = manifest["metadata"]
    print(f"id: {manifest['id']}  revision: {manifest['revision']}  updatedAt: {manifest.get('updatedAt', '-')}")
    print(f"书名: {meta['title']}  作者: {meta.get('author', '-')}  状态: {meta.get('status', '-')}")
    volume = object()
    for c in manifest["chapters"]:
        if c.get("volume") != volume:
            volume = c.get("volume")
            if volume:
                print(f"  [{volume}]")
        print(f"    {c['id']:>8}  {c['title']}  ({c['chars']} 字)")


def cmd_diff(args) -> None:
    old = validate_package(Path(args.old))
    new = validate_package(Path(args.new))
    if old["id"] != new["id"]:
        raise MkBookError("两个包不是同一本书")
    print(f"{old['id']}: r{old['revision']} → r{new['revision']}")
    d = diff_manifests(old, new)
    print_diff(d)
    for key in ("added", "removed", "changed", "retitled"):
        if d[key]:
            print(f"  {key}: {', '.join(d[key][:20])}{' …' if len(d[key]) > 20 else ''}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="mkbook", description="MKBook 打包工具（规范见 docs/formats/mkbook-v1.md）")
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build", help=".mktxt/.txt/.epub → .mkbook")
    b.add_argument("source")
    b.add_argument("-o", "--output")
    b.add_argument("--id", help="书的 id（.txt/.epub 必填）")
    b.add_argument("--title")
    b.add_argument("--author")
    b.add_argument("--previous", help="上一版 .mkbook：沿用章节 id，revision 自动 +1")
    b.add_argument("--revision", type=int)
    b.set_defaults(func=cmd_build)
    v = sub.add_parser("validate", help="检查 .mkbook 是否合规")
    v.add_argument("package")
    v.set_defaults(func=cmd_validate)
    i = sub.add_parser("info", help="查看 .mkbook 信息和目录")
    i.add_argument("package")
    i.set_defaults(func=cmd_info)
    d = sub.add_parser("diff", help="对比同一本书的两个版本")
    d.add_argument("old")
    d.add_argument("new")
    d.set_defaults(func=cmd_diff)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except MkBookError as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
