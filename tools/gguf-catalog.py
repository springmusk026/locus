#!/usr/bin/env python3
"""
Verifies or regenerates Locus's recommended GGUF catalogue from the Hugging Face API
(docs/design/llamacpp.md, "Getting models").

    python3 -I tools/gguf-catalog.py            # verify: every entry still matches its pinned file
    python3 -I tools/gguf-catalog.py --update   # re-pin every entry to its repo's current main commit

The catalogue is core/ondevice/src/main/resources/dev/locus/core/ondevice/gguf/catalog.json. Each
entry names a repo and a file; the hand-written fields (name, description, licenseName) are kept,
and everything else is read here:

- revision: the commit (GET /api/models/{repo}/revision/main → "sha"), only with --update.
- sizeBytes, sha256: GET /api/models/{repo}/tree/{revision} → the file's "lfs.size" and "lfs.oid".
- license, gated: GET /api/models/{repo}?expand[]=cardData&expand[]=gated. A gated repo is refused:
  Locus downloads without a Hugging Face account.
- architecture, contextLength, sizeLabel, kvBytesPerToken, tools: the GGUF header itself, read with
  an HTTP Range request for the first bytes of the pinned file, the same way the app reads it
  (GgufHeader.kt, GgufMemory.kvBytesPerToken, GgufTemplate.supportsTools).

Verify mode changes nothing and exits 1 when anything differs. No token is sent; only huggingface.co
(and the CDN it redirects file reads to) is contacted.
"""
import json
import os
import re
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "core/ondevice/src/main/resources/dev/locus/core/ondevice/gguf/catalog.json")
HF = "https://huggingface.co"
UA = "Locus-catalog-check/1"


def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def api(path):
    return json.loads(get(HF + path))


def q(s):
    return urllib.parse.quote(s, safe="/")


# --- GGUF header (mirrors GgufReader: scalars and strings kept, arrays skipped except head_count_kv) ---

SCALAR = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, n):
        if self.p + n > len(self.d):
            raise EOFError
        b = self.d[self.p:self.p + n]
        self.p += n
        return b

    def u(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        return self.take(self.u("<Q")).decode("utf-8", "replace")

    def value(self, t):
        if t == 8:
            return self.string()
        if t == 9:
            et, n = self.u("<I"), self.u("<Q")
            return [self.value(et) for _ in range(n)]
        return self.u(SCALAR[t])


def header(repo, revision, path):
    url = f"{HF}/{q(repo)}/resolve/{revision}/{q(path)}"
    size = 4 << 20
    while True:
        data = get(url, {"Range": f"bytes=0-{size - 1}"})
        try:
            r = Reader(data)
            if r.u("<I") != 0x46554747:
                raise SystemExit(f"{repo}/{path}: not a GGUF file")
            r.u("<I")
            r.u("<Q")
            meta = {}
            for _ in range(r.u("<Q")):
                key = r.string()
                meta[key] = r.value(r.u("<I"))
            return meta
        except EOFError:
            if size >= 64 << 20:
                raise SystemExit(f"{repo}/{path}: header larger than 64 MB")
            size *= 2


def from_header(meta):
    arch = meta.get("general.architecture", "")

    def a(k):
        return meta.get(f"{arch}.{k}")

    layers, heads = a("block_count"), a("attention.head_count")
    kv = a("attention.head_count_kv")
    if isinstance(kv, list):
        kv = max(kv) if kv else None
    kv = kv if kv is not None else heads
    per_head = (a("embedding_length") // heads) if a("embedding_length") and heads else None
    key, value = a("attention.key_length") or per_head, a("attention.value_length") or per_head
    kv_bytes = layers * kv * (key + value) * 2 if all(x and x > 0 for x in (layers, kv, key, value)) else None
    template = meta.get("tokenizer.chat_template") or ""
    return {
        "architecture": arch,
        "contextLength": a("context_length"),
        "sizeLabel": meta.get("general.size_label"),
        "kvBytesPerToken": kv_bytes,
        "tools": supports_tools(template),
    }


JINJA_BLOCK = re.compile(r"\{[{%]-?(.*?)-?[%}]}", re.S)
TOOLS_VARIABLE = re.compile(r"(?<![\w.'\"])(?:[a-z]+_)?tools(?![\w'\"])")


def supports_tools(template):
    """GgufTemplate.supportsTools: the Jinja code reads a `tools` variable (not just the word in text)."""
    if not template.strip():
        return False
    code = "\n".join(m.group(1) for m in JINJA_BLOCK.finditer(template))
    return bool(TOOLS_VARIABLE.search(code))


def check(entry, update):
    repo, path = entry["repo"], entry["file"]
    info = api(f"/api/models/{q(repo)}?expand[]=cardData&expand[]=gated")
    if info.get("gated"):
        raise SystemExit(f"{repo} is gated ({info['gated']}); Locus downloads without an account. Pick another upload.")
    revision = api(f"/api/models/{q(repo)}/revision/main")["sha"] if update else entry["revision"]
    folder = path.rsplit("/", 1)[0] if "/" in path else ""
    tree = api(f"/api/models/{q(repo)}/tree/{revision}" + (f"/{q(folder)}" if folder else ""))
    item = next((t for t in tree if t.get("path") == path), None)
    if item is None or "lfs" not in item:
        raise SystemExit(f"{repo}@{revision}: {path} not found (or not an LFS file)")
    found = {
        "revision": revision,
        "sizeBytes": item["lfs"]["size"],
        "sha256": item["lfs"]["oid"],
        "license": (info.get("cardData") or {}).get("license"),
    }
    if update or any(k not in entry for k in ("architecture", "kvBytesPerToken", "tools")):
        found.update(from_header(header(repo, revision, path)))
    return found


def main():
    update = "--update" in sys.argv
    with open(CATALOG) as f:
        catalog = json.load(f)
    problems = []
    for entry in catalog["models"]:
        found = check(entry, update)
        for k, v in found.items():
            if entry.get(k) != v:
                if update:
                    entry[k] = v
                else:
                    problems.append(f"{entry['id']}: {k} is {entry.get(k)!r}, the Hub says {v!r}")
        print(f"ok  {entry['id']}  {entry['repo']}@{entry['revision'][:8]}  {entry['file']}  {entry['sizeBytes']}")
    if update:
        with open(CATALOG, "w") as f:
            json.dump(catalog, f, indent=2)
            f.write("\n")
        print(f"Wrote {CATALOG}")
    elif problems:
        print("\n".join(problems))
        sys.exit(1)


if __name__ == "__main__":
    main()
