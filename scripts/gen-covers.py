#!/usr/bin/env python3
"""Draws a banner picture for every Pal (built-in and D's Hub) with Pollinations and saves it
as docs/hub/covers/<id>.webp (768x400). Runs in CI. Existing files are kept unless --force."""
import io, json, os, sys, time, urllib.parse, urllib.request
from PIL import Image

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cfg = json.load(open(os.path.join(root, "scripts", "covers.json")))
out = os.path.join(root, "docs", "hub", "covers")
os.makedirs(out, exist_ok=True)
force = "--force" in sys.argv
only = [a for a in sys.argv[1:] if not a.startswith("--")]
key = os.environ.get("POLLINATIONS_KEY", "")

def fetch(prompt, seed):
    url = "https://image.pollinations.ai/prompt/" + urllib.parse.quote(prompt) + \
        f"?width=1152&height=600&seed={seed}&model=flux&nologo=true&private=true"
    for attempt in range(10):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "D-Agent-covers"})
            if key: req.add_header("Authorization", "Bearer " + key)
            with urllib.request.urlopen(req, timeout=240) as r:
                if not r.headers.get("Content-Type", "").startswith("image"):
                    raise IOError("not an image: " + r.headers.get("Content-Type", ""))
                return r.read()
        except Exception as e:
            print("  retry", attempt + 1, e, flush=True)
            time.sleep(min(30 * (attempt + 1), 150))
    return None

failed = []
for pid, c in cfg["covers"].items():
    if only and pid not in only: continue
    path = os.path.join(out, pid + ".webp")
    if os.path.exists(path) and not force: continue
    print(pid, flush=True)
    data = fetch(f"{c['art']}. {cfg['style']}", c["seed"])
    if data is None:
        failed.append(pid); continue
    img = Image.open(io.BytesIO(data)).convert("RGB")
    w, h = img.size
    target = 768 / 400
    if w / h > target:
        nw = int(h * target); img = img.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w / target); img = img.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
    img.resize((768, 400), Image.LANCZOS).save(path, "WEBP", quality=82, method=6)
    if not key: time.sleep(18)
print("done; missing:", ", ".join(failed) if failed else "none")
