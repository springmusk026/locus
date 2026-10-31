# Third-party code in core/markdown

## Mermaid (`src/main/assets/mermaid/mermaid.tiny.js`)

**@mermaid-js/tiny** 12.2.0, the single-file build of Mermaid (https://mermaid.js.org,
https://github.com/mermaid-js/mermaid) without mindmap and architecture diagrams, copied unmodified
from `package/dist/mermaid.tiny.js` in https://registry.npmjs.org/@mermaid-js/tiny/-/tiny-12.2.0.tgz
(npm integrity `sha512-9bEE+R974NTh1YJlfc6ztgwhy6+9sE+wAFiFrqvSdtlJHdxGfLytQmHYHNpFKmj94+Z+uMicYrQeGXnTTjILAQ==`,
file sha256 `ea0cdf04eb31907eb20d956a34fe300095365c876887b54ffa29f1f847f56d00`). The package's
`package.json` declares `"license": "MIT"` and its `LICENSE` reads:

```
The MIT License (MIT)

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The bundle includes code from other projects. Its own "Bundled license information" comment (kept at
the end of the file) names:

- **DOMPurify** 3.4.12, (c) Cure53 and other contributors, Apache License 2.0 and Mozilla Public
  License 2.0 (https://github.com/cure53/DOMPurify/blob/3.4.12/LICENSE). Locus uses it under the
  Apache License 2.0.
- **Lodash** 4.18.1 (custom build), Copyright OpenJS Foundation and other contributors, MIT
  (https://lodash.com/license); based on Underscore.js 1.8.3, Copyright Jeremy Ashkenas, DocumentCloud
  and Investigative Reporters & Editors.

`render.html` and `render.js` next to it are Locus's own (AGPL-3.0-or-later).

## LaTeX typesetting (Maven dependency, not copied into this repository)

**io.github.huarangmeng:latex-renderer** 1.5.6 with `latex-parser` and `latex-base`
(https://github.com/huarangmeng/latex), MIT, Copyright (c) 2026 huarangmeng. The AAR bundles the
**KaTeX fonts** (https://github.com/KaTeX/katex-fonts), MIT, Copyright (c) 2018 Khan Academy; KaTeX
itself is MIT, Copyright (c) 2013-2020 Khan Academy and other contributors. Both licences are the
standard MIT text reproduced above with their own copyright lines.
