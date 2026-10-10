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
# Version 2 adds inline images (spec §1.4); books without images are still written as version 1.
IMAGES_FORMAT_VERSION = 2
SUPPORTED_FORMAT_VERSIONS = {FORMAT_VERSION, IMAGES_FORMAT_VERSION}
MANIFEST = "mkbook.json"

BOOK_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{1,63}$")
CHAPTER_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
MAX_CHAPTERS = 20_000
MAX_CHAPTER_CHARS = 5_000_000
MAX_COVER_BYTES = 10 * 1024 * 1024
MAX_PACKAGE_BYTES = 200 * 1024 * 1024
MAX_EXPANDED_BYTES = 400 * 1024 * 1024
COVER_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_IMAGES = 2000
IMAGE_PATH = re.compile(r"^images/[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.(?:jpg|png|webp)$")
# A whole chapter line `![caption](path)`; the caption may be empty but cannot contain "]".
IMAGE_LINE = re.compile(r"^!\[([^\]\n]{0,200})\]\(([^()\s]+)\)$")
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


def image_type(data: bytes) -> str | None:
    """File extension matching the image's actual content, or None if it is not JPEG/PNG/WebP."""
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ".webp"
    return None


def image_refs(text: str) -> list[tuple[str, str]]:
    """(caption, path) for every image line in a chapter body."""
    refs = []
    for line in text.split("\n"):
        match = IMAGE_LINE.match(line)
        if match:
            refs.append((match.group(1), match.group(2)))
    return refs


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
    # Package path (images/<name>) → bytes; chapter text refers to these paths.
    images: dict[str, bytes] = field(default_factory=dict)
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
        data = (path.parent / meta["cover"]).resolve().read_bytes()
        ext = image_type(data)
        if ext is None:
            raise MkBookError("封面需为 JPEG/PNG/WebP")
        book.cover = (ext, data)
    sidecar = path.with_suffix(".pronunciation.json")
    if sidecar.is_file():
        book.pronunciation = sidecar.read_bytes()
    embed_images(book, lambda ref: _read_local_image(path.parent, ref))
    return book


def _read_local_image(base: Path, ref: str) -> tuple[str, bytes]:
    image = (base / ref).resolve()
    if not image.is_file():
        raise MkBookError(f"找不到插图文件：{ref}")
    return image.name, image.read_bytes()


def _package_image_name(original: str, ext: str, taken: set[str]) -> str:
    stem = re.sub(r"[^A-Za-z0-9._-]+", "-", Path(original).stem).strip("-._")[:80] or "image"
    if not stem[0].isalnum():
        stem = "img" + stem
    name, n = f"{stem}{ext}", 1
    while f"images/{name}" in taken:
        n += 1
        name = f"{stem}-{n}{ext}"
    return f"images/{name}"


def embed_images(book: Book, load) -> None:
    """Rewrites every image line to a package path, loading each referenced file once.

    `load(ref)` returns (original file name, bytes) for a reference found in the text.
    """
    by_ref: dict[str, str] = {}
    by_hash: dict[str, str] = {}
    for chapter in book.chapters:
        lines = chapter.text.split("\n")
        for n, line in enumerate(lines):
            match = IMAGE_LINE.match(line)
            if not match:
                continue
            caption, ref = match.groups()
            if ref not in by_ref:
                original, data = load(ref)
                ext = image_type(data)
                if ext is None:
                    raise MkBookError(f"插图不是 JPEG/PNG/WebP：{ref}")
                if len(data) > MAX_IMAGE_BYTES:
                    raise MkBookError(f"插图超过 10MB：{ref}")
                digest = sha256(data)
                if digest not in by_hash:
                    target = _package_image_name(original, ext, set(book.images))
                    book.images[target] = data
                    by_hash[digest] = target
                by_ref[ref] = by_hash[digest]
            lines[n] = f"![{caption}]({by_ref[ref]})"
        chapter.text = "\n".join(lines)
    if len(book.images) > MAX_IMAGES:
        raise MkBookError(f"插图超过 {MAX_IMAGES} 张")


