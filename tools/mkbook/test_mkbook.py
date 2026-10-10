import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import mkbook

SAMPLE = Path(__file__).resolve().parents[2] / "docs/formats/examples/夜行者.mktxt"


class MkBookTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def build(self, source=SAMPLE, **kwargs):
        out = self.dir / "book.mkbook"
        args = ["build", str(source), "-o", str(out)]
        for key, value in kwargs.items():
            args += [f"--{key}", str(value)]
        self.assertEqual(0, mkbook.main(args))
        return out

    def rewrite(self, package, mutate):
        """Copies a package, letting mutate(name, data) change or drop (None) entries."""
        out = self.dir / "tampered.mkbook"
        with zipfile.ZipFile(package) as src, zipfile.ZipFile(out, "w") as dst:
            for info in src.infolist():
                data = mutate(info.filename, src.read(info.filename))
                if data is not None:
                    dst.writestr(info, data)
        return out

    def test_sample_builds_with_volumes_ids_and_normalized_text(self):
        manifest = mkbook.validate_package(self.build())
        self.assertEqual("sample-yexingzhe", manifest["id"])
        self.assertEqual(["c0001", "c0002", "c0003"], [c["id"] for c in manifest["chapters"]])
        self.assertEqual("第二卷 远行", manifest["chapters"][2]["volume"])
        self.assertEqual({"pronunciation": "pronunciation.json"}, manifest["narration"])
        with zipfile.ZipFile(self.dir / "book.mkbook") as zf:
            text = zf.read("chapters/c0002.txt").decode()
        self.assertTrue(text.startswith("老人从怀里"), "full-width indentation must be stripped")
        self.assertNotIn("\n\n", text)

    def test_normalize_text_rules(self):
        raw = "﻿  第一段  \r\n\r\n　　第二段\r***\n"
        self.assertEqual("第一段\n第二段\n※※※", mkbook.normalize_text(raw))

    def test_tampered_chapter_is_rejected(self):
        bad = self.rewrite(self.build(), lambda n, d: d + "改".encode() if n == "chapters/c0001.txt" else d)
        with self.assertRaisesRegex(mkbook.MkBookError, "sha256"):
            mkbook.validate_package(bad)

    def test_undeclared_entry_is_rejected(self):
        package = self.build()
        out = self.dir / "extra.mkbook"
        out.write_bytes(package.read_bytes())
        with zipfile.ZipFile(out, "a") as zf:
            zf.writestr("evil.sh", "rm -rf /")
        with self.assertRaisesRegex(mkbook.MkBookError, "未声明"):
            mkbook.validate_package(out)

    def test_path_traversal_is_rejected(self):
        package = self.build()
        out = self.dir / "traversal.mkbook"
        out.write_bytes(package.read_bytes())
        with zipfile.ZipFile(out, "a") as zf:
            zf.writestr("../outside.txt", "x")
        with self.assertRaisesRegex(mkbook.MkBookError, "非法路径"):
            mkbook.validate_package(out)

    def test_newer_format_version_is_rejected(self):
        def bump(name, data):
            if name != "mkbook.json":
                return data
            doc = json.loads(data)
            doc["formatVersion"] = 3
            return json.dumps(doc).encode()
        with self.assertRaisesRegex(mkbook.MkBookError, "formatVersion"):
            mkbook.validate_package(self.rewrite(self.build(), bump))

    def test_previous_keeps_ids_and_bumps_revision(self):
        first = self.build()
        kept = self.dir / "r1.mkbook"
        kept.write_bytes(first.read_bytes())
        source = self.dir / "next.mktxt"
        source.write_text(
            SAMPLE.read_text(encoding="utf-8").replace("## 第3章 出城", "## 第2.5章 插曲\n\n新内容。\n\n## 第3章 出城"),
            encoding="utf-8",
        )
        second = mkbook.validate_package(self.build(source=source, previous=kept))
        ids = {c["title"]: c["id"] for c in second["chapters"]}
        self.assertEqual("c0003", ids["第3章 出城"], "existing chapter keeps its id")
        self.assertEqual("c0004", ids["第2.5章 插曲"], "new chapter gets a fresh id")
        self.assertEqual(2, second["revision"])

    def test_plain_txt_detects_chapters(self):
        source = self.dir / "plain.txt"
        source.write_bytes("序\n第一章 开始\n内容一\n第二章 继续\n内容二\n".encode("gb18030"))
        manifest = mkbook.validate_package(self.build(source=source, id="plain-book"))
        self.assertEqual(["前言", "第一章 开始", "第二章 继续"], [c["title"] for c in manifest["chapters"]])

    def test_txt_without_id_fails_cleanly(self):
        source = self.dir / "noid.txt"
        source.write_text("内容", encoding="utf-8")
        self.assertEqual(1, mkbook.main(["build", str(source), "-o", str(self.dir / "x.mkbook")]))

    # ------------------------------------------------------------ images (formatVersion 2)

    PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
    JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32

    def illustrated_source(self):
        (self.dir / "img").mkdir()
        (self.dir / "img" / "map one.png").write_bytes(self.PNG)
        (self.dir / "img" / "copy.png").write_bytes(self.PNG)
        (self.dir / "cover.jpg").write_bytes(self.JPEG)
        source = self.dir / "illustrated.mktxt"
        source.write_text(
            "---\nid: illustrated\ntitle: 插图测试\ncover: cover.jpg\n---\n\n"
            "## 第1章 {#c1}\n\n![](img/map one.png)\n第一段。\n\n"
            "## 第2章 {#c2}\n\n正文。\n![同一张图](img/copy.png)\n",
            encoding="utf-8",
        )
        return source

    def test_images_are_packaged_at_their_positions(self):
        # "map one.png" has a space, which the image line syntax does not allow: it stays text.
        source = self.illustrated_source()
        source.write_text(source.read_text(encoding="utf-8").replace("map one.png", "map.png"), encoding="utf-8")
        (self.dir / "img" / "map one.png").rename(self.dir / "img" / "map.png")
        package = self.build(source)
        manifest = mkbook.validate_package(package)
        self.assertEqual(2, manifest["formatVersion"])
        self.assertEqual("cover.jpg", manifest["metadata"]["cover"])
        # Identical files are stored once.
        self.assertEqual(["images/map.png"], [i["path"] for i in manifest["images"]])
        with zipfile.ZipFile(package) as zf:
            self.assertEqual("![](images/map.png)\n第一段。", zf.read("chapters/c1.txt").decode())
            self.assertEqual("正文。\n![同一张图](images/map.png)", zf.read("chapters/c2.txt").decode())
            self.assertEqual(zipfile.ZIP_STORED, zf.getinfo("images/map.png").compress_type)

    def test_book_without_images_stays_version_1(self):
        self.assertEqual(1, mkbook.validate_package(self.build())["formatVersion"])

    def test_missing_image_file_fails(self):
        source = self.dir / "broken.mktxt"
        source.write_text("---\nid: broken\ntitle: 坏\n---\n## 一 {#c1}\n![](nope.png)\n", encoding="utf-8")
        self.assertEqual(1, mkbook.main(["build", str(source), "-o", str(self.dir / "x.mkbook")]))

    def test_image_that_is_not_an_image_fails(self):
        source = self.dir / "fake.mktxt"
        (self.dir / "fake.png").write_bytes(b"not an image")
        source.write_text("---\nid: fake\ntitle: 假\n---\n## 一 {#c1}\n![](fake.png)\n", encoding="utf-8")
        self.assertEqual(1, mkbook.main(["build", str(source), "-o", str(self.dir / "x.mkbook")]))

    def illustrated_package(self):
        source = self.illustrated_source()
        source.write_text(source.read_text(encoding="utf-8").replace("map one.png", "copy.png"), encoding="utf-8")
        return self.build(source)

    def test_tampered_image_is_rejected(self):
        package = self.rewrite(
            self.illustrated_package(),
            lambda name, data: data[:-1] + b"\x01" if name.startswith("images/") else data,
        )
        with self.assertRaisesRegex(mkbook.MkBookError, "sha256"):
            mkbook.validate_package(package)

    def test_reference_to_undeclared_image_is_rejected(self):
        def mutate(name, data):
            if name == "chapters/c1.txt":
                return data.replace(b"images/copy.png", b"images/other.png")
            if name == "mkbook.json":
                manifest = json.loads(data)
                text = "![](images/other.png)\n第一段。".encode()
                manifest["chapters"][0].update(sha256=mkbook.sha256(text), chars=len(text.decode()))
                return json.dumps(manifest).encode()
            return data

        with self.assertRaisesRegex(mkbook.MkBookError, "未登记"):
            mkbook.validate_package(self.rewrite(self.illustrated_package(), mutate))

    def test_images_require_version_2(self):
        def mutate(name, data):
            if name == "mkbook.json":
                manifest = json.loads(data)
                manifest["formatVersion"] = 1
                return json.dumps(manifest).encode()
            return data

        with self.assertRaisesRegex(mkbook.MkBookError, "formatVersion 2"):
            mkbook.validate_package(self.rewrite(self.illustrated_package(), mutate))

    def test_cover_option_for_txt(self):
        source = self.dir / "plain.txt"
        source.write_text("第一章 开始\n正文一。\n", encoding="utf-8")
        (self.dir / "c.png").write_bytes(self.PNG)
        manifest = mkbook.validate_package(self.build(source, id="plain", cover=self.dir / "c.png"))
        self.assertEqual("cover.png", manifest["metadata"]["cover"])

    def test_epub_images_become_image_lines_and_cover_is_not_repeated(self):
        epub = self.dir / "book.epub"
        with zipfile.ZipFile(epub, "w") as zf:
            zf.writestr("mimetype", "application/epub+zip")
            zf.writestr("META-INF/container.xml",
                        '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
            zf.writestr("OEBPS/content.opf", """<package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>图书</dc:title></metadata>
              <manifest>
                <item id="cover" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="p0" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="p1" href="text/one.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="p0"/><itemref idref="p1"/></spine></package>""")
            zf.writestr("OEBPS/img/cover.jpg", self.JPEG)
            zf.writestr("OEBPS/img/fig.png", self.PNG)
            zf.writestr("OEBPS/text/cover.xhtml", '<html><body><img src="../img/cover.jpg"/></body></html>')
            zf.writestr("OEBPS/text/one.xhtml",
                        '<html><body><h1>第一章</h1><p>开头。</p><p><img src="../img/fig.png" alt="插图"/></p>'
                        '<p>结尾。</p></body></html>')
        package = self.build(epub, id="epub-book")
        manifest = mkbook.validate_package(package)
        self.assertEqual(["第一章"], [c["title"] for c in manifest["chapters"]])
        self.assertEqual(["images/fig.png"], [i["path"] for i in manifest["images"]])
        with zipfile.ZipFile(package) as zf:
            self.assertEqual("开头。\n![插图](images/fig.png)\n结尾。", zf.read("chapters/c0001.txt").decode())


if __name__ == "__main__":
    unittest.main()
