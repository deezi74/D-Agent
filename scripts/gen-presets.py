#!/usr/bin/env python3
"""Draws D-Agent's built-in characters (one picture per mood) with Pollinations and saves
them as 512px WebP under app/src/main/assets/characters/<id>/<mood>.webp. Run in CI, where
the image service is reachable. Existing files are kept unless --force is given."""
import io, json, os, sys, time, urllib.parse, urllib.request
from PIL import Image

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cfg = json.load(open(os.path.join(root, "scripts", "presets.json")))
out_root = os.path.join(root, "app", "src", "main", "assets", "characters")
force = "--force" in sys.argv
only = [a for a in sys.argv[1:] if not a.startswith("--")]
key = os.environ.get("POLLINATIONS_KEY", "")

def fetch(prompt, seed):
    url = "https://image.pollinations.ai/prompt/" + urllib.parse.quote(prompt) + \
        f"?width=768&height=768&seed={seed}&model=flux&nologo=true&private=true"
    for attempt in range(10):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "D-Agent-presets"})
            if key: req.add_header("Authorization", "Bearer " + key)
            with urllib.request.urlopen(req, timeout=240) as r:
                if not r.headers.get("Content-Type", "").startswith("image"):
                    raise IOError("not an image: " + r.headers.get("Content-Type", ""))
                return r.read()
        except Exception as e:
            print("  retry", attempt + 1, e, flush=True)
            time.sleep(min(30 * (attempt + 1), 150))
    print("  gave up on: " + prompt[:80], flush=True)
    return None

index = []
failed = []
for c in cfg["characters"]:
    if only and c["id"] not in only: continue
    d = os.path.join(out_root, c["id"])
    os.makedirs(d, exist_ok=True)
    for mood, pose in cfg["moods"].items():
        path = os.path.join(d, mood + ".webp")
        if os.path.exists(path) and not force: continue
        prompt = f"{c['look']}, {pose}. {cfg['style']}"
        print(c["id"], mood, flush=True)
        data = fetch(prompt, c["seed"])
        if data is None:
            failed.append(f"{c['id']}/{mood}")
            continue
        img = Image.open(io.BytesIO(data)).convert("RGB").resize((512, 512), Image.LANCZOS)
        img.save(path, "WEBP", quality=88, method=6)
        # The free tier allows about one picture every 15 seconds without a key.
        if not key: time.sleep(18)
for c in cfg["characters"]:
    index.append({"id": c["id"], "name": c["name"], "look": c["look"]})
json.dump(index, open(os.path.join(out_root, "index.json"), "w"), indent=1)
print("done; missing:", ", ".join(failed) if failed else "none")
