# Reader Pagination Test Font

`NotoSansCJKsc-ReaderTest.otf` is a glyph subset of Noto Sans CJK SC Regular.
It is packaged only in the instrumentation test APK so pagination tests do not
depend on the emulator's default font selection.

- Source: Android API 35 system image, `/system/fonts/NotoSansCJK-Regular.ttc`, face 2
- Source SHA-256: `9ca9debb09459bf4e3e7f826f5cd0f35f253902b85684921fce2ba3f28dd0f50`
- Subset SHA-256: `9d08babe463fbd7e11792e5a399f97afb3892a2db968fa5b1032fda07b5987ba`
- License: SIL Open Font License 1.1, as recorded in the font name table
- Subsetter: fontTools 4.61.1, using the reader instrumentation-test glyph set

The test-only subset retains the source font's copyright and license metadata.