def read_txt(path: Path) -> Book:
    text = normalize_text_keep_lines(decode_text_file(path.read_bytes()))
    return Book(id="", title=path.stem, chapters=split_detected_chapters(text))


def normalize_text_keep_lines(raw: str) -> str:
    return unicodedata.normalize("NFC", raw.replace("﻿", "")).replace("\r\n", "\n").replace("\r", "\n")


EPUB_IMAGE_PREFIX = "epub:"


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
        if tag in {"img", "image"} and not self._skip:
            values = dict(attrs)
            src = values.get("src") or values.get("xlink:href") or values.get("href")
            if src and not src.startswith(("data:", "http:", "https:")):
                alt = re.sub(r"[\]\n]", "", values.get("alt") or "").strip()[:200]
                self.parts.append(f"\n![{alt}]({EPUB_IMAGE_PREFIX}{src})\n")
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
        manifest = {item.get("id"): item for item in opf.iterfind(".//{*}item")}
        title = (opf.findtext(".//{*}title") or path.stem).strip()
        author = (opf.findtext(".//{*}creator") or "").strip() or None
        chapters = []
        for ref in opf.iterfind(".//{*}itemref"):
            item = manifest.get(ref.get("idref"))
            if item is None or "html" not in (item.get("media-type") or ""):
                continue
            href = posixpath.normpath(posixpath.join(base, item.get("href")))
            parser = _XhtmlText()
            parser.feed(zf.read(href).decode("utf-8", errors="replace"))
            # Image sources are relative to their XHTML file; make them archive paths.
            folder = posixpath.dirname(href)
            text = re.sub(
                rf"^!\[([^\]\n]*)\]\({EPUB_IMAGE_PREFIX}([^)\n]+)\)$",
                lambda m: f"![{m.group(1)}]({EPUB_IMAGE_PREFIX}"
                          f"{posixpath.normpath(posixpath.join(folder, m.group(2).split('#')[0]))})",
                normalize_text("".join(parser.parts)),
                flags=re.MULTILINE,
            )
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
        book = Book(id="", title=title, author=author, chapters=chapters, cover=cover)
        cover_hash = sha256(cover[1]) if cover else None
        names = set(zf.namelist())

        def load(ref: str) -> tuple[str, bytes]:
            name = ref[len(EPUB_IMAGE_PREFIX):] if ref.startswith(EPUB_IMAGE_PREFIX) else ref
            if name not in names:
                raise MkBookError(f"EPUB 里找不到图片：{name}")
            return posixpath.basename(name), zf.read(name)

        # The cover usually has its own page; it is shown on the shelf, not as an illustration.
        for chapter in book.chapters:
            kept = []
            for line in chapter.text.split("\n"):
                match = IMAGE_LINE.match(line)
                if match:
                    data = load(match.group(2))[1]
                    if image_type(data) is None or sha256(data) == cover_hash:
                        continue
                kept.append(line)
            chapter.text = "\n".join(kept)
        book.chapters = [c for c in book.chapters if c.text]
        embed_images(book, load)
    return book


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
    for path, data in book.images.items():
        if not IMAGE_PATH.match(path) or image_type(data) != posixpath.splitext(path)[1]:
            raise MkBookError(f"插图路径或格式不合规：{path}")
    for chapter in book.chapters:
        if not CHAPTER_ID.match(chapter.id or "") or chapter.id in seen:
            raise MkBookError(f"章节 id 无效或重复：{chapter.id}")
        seen.add(chapter.id)
        check_text(chapter.text, f"章节《{chapter.title}》")
        for _, ref in image_refs(chapter.text):
            if ref not in book.images:
                raise MkBookError(f"章节《{chapter.title}》引用了不存在的插图：{ref}")
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
        "formatVersion": IMAGES_FORMAT_VERSION if book.images else FORMAT_VERSION,
        "id": book.id,
        "revision": book.revision,
        "updatedAt": _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "metadata": metadata,
        "chapters": entries,
    }
    if book.images:
        manifest["images"] = [
            {"path": path, "size": len(data), "sha256": sha256(data)} for path, data in sorted(book.images.items())
        ]
        files.extend(sorted(book.images.items()))
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
            # Images are already compressed.
            stored = zipfile.ZIP_STORED if path.startswith(("cover", "images/")) else zipfile.ZIP_DEFLATED
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
        version = manifest.get("formatVersion")
        if isinstance(version, bool) or version not in SUPPORTED_FORMAT_VERSIONS:
            raise MkBookError(f"不支持的 formatVersion：{version}")
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
            if image_type(zf.read(cover)) != posixpath.splitext(cover)[1]:
                raise MkBookError("封面内容与扩展名不符")
            allowed.add(cover)
        images = validate_images(zf, manifest, names, version)
        allowed.update(images)
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
            if version >= IMAGES_FORMAT_VERSION:
                for _, ref in image_refs(text):
                    if ref not in images:
                        raise MkBookError(f"{where}《{title}》: 引用了未登记的插图 {ref}")
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


