# Third-party notices

`offline_document_viewer` renders documents on the device, which means it
redistributes the engines and fonts that do the rendering. Everything bundled
here is open source. This file lists what is included, under which license,
and what your application must carry when it ships.

**If you publish an app that depends on this package, reproduce these notices
in it.** The full license texts are bundled as assets under
`assets/licenses/`, so you can load and display them from an about screen
without vendoring anything yourself.

---

## Rendering engines

### SheetJS Community Edition — Apache-2.0

Reads spreadsheet values, number formats and formula results.

Apache-2.0 requires attribution. Add this text to your open-source
disclosures verbatim:

```
SheetJS Community Edition -- https://sheetjs.com/
Copyright (C) 2012-present SheetJS LLC
Licensed under the Apache License, Version 2.0.
```

> Note: the copy bundled here is taken from `cdn.sheetjs.com`, not from the
> npm `xlsx` package. The npm package is frozen at an old version with a known
> vulnerability.

### docx-preview — Apache-2.0

Renders Word documents to HTML. Upstream: `VolodymyrBaydalka/docxjs`.

**Modified.** Three fixes are applied. Each is marked with a `/* PATCH: */`
comment in the bundled source, and the reasoning is recorded in
`doc/engine-patches-docx-preview.md`. To diff against upstream, fetch
`docx-preview@0.3.5` from npm — the bundled file is that release plus these
changes:

1. `themeFill` was never read, so theme-based table shading disappeared.
2. `themeTint` / `themeShade` were not applied, so "accent1, 60% lighter"
   rendered as solid accent1.
3. `w:val="auto"` short-circuited before the theme branch, so every
   theme-based **text** colour came out black.

All three are spec-conformance fixes rather than behaviour preferences.

### PPTXjs — MIT

Renders PowerPoint presentations. Upstream: `meshesha/PPTXjs`.

**Modified.** Five fixes are applied, marked with `/* PATCH: */` in the
bundled source and explained in `doc/engine-patches-pptxjs.md`. To diff
against upstream, fetch `meshesha/PPTXjs` at 1.21.1 — the bundled file is that
release plus these changes:

1. Bullet `a:buFont` was read only from the paragraph's own properties, never
   inherited from the slide layout or master — inherited bullets rendered as
   tofu boxes.
2. Plain `Wingdings` was commented out of the dingbat conversion.
3. Numeric XML character references (`&#xF097;`) in attributes were not
   decoded, turning bullets into unrelated glyphs.
4. A missing `docProps/app.xml` crashed the entire render. PowerPoint always
   writes that part; LibreOffice, Google Slides and python-pptx may not.
5. `numCol` (multi-column text) and `vert` (vertical text) were ignored.

Bundled with its pinned dependencies: jQuery 1.11 (MIT), JSZip 2 (MIT),
D3 (ISC), NVD3 (Apache-2.0).

### JSZip — MIT

Reads the zip container of Office Open XML files. Version 3 for Word and
Excel, version 2 for PowerPoint — the two are not interchangeable, which is
why each format renders in its own WebView.

### PDFium — BSD-3-Clause

PDFs render natively through the `pdfrx` package, which embeds PDFium. Not
redistributed by this package directly; `pdfrx` carries its own notices.

---

## Fonts

Office documents compute text box widths from the metrics of fonts that exist
on neither Android nor iOS. Metric-compatible substitutes are bundled so text
fits its box and fixed-position layouts do not drift.

| Requested by documents | Bundled substitute | License |
| --- | --- | --- |
| Calibri | Carlito | SIL OFL 1.1 |
| Cambria | Caladea | SIL OFL 1.1 |
| Arial, Helvetica | Liberation Sans | SIL OFL 1.1 |
| Times New Roman | Liberation Serif | SIL OFL 1.1 |
| Courier New | Liberation Mono | SIL OFL 1.1 |

Symbol coverage, needed so that converted bullet glyphs can actually be drawn:

| Font | Purpose | License |
| --- | --- | --- |
| DejaVu Sans | Dingbats and geometric shapes | Bitstream Vera + Public Domain |
| Noto Sans Math | Mathematical blocks | SIL OFL 1.1 |
| Noto Sans Symbols 2 | Symbol and pictograph blocks | SIL OFL 1.1 |

Together these three cover 849 of the 862 code points the Wingdings/Webdings
conversion table can emit (98%). The remaining 13 are rare pictographs that do
not occur as bullets in practice.

SIL OFL 1.1 permits bundling and redistribution. Reserved Font Names are
respected: the fonts are shipped unmodified and under their original names.

---

## Where the license texts live

```
assets/licenses/LICENSE-liberation
assets/licenses/LICENSE-dejavu
assets/licenses/LICENSE-caladea
assets/licenses/LICENSE-pptxjs
```

Load them with `rootBundle.loadString('packages/offline_document_viewer/assets/licenses/...')`.

