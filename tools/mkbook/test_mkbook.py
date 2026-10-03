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
            doc["formatVersion"] = 2
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


if __name__ == "__main__":
    unittest.main()
