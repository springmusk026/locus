# Third-party assets in core/designsystem

## Provider and model logos (`src/main/res/drawable/logo_*.xml`)

Converted from **LobeHub Icons** (https://github.com/lobehub/lobe-icons), npm package
`@lobehub/icons-static-svg` 1.101.0
(https://registry.npmjs.org/@lobehub/icons-static-svg/-/icons-static-svg-1.101.0.tgz,
sha256 `5742ae702372ca7ba26c8166a9a860657cb3fe180e3f30abbd9cf0e7d1a88c76`), files under `icons/`.
The package's `package.json` declares `"license": "MIT"`, and the repository's licence is at
https://github.com/lobehub/lobe-icons/blob/master/LICENSE:

```
MIT License

Copyright (c) 2023 LobeHub

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

| Drawable | Source SVG |
|---|---|
| logo_openai | openai.svg |
| logo_anthropic | anthropic.svg |
| logo_claude | claude-color.svg |
| logo_gemini | gemini-color.svg |
| logo_gemma | gemma-color.svg |
| logo_openrouter | openrouter.svg |
| logo_groq | groq.svg |
| logo_ollama | ollama.svg |
| logo_lmstudio | lmstudio.svg |
| logo_mistral | mistral-color.svg |
| logo_deepseek | deepseek-color.svg |
| logo_meta | meta-color.svg |
| logo_qwen | qwen-color.svg |
| logo_xai | xai.svg |
| logo_grok | grok.svg |

Conversion: path data is copied value for value; it is only re-tokenised with explicit separators so
compact arc flags parse the same on Android. Fill rule, fill opacity and colours are kept. Single-colour
(`currentColor`) marks are written black and tinted with the theme's content colour at runtime.
Gradients with `gradientUnits="userSpaceOnUse"` are copied as is; percentage (objectBoundingBox)
gradients are mapped onto each path's bounding box. That is exact for square boxes (Gemma) and a close
approximation for the subtle blue shading of the Meta mark.

### Trademarks

The MIT licence covers the SVG files, not the marks. The names and logos belong to their owners
(OpenAI, Anthropic, Google, OpenRouter, Groq, Ollama, LM Studio/Element Labs, Mistral AI, DeepSeek,
Meta, Alibaba Cloud/Qwen, xAI). Locus uses them only nominatively, to identify which provider or model
family the user has configured. It does not imply endorsement or affiliation. Providers without a logo
here keep the coloured monogram.

## File-type icons (`src/main/res/drawable/ficon_*.xml`)

Converted from **Material Icon Theme** by Material Extensions / Philipp Kief
(https://github.com/material-extensions/vscode-material-icon-theme), npm package `material-icon-theme`
5.40.0 (https://registry.npmjs.org/material-icon-theme/-/material-icon-theme-5.40.0.tgz,
sha256 `6ea13a2dece7ca953c0315b1e93348660c02bb235706275d215be69f09d98efb`, git commit
`045291ff033062a25441dd297fd51546326866f2`), files under `icons/`. The package's `package.json` declares
`"license": "MIT"`; the `LICENSE` file shipped in the package
(also https://github.com/material-extensions/vscode-material-icon-theme/blob/main/LICENSE) reads:

```
The MIT License (MIT)
Copyright (c) 2025 Material Extensions

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

| Drawable | Source SVG |
|---|---|
| ficon_folder | folder.svg |
| ficon_folder_git | folder-git.svg |
| ficon_folder_node | folder-node.svg |
| ficon_folder_src | folder-src.svg |
| ficon_folder_docs | folder-docs.svg |
| ficon_folder_mail | folder-mail.svg |
| ficon_folder_images | folder-images.svg |
| ficon_folder_scripts | folder-scripts.svg |
| ficon_folder_test | folder-test.svg |
| ficon_folder_config | folder-config.svg |
| ficon_folder_dist | folder-dist.svg |
| ficon_folder_temp | folder-temp.svg |
| ficon_folder_trash | folder-trash.svg |
| ficon_folder_github | folder-github.svg |
| ficon_folder_vscode | folder-vscode.svg |
| ficon_folder_lib | folder-lib.svg |
| ficon_folder_public | folder-public.svg |
| ficon_folder_audio | folder-audio.svg |
| ficon_folder_video | folder-video.svg |
| ficon_folder_archive | folder-archive.svg |
| ficon_folder_database | folder-database.svg |
| ficon_folder_keys | folder-keys.svg |
| ficon_folder_download | folder-download.svg |
| ficon_folder_skills | folder-skills.svg |
| ficon_folder_python | folder-python.svg |
| ficon_folder_log | folder-log.svg |
| ficon_python | python.svg |
| ficon_javascript | javascript.svg |
| ficon_typescript | typescript.svg |
| ficon_kotlin | kotlin.svg |
| ficon_java | java.svg |
| ficon_go | go.svg |
| ficon_rust | rust.svg |
| ficon_c | c.svg |
| ficon_cpp | cpp.svg |
| ficon_h | h.svg |
| ficon_hpp | hpp.svg |
| ficon_shell | console.svg |
| ficon_html | html.svg |
| ficon_css | css.svg |
| ficon_json | json.svg |
| ficon_yaml | yaml.svg |
| ficon_toml | toml.svg |
| ficon_xml | xml.svg |
| ficon_sql | database.svg |
| ficon_docker | docker.svg |
| ficon_markdown | markdown.svg |
| ficon_readme | readme.svg |
| ficon_pdf | pdf.svg |
| ficon_word | word.svg |
| ficon_table | table.svg |
| ficon_powerpoint | powerpoint.svg |
| ficon_text | document.svg |
| ficon_image | image.svg |
| ficon_audio | audio.svg |
| ficon_video | video.svg |
| ficon_svg | svg.svg |
| ficon_zip | zip.svg |
| ficon_android | android.svg |
| ficon_env | tune.svg |
| ficon_lock | lock.svg |
| ficon_settings | settings.svg |
| ficon_git | git.svg |
| ficon_nodejs | nodejs.svg |
| ficon_npm | npm.svg |
| ficon_makefile | makefile.svg |
| ficon_gradle | gradle.svg |
| ficon_log | log.svg |
| ficon_key | key.svg |
| ficon_certificate | certificate.svg |
| ficon_license | license.svg |
| ficon_font | font.svg |
| ficon_jupyter | jupyter.svg |
| ficon_swift | swift.svg |
| ficon_ruby | ruby.svg |
| ficon_php | php.svg |
| ficon_lua | lua.svg |
| ficon_csharp | csharp.svg |
| ficon_dart | dart.svg |
| ficon_r | r.svg |
| ficon_exe | exe.svg |
| ficon_todo | todo.svg |
| ficon_file | file.svg |
| ficon_toml_light | toml_light.svg |

Conversion: path data is copied value for value and only re-tokenised with explicit separators; `rect`
and `circle` elements are written as the equivalent path; fill colours and fill rules are kept. The one
gradient (Kotlin, `userSpaceOnUse`) has its `gradientTransform` (translate + scale) applied to the end
points. `json.svg` uses a `0 -960 960 960` viewBox, so its path sits in a group translated by 960.
`ficon_toml_light` is the source's light-theme variant and is shown on light surfaces.
Logos inside some icons (Docker, Node.js, npm, Git, language marks) belong to their owners and are used
only to identify file types.
