#!/usr/bin/env python3
# Fetch pinned upstream, apply narrowly scoped integration changes, and build.
import json, pathlib, subprocess, urllib.request, tarfile, io, shutil

root = pathlib.Path(__file__).resolve().parent
pin = json.loads((root / "upstream.json").read_text())
cache = root / ".cache"
source = cache / pin["commit"]
if not source.exists():
    cache.mkdir(exist_ok=True)
    data = urllib.request.urlopen(
        f"https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{pin['commit']}"
    ).read()
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        members = [
            m
            for m in archive.getmembers()
            if "/ui/" in m.name or m.name.endswith("/LICENSE")
        ]
        if any(
            m.issym()
            or m.islnk()
            or ".." in pathlib.PurePosixPath(m.name).parts
            or m.name.startswith("/")
            for m in members
        ):
            raise SystemExit("Unsafe upstream archive")
        archive.extractall(cache, members=members)
    (cache / f"llama.cpp-{pin['commit']}").rename(source)
app = source / pin["path"]
storage = app / "src/lib/constants/storage.constants.ts"
s = storage.read_text()
s = s.replace(
    "export const STORAGE_APP_NAME = 'LlamaUi';",
    "export const STORAGE_APP_NAME = 'CompanyRag-' + (window as unknown as {__RAG_SESSION__: string}).__RAG_SESSION__;",
)
storage.write_text(s)
headers = app / "src/lib/utils/api-headers.ts"
s = headers.read_text()
start = s.index("export function getAuthHeaders()")
end = s.index("\n}", start) + 2
s = (
    s[:start]
    + """export function getAuthHeaders(): Record<string, string> {
 const token = document.cookie.split('; ').find(x => x.startsWith('XSRF-TOKEN='))?.slice(11);
 return token ? {'X-XSRF-TOKEN': decodeURIComponent(token)} : {};
}"""
    + s[end:]
)
headers.write_text(s)
# The company backend has no model-loading, external tools or browser MCP endpoints.
chat = app / "src/lib/services/chat.service.ts"
s = chat.read_text()
# Pre-encoding is a llama inference optimization and must not create paid RAG calls.
pos = s.index("static async preEncode(")
body = s.index("\n\t\tconst normalizedMessages", pos)
if "return null; // company-rag" not in s[pos : body + 70]:
    s = s[:body] + "\n\t\treturn null; // company-rag: no local KV cache\n" + s[body:]
chat.write_text(s)
form = app / "src/lib/components/app/chat/ChatForm/ChatForm.svelte"
s = form.read_text().replace("{showAddButton}", "showAddButton={false}")
form.write_text(s)
# No offline service worker: authenticated company content must never be served offline.
hook = app / "src/lib/hooks/use-pwa.svelte.ts"
if hook.exists():
    s = hook.read_text()
    s = (
        s
        if "company-rag: disable offline caching" in s
        else s.replace(
            "if (dev || !('serviceWorker' in navigator)) return;",
            "return; // company-rag: disable offline caching\n\t\tif (dev || !('serviceWorker' in navigator)) return;",
        )
    )
    hook.write_text(s)
subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=app, check=True)
subprocess.run(["npm", "run", "build"], cwd=app, check=True)
out = root / "dist"
shutil.rmtree(out, ignore_errors=True)
shutil.copytree(app / "dist", out)
index = out / "index.html"
s = index.read_text()
s = s.replace(
    "<head>",
    '<head><script src="/chat/session.js"></script><script src="/chat/guard.js"></script>',
    1,
)
index.write_text(s)
# Preserve upstream attribution with the shipped build.
shutil.copy2(source / "LICENSE", out / "UPSTREAM-LICENSE.txt")