def validate_images(zf: zipfile.ZipFile, manifest: dict, names: set[str], version: int) -> set[str]:
    """Checks the images list (spec §1.4); returns the declared paths."""
    entries = manifest.get("images")
    if entries is None:
        return set()
    if version < IMAGES_FORMAT_VERSION:
        raise MkBookError("只有 formatVersion 2 才能包含 images")
    if not isinstance(entries, list) or len(entries) > MAX_IMAGES:
        raise MkBookError(f"images 需为不超过 {MAX_IMAGES} 项的数组")
    paths: set[str] = set()
    for n, entry in enumerate(entries, start=1):
        path = entry.get("path") if isinstance(entry, dict) else None
        where = f"第 {n} 张插图"
        if not isinstance(path, str) or not IMAGE_PATH.match(path) or path in paths or path not in names:
            raise MkBookError(f"{where}: 路径不合规、重复或文件不存在")
        size = entry.get("size")
        if not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= MAX_IMAGE_BYTES:
            raise MkBookError(f"{where}: size 不合规（单张不超过 10MB）")
        if zf.getinfo(path).file_size != size:
            raise MkBookError(f"{where}: size 与文件不符")
        data = zf.read(path)
        if sha256(data) != entry.get("sha256"):
            raise MkBookError(f"{where}: sha256 不匹配")
        if image_type(data) != posixpath.splitext(path)[1]:
            raise MkBookError(f"{where}: 内容不是扩展名所示的图片格式")
        paths.add(path)
    return paths


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
    if args.cover:
        cover = Path(args.cover)
        data = cover.read_bytes()
        ext = image_type(data)
        if ext is None:
            raise MkBookError("封面需为 JPEG/PNG/WebP")
        book.cover = (ext, data)
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
    extras = "".join([
        f"，{len(manifest['images'])} 张插图" if manifest.get("images") else "",
        "，含封面" if manifest["metadata"].get("cover") else "",
    ])
    print(f"已生成 {out}：《{manifest['metadata']['title']}》 r{manifest['revision']}，"
          f"{len(manifest['chapters'])} 章，{total} 字{extras}")
    if previous:
        print_diff(diff_manifests(previous, manifest))


def print_diff(d: dict) -> None:
    print(f"  新增 {len(d['added'])} 章，删除 {len(d['removed'])} 章，正文修改 {len(d['changed'])} 章，"
          f"改标题 {len(d['retitled'])} 章{'，章节顺序有变化' if d['reordered'] else ''}")


def cmd_validate(args) -> None:
    manifest = validate_package(Path(args.package))
    print(f"✓ 合规：{manifest['id']} r{manifest['revision']}《{manifest['metadata']['title']}》"
          f"{len(manifest['chapters'])} 章，{len(manifest.get('images') or [])} 张插图")


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
    b.add_argument("--cover", help="封面图片（JPEG/PNG/WebP，≤10MB）")
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
