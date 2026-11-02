#!/usr/bin/env python3
"""
D-Agent PC: the always-on computer for D-Agent.

Runs on your Windows, Linux or macOS computer. The D-Agent app on your phone connects to it over
your home Wi-Fi (or Tailscale) and can:
  * hand it long tasks that keep running when the phone is asleep or the app is closed,
  * run commands, read and write files in its workspace, and read the web,
  * use the AI model on this computer (LM Studio or Ollama) or any OpenAI-compatible provider.

Only Python's standard library is used, so there is nothing else to install.

    python dagent_pc.py            start the server (prints the pairing code)
    python dagent_pc.py --code     show the pairing code and addresses
    python dagent_pc.py --new-code make a new pairing code (the phone must be paired again)
    python dagent_pc.py --task "..."   run one task here in the console (for testing)
"""
from __future__ import annotations

import argparse
import html
import json
import os
import platform
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1.1.0"
HOME = Path(os.environ.get("DAGENT_PC_HOME", Path.home() / ".dagent-pc"))
CONFIG_FILE = HOME / "config.json"
TASKS_DIR = HOME / "tasks"
LOG_FILE = HOME / "dagent-pc.log"
IS_WINDOWS = platform.system() == "Windows"
# Running inside Termux on an Android phone: the phone is its own always-on computer.
IS_TERMUX = "com.termux" in os.environ.get("PREFIX", "") or "TERMUX_VERSION" in os.environ
OS_NAME = f"Android {platform.release()} (Termux)" if IS_TERMUX else f"{platform.system()} {platform.release()}"

DEFAULT_CONFIG = {
    "port": 8787,
    "token": "",
    "name": socket.gethostname(),
    "workspace": str(Path.home() / "D-Agent"),
    "allow_shell": True,
    "max_steps": 40,
    # On a phone only D-Agent on the same phone may connect; a PC listens on the home network.
    "bind": "127.0.0.1" if IS_TERMUX else "0.0.0.0",
    "llm": {
        # LM Studio's server on a PC; FreeLLMAPI on a phone. For Ollama use http://localhost:11434/v1.
        "base_url": "http://localhost:3001/v1" if IS_TERMUX else "http://localhost:1234/v1",
        "api_key": "",
        # Empty: use the first model the server lists.
        "model": "auto" if IS_TERMUX else "",
    },
}

_lock = threading.Lock()


def log(msg: str) -> None:
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}"
    try:
        print(line, flush=True)
    except Exception:
        pass
    try:
        HOME.mkdir(parents=True, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


# ---- Config ----------------------------------------------------------------------------------


def new_code() -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(12))
    return f"{raw[:4]}-{raw[4:8]}-{raw[8:]}"


def load_config() -> dict:
    HOME.mkdir(parents=True, exist_ok=True)
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if CONFIG_FILE.exists():
        try:
            saved = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            llm = {**cfg["llm"], **saved.get("llm", {})}
            cfg.update(saved)
            cfg["llm"] = llm
        except Exception as e:  # a broken file should not stop the server
            log(f"config.json could not be read ({e}); using defaults")
    if not cfg.get("token"):
        cfg["token"] = new_code()
        save_config(cfg)
    Path(cfg["workspace"]).expanduser().mkdir(parents=True, exist_ok=True)
    return cfg


def save_config(cfg: dict) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    os.replace(tmp, CONFIG_FILE)
    if not IS_WINDOWS:
        try:
            os.chmod(CONFIG_FILE, 0o600)
        except OSError:
            pass


def local_addresses() -> list[str]:
    found = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        found.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except Exception:
        pass
    return sorted(a for a in found if not a.startswith("127."))


# ---- Model -----------------------------------------------------------------------------------


class Llm:
    def __init__(self, cfg: dict):
        self.base = cfg["base_url"].rstrip("/")
        self.key = cfg.get("api_key", "")
        self.model = cfg.get("model", "")

    def _req(self, path: str, body: dict | None = None, timeout: int = 900) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.key:
            headers["Authorization"] = f"Bearer {self.key}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method="POST" if body is not None else "GET")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:500]
            raise RuntimeError(f"The model server answered {e.code}: {detail}") from None
        except urllib.error.URLError as e:
            raise RuntimeError(
                f"Can't reach the model at {self.base} ({e.reason}). " + (
                    "Start FreeLLMAPI in Termux, or pick another AI in D-Agent (Your PC > Use the app's AI)." if IS_TERMUX
                    else "Start LM Studio's server (Developer tab) or Ollama.")
            ) from None

    def models(self) -> list[str]:
        out = self._req("/models", timeout=15)
        return [m.get("id", "") for m in out.get("data", []) if m.get("id")]

    def pick_model(self) -> str:
        if self.model:
            return self.model
        names = [m for m in self.models() if "embed" not in m.lower()]
        if not names:
            raise RuntimeError("The model server has no model loaded. Load one in LM Studio (or pull one in Ollama).")
        self.model = names[0]
        return self.model

    def chat(self, messages: list, tools: list | None) -> dict:
        body = {"model": self.pick_model(), "messages": messages, "temperature": 0.3, "max_tokens": 4096}
        if tools:
            body["tools"] = tools
        out = self._req("/chat/completions", body)
        choice = (out.get("choices") or [{}])[0]
        return choice.get("message") or {}


# ---- Tools -----------------------------------------------------------------------------------

BLOCKED_COMMANDS = [
    r"\bformat(\.com)?\s+[a-z]:",
    r"\bdiskpart\b",
    r"\bmkfs(\.\w+)?\b",
    r"\bdd\s+.*\bof=/dev/",
    r"\brm\s+-[a-z]*r[a-z]*f?[a-z]*\s+(/|~|\$HOME)(\s|$)",
    r"remove-item\s+.*-recurse.*\s[a-z]:\\?(\s|$|['\"])",
    r"\b(rd|rmdir)\s+/s\s+.*[a-z]:\\?(\s|$)",
    r"\bdel\s+/[sq].*[a-z]:\\(windows|users)?\s*$",
    r"\bbcdedit\b",
    r"\bshutdown\b",
    r"\breg\s+delete\s+hk(lm|ey_local_machine)",
    r"\bcipher\s+/w",
    r"set-executionpolicy\s+unrestricted",
]


def blocked(command: str) -> str | None:
    c = command.lower()
    for pat in BLOCKED_COMMANDS:
        if re.search(pat, c):
            return "That command could wipe or break this computer, so D-Agent PC won't run it. Do it by hand if you really mean it."
    return None


def run_shell(command: str, cwd: Path, timeout: int = 120) -> tuple[int, str]:
    reason = blocked(command)
    if reason:
        return 1, reason
    if IS_WINDOWS:
        argv = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command]
    else:
        argv = ["bash", "-lc", command]
    try:
        p = subprocess.run(
            argv, cwd=str(cwd), capture_output=True, timeout=max(5, min(timeout, 3600)),
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if IS_WINDOWS else 0,
        )
        out = (p.stdout or b"").decode("utf-8", "replace") + (p.stderr or b"").decode("utf-8", "replace")
        return p.returncode, clip(out.strip() or "(no output)")
    except subprocess.TimeoutExpired as e:
        partial = (e.stdout or b"").decode("utf-8", "replace") if isinstance(e.stdout, bytes) else ""
        return 124, clip(f"Stopped after {timeout} s (still running output below).\n{partial}")
    except FileNotFoundError as e:
        return 127, f"Couldn't start the shell: {e}"


def clip(text: str, limit: int = 12000) -> str:
    if len(text) <= limit:
        return text
    head, tail = text[: limit * 2 // 3], text[-limit // 3:]
    return f"{head}\n\n[... {len(text) - len(head) - len(tail)} characters cut ...]\n\n{tail}"


def inside(workspace: Path, path: str) -> Path:
    p = Path(path).expanduser()
    if not p.is_absolute():
        p = workspace / p
    p = p.resolve()
    ws = workspace.resolve()
    if p != ws and ws not in p.parents:
        raise ValueError(f"Only files inside the workspace ({ws}) can be used.")
    return p


def strip_html(page: str) -> str:
    page = re.sub(r"(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\1>", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h[1-6]|tr)>", "\n", page)
    page = re.sub(r"(?s)<[^>]+>", " ", page)
    page = html.unescape(page)
    page = re.sub(r"[ \t\r\f\v]+", " ", page)
    return re.sub(r"\n\s*\n+", "\n\n", page).strip()


UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"


def web_fetch(url: str) -> str:
    if not re.match(r"^https?://", url):
        url = "https://" + url
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    with urllib.request.urlopen(req, timeout=30) as r:
        ctype = r.headers.get("Content-Type", "")
        raw = r.read(3_000_000)
    text = raw.decode("utf-8", "replace")
    return clip(strip_html(text) if "html" in ctype or text.lstrip().startswith("<") else text)


def web_search(query: str) -> str:
    url = "https://html.duckduckgo.com/html/?" + urllib.parse.urlencode({"q": query, "kl": "us-en"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        page = r.read().decode("utf-8", "replace")
    results = []
    for m in re.finditer(r'(?s)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</a>', page):
        href = html.unescape(m.group(1))
        q = urllib.parse.parse_qs(urllib.parse.urlparse(href).query).get("uddg")
        link = q[0] if q else href
        results.append(f"{strip_html(m.group(2))}\n{link}\n{strip_html(m.group(3))}")
        if len(results) >= 8:
            break
    return "\n\n".join(results) or "No results."


def tool_specs() -> list:
    def fn(name, desc, props, req):
        return {"type": "function", "function": {"name": name, "description": desc, "parameters": {"type": "object", "properties": props, "required": req}}}

    s = {"type": "string"}
    shell_name = "PowerShell" if IS_WINDOWS else "bash"
    return [
        fn("shell", f"Run a {shell_name} command on this computer, in the workspace folder. Returns the output.",
           {"command": s, "timeout": {"type": "integer", "description": "Seconds, default 120, max 3600."}}, ["command"]),
        fn("read_file", "Read a text file in the workspace.", {"path": s}, ["path"]),
        fn("write_file", "Create or overwrite a text file in the workspace.", {"path": s, "content": s}, ["path", "content"]),
        fn("list_files", "List files in a workspace folder.", {"path": s}, []),
        fn("web_search", "Search the web. Returns titles, links and snippets.", {"query": s}, ["query"]),
        fn("web_fetch", "Read a web page as text.", {"url": s}, ["url"]),
    ]


# ---- Tasks -----------------------------------------------------------------------------------


def system_prompt(cfg: dict) -> str:
    os_name = OS_NAME
    shell = "PowerShell" if IS_WINDOWS else "bash"
    return (
        f"You are D-Agent's computer: an AI agent running on the owner's own {os_name} PC named {cfg['name']}. "
        "The owner sent you this task from the D-Agent app on their phone and is not watching, so work on your own "
        "until it's done; you can't ask questions. Use the tools step by step, check your results, and fix mistakes.\n\n"
        f"Commands run in {shell} with the workspace folder {cfg['workspace']} as the current directory. Save anything "
        "you make (reports, code, downloads) in the workspace so the owner can get it from the phone.\n\n"
        "Rules: never buy anything, send messages or email, post online, sign in to accounts, or change system "
        "settings, and don't delete files you didn't create. If the task needs one of those, stop and explain what the "
        "owner should do. Today's date is " + time.strftime("%A, %B %d, %Y") + ".\n\n"
        "When you finish, reply with a short, plain summary for the owner: what you did, what you found, and the "
        "names of any files you saved."
    )


TOOL_TAG = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.S)


class Task:
    def __init__(self, title: str, prompt: str, tid: str | None = None):
        self.id = tid or uuid.uuid4().hex[:10]
        self.title = title or prompt[:60]
        self.prompt = prompt
        self.status = "queued"  # queued, running, done, failed, cancelled
        self.result = ""
        self.steps: list[dict] = []
        self.created = time.time()
        self.updated = self.created
        self.cancel = threading.Event()
        self.model = ""

    def to_json(self, steps: bool = True) -> dict:
        out = {
            "id": self.id, "title": self.title, "prompt": self.prompt, "status": self.status,
            "result": self.result, "created": int(self.created * 1000), "updated": int(self.updated * 1000),
            "model": self.model, "step_count": len(self.steps),
        }
        if steps:
            out["steps"] = self.steps[-60:]
        return out

    def step(self, kind: str, title: str, detail: str = "") -> None:
        self.steps.append({"t": int(time.time() * 1000), "kind": kind, "title": title[:200], "detail": detail[:1500]})
        self.updated = time.time()
        self.save()

    def save(self) -> None:
        try:
            TASKS_DIR.mkdir(parents=True, exist_ok=True)
            data = self.to_json()
            data["steps"] = self.steps[-200:]
            tmp = TASKS_DIR / f"{self.id}.tmp"
            tmp.write_text(json.dumps(data), encoding="utf-8")
            os.replace(tmp, TASKS_DIR / f"{self.id}.json")
        except Exception as e:
            log(f"couldn't save task {self.id}: {e}")

    @staticmethod
    def load(path: Path) -> "Task":
        d = json.loads(path.read_text(encoding="utf-8"))
        t = Task(d.get("title", ""), d.get("prompt", ""), d["id"])
        t.status = d.get("status", "failed")
        if t.status in ("queued", "running"):
            t.status = "failed"
            t.result = "D-Agent PC was restarted while this task was running. Send it again to start over."
        else:
            t.result = d.get("result", "")
        t.steps = d.get("steps", [])
        t.created = d.get("created", 0) / 1000
        t.updated = d.get("updated", 0) / 1000
        t.model = d.get("model", "")
        return t


class Worker:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.tasks: dict[str, Task] = {}
        self.queue: list[Task] = []
        self.cv = threading.Condition()
        TASKS_DIR.mkdir(parents=True, exist_ok=True)
        for f in sorted(TASKS_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime)[-200:]:
            try:
                t = Task.load(f)
                self.tasks[t.id] = t
                t.save()
            except Exception:
                pass
        # Two at a time: a local model is busy enough with that.
        for i in range(2):
            threading.Thread(target=self._loop, name=f"task-{i}", daemon=True).start()

    @property
    def workspace(self) -> Path:
        return Path(self.cfg["workspace"]).expanduser()

    def submit(self, title: str, prompt: str) -> Task:
        t = Task(title, prompt)
        t.save()
        with self.cv:
            self.tasks[t.id] = t
            self.queue.append(t)
            self.cv.notify()
        log(f"task {t.id} queued: {t.title}")
        return t

    def _loop(self) -> None:
        while True:
            with self.cv:
                while not self.queue:
                    self.cv.wait()
                t = self.queue.pop(0)
            if t.cancel.is_set():
                continue
            try:
                self.run(t)
            except Exception as e:
                t.status = "failed"
                t.result = str(e)
                t.step("error", "Stopped by an error", traceback.format_exc()[-1200:])
            t.updated = time.time()
            t.save()
            log(f"task {t.id} {t.status}")

    def run(self, t: Task) -> None:
        t.status = "running"
        llm = Llm(self.cfg["llm"])
        t.model = llm.pick_model()
        t.step("note", f"Started with {t.model}")
        messages = [{"role": "system", "content": system_prompt(self.cfg)}, {"role": "user", "content": t.prompt}]
        tools = tool_specs()
        native = True
        repeats: dict[str, int] = {}
        for step in range(int(self.cfg.get("max_steps", 40))):
            if t.cancel.is_set():
                t.status, t.result = "cancelled", "Cancelled from the phone."
                return
            last = step == int(self.cfg.get("max_steps", 40)) - 1
            if last:
                messages.append({"role": "user", "content": "You're out of steps. Don't call tools. Summarize what you finished and what's left."})
            try:
                msg = llm.chat(messages, None if last else (tools if native else None))
            except RuntimeError as e:
                if native and re.search(r"tool", str(e), re.I):
                    native = False  # this model or server doesn't take tools; describe them in text instead
                    messages[0]["content"] += "\n\n" + text_tool_manual(tools)
                    continue
                raise
            content = strip_think(msg.get("content") or "")
            calls = msg.get("tool_calls") or []
            if not calls:
                calls = [{"id": f"t{step}_{i}", "type": "function", "function": {"name": c.get("name", ""), "arguments": json.dumps(c.get("arguments", {}))}}
                         for i, c in enumerate(parse_text_calls(content))]
                if calls:
                    content = TOOL_TAG.sub("", content).strip()
            if not calls:
                t.status, t.result = "done", content.strip() or "Finished."
                return
            messages.append({"role": "assistant", "content": content or None, "tool_calls": calls})
            if content:
                t.step("text", content[:200], content)
            for c in calls:
                f = c.get("function", {})
                name, raw = f.get("name", ""), f.get("arguments") or "{}"
                key = name + raw
                repeats[key] = repeats.get(key, 0) + 1
                try:
                    args = json.loads(raw) if isinstance(raw, str) else raw
                except json.JSONDecodeError:
                    args = None
                if args is None:
                    out = "Error: the arguments were not valid JSON."
                elif repeats[key] > 3:
                    out = "Error: you already made this exact call 3 times. Try something else or finish."
                else:
                    out = self.call_tool(name, args)
                t.step("tool", f"{name}: {summarize(args)}", out)
                messages.append({"role": "tool", "tool_call_id": c.get("id", name), "content": clip(out)})
        t.status = "done"
        t.result = t.result or "Stopped after the step limit."

    def call_tool(self, name: str, a: dict) -> str:
        ws = self.workspace
        try:
            if name == "shell":
                if not self.cfg.get("allow_shell", True):
                    return "Error: commands are turned off in config.json (allow_shell)."
                code, out = run_shell(str(a.get("command", "")), ws, int(a.get("timeout") or 120))
                return out if code == 0 else f"(exit code {code})\n{out}"
            if name == "read_file":
                return clip(inside(ws, a["path"]).read_text(encoding="utf-8", errors="replace"))
            if name == "write_file":
                p = inside(ws, a["path"])
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(a.get("content", ""), encoding="utf-8")
                return f"Saved {p} ({p.stat().st_size} bytes)."
            if name == "list_files":
                return list_dir(inside(ws, a.get("path") or "."), ws)
            if name == "web_search":
                return web_search(a["query"])
            if name == "web_fetch":
                return web_fetch(a["url"])
            return f"Error: there's no tool named {name}."
        except KeyError as e:
            return f"Error: missing argument {e}."
        except Exception as e:
            return f"Error: {type(e).__name__}: {e}"


def list_dir(p: Path, ws: Path) -> str:
    if not p.exists():
        return f"Error: {p} doesn't exist."
    rows = []
    for c in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower()))[:300]:
        rel = c.relative_to(ws.resolve()) if ws.resolve() in c.resolve().parents or c.resolve() == ws.resolve() else c
        rows.append(f"{rel}/" if c.is_dir() else f"{rel}  ({c.stat().st_size} bytes)")
    return "\n".join(rows) or "(empty folder)"


def summarize(args) -> str:
    if not isinstance(args, dict):
        return ""
    for k in ("command", "query", "url", "path"):
        if args.get(k):
            return str(args[k])[:150]
    return json.dumps(args)[:150]


def strip_think(text: str) -> str:
    return re.sub(r"(?s)<think>.*?(</think>|$)", "", text).strip()


def parse_text_calls(text: str) -> list[dict]:
    out = []
    for m in TOOL_TAG.finditer(text):
        try:
            d = json.loads(m.group(1))
            if isinstance(d, dict) and d.get("name"):
                out.append({"name": d["name"], "arguments": d.get("arguments") or d.get("parameters") or {}})
        except json.JSONDecodeError:
            pass
    return out


def text_tool_manual(tools: list) -> str:
    lines = ["To use a tool, write exactly:", '<tool_call>{"name": "TOOL", "arguments": {...}}</tool_call>', "Tools:"]
    for t in tools:
        f = t["function"]
        lines.append(f"- {f['name']}({', '.join(f['parameters']['properties'])}): {f['description']}")
    return "\n".join(lines)


# ---- HTTP API --------------------------------------------------------------------------------


class Api(BaseHTTPRequestHandler):
    server_version = f"DAgentPC/{VERSION}"
    worker: Worker  # set at startup

    def log_message(self, fmt, *args):  # quiet; errors go to our log
        pass

    def _send(self, code: int, body, ctype: str = "application/json") -> None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authed(self) -> bool:
        want = self.worker.cfg["token"]
        got = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if got and secrets.compare_digest(got.upper(), want.upper()):
            return True
        time.sleep(0.5)  # slows down guessing
        self._send(401, {"error": "Wrong or missing pairing code. Check it with: python dagent_pc.py --code"})
        return False

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 5_000_000:
            raise ValueError("Request too large")
        raw = self.rfile.read(n) if n else b"{}"
        return json.loads(raw or b"{}")

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        if url.path == "/":
            return self._send(200, {"app": "dagent-pc", "version": VERSION})
        if not self._authed():
            return
        w = self.worker
        try:
            if url.path == "/health":
                llm = Llm(w.cfg["llm"])
                try:
                    models, problem = llm.models(), ""
                except Exception as e:
                    models, problem = [], str(e)
                running = sum(1 for t in w.tasks.values() if t.status in ("queued", "running"))
                return self._send(200, {
                    "app": "dagent-pc", "version": VERSION, "name": w.cfg["name"],
                    "os": OS_NAME, "workspace": str(w.workspace),
                    "llm": {"base_url": llm.base, "model": w.cfg["llm"].get("model") or (models[0] if models else ""), "models": models[:50], "problem": problem},
                    "running": running,
                })
            if url.path == "/tasks":
                items = sorted(w.tasks.values(), key=lambda t: t.created, reverse=True)[:50]
                return self._send(200, {"tasks": [t.to_json(steps=False) for t in items]})
            m = re.fullmatch(r"/tasks/([\w-]+)", url.path)
            if m:
                t = w.tasks.get(m.group(1))
                return self._send(200, t.to_json()) if t else self._send(404, {"error": "No such task."})
            if url.path == "/files":
                p = inside(w.workspace, (q.get("path") or ["."])[0])
                if p.is_dir():
                    return self._send(200, {"path": str(p), "listing": list_dir(p, w.workspace)})
                if not p.exists():
                    return self._send(404, {"error": f"{p.name} doesn't exist."})
                if p.stat().st_size > 50_000_000:
                    return self._send(413, {"error": "That file is over 50 MB."})
                return self._send(200, p.read_bytes(), "application/octet-stream")
            return self._send(404, {"error": "Not found"})
        except ValueError as e:
            return self._send(400, {"error": str(e)})
        except Exception as e:
            log(f"GET {url.path} failed: {e}")
            return self._send(500, {"error": str(e)})

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if not self._authed():
            return
        w = self.worker
        try:
            b = self._body()
            if url.path == "/run":
                if not w.cfg.get("allow_shell", True):
                    return self._send(403, {"error": "Commands are turned off in config.json (allow_shell)."})
                code, out = run_shell(str(b.get("command", "")), w.workspace, int(b.get("timeout") or 120))
                return self._send(200, {"exit": code, "output": out})
            if url.path == "/tasks":
                prompt = str(b.get("prompt", "")).strip()
                if not prompt:
                    return self._send(400, {"error": "Say what the task is."})
                t = w.submit(str(b.get("title", "")).strip(), prompt)
                return self._send(200, t.to_json())
            m = re.fullmatch(r"/tasks/([\w-]+)/cancel", url.path)
            if m:
                t = w.tasks.get(m.group(1))
                if not t:
                    return self._send(404, {"error": "No such task."})
                t.cancel.set()
                if t.status == "queued":
                    t.status, t.result = "cancelled", "Cancelled from the phone."
                    t.save()
                return self._send(200, t.to_json(steps=False))
            if url.path == "/files":
                p = inside(w.workspace, str(b.get("path", "")))
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(str(b.get("content", "")), encoding="utf-8")
                return self._send(200, {"saved": str(p)})
            if url.path == "/config":
                with _lock:
                    llm = b.get("llm") or {}
                    for k in ("base_url", "api_key", "model"):
                        if k in llm:
                            w.cfg["llm"][k] = str(llm[k])
                    save_config(w.cfg)
                return self._send(200, {"llm": {k: v for k, v in w.cfg["llm"].items() if k != "api_key"}})
            return self._send(404, {"error": "Not found"})
        except (ValueError, json.JSONDecodeError) as e:
            return self._send(400, {"error": str(e)})
        except Exception as e:
            log(f"POST {url.path} failed: {e}")
            return self._send(500, {"error": str(e)})


def show_code(cfg: dict) -> None:
    port = cfg["port"]
    print()
    print("  D-Agent PC is ready")
    print("  -------------------")
    print(f"  Pairing code:  {cfg['token']}")
    if cfg.get("bind", "0.0.0.0").startswith("127."):
        print(f"  Address:       http://127.0.0.1:{port}  (only apps on this device)")
    else:
        for a in local_addresses() or ["<this PC's IP>"]:
            print(f"  Address:       http://{a}:{port}")
    print()
    print("  In D-Agent: Settings > Connectors > Always-on computer. Enter the address and the pairing code.")
    hint = "FreeLLMAPI, or tap Use the app's AI in D-Agent" if IS_TERMUX else "LM Studio: Developer tab > Start server"
    print(f"  Model server:  {cfg['llm']['base_url']}  ({hint})")
    print()


def main() -> None:
    ap = argparse.ArgumentParser(description="D-Agent PC: the always-on computer for D-Agent.")
    ap.add_argument("--code", action="store_true", help="show the pairing code and addresses")
    ap.add_argument("--new-code", action="store_true", help="make a new pairing code")
    ap.add_argument("--task", help="run one task in this console and print the result")
    ap.add_argument("--port", type=int, help="port to listen on (default 8787)")
    ap.add_argument("--token", action="store_true", help="print only the pairing code (used by the D-Agent app)")
    ap.add_argument("--bind", help="address to listen on: 0.0.0.0 (home network) or 127.0.0.1 (this device only)")
    args = ap.parse_args()

    cfg = load_config()
    if args.port:
        cfg["port"] = args.port
        save_config(cfg)
    if args.bind:
        cfg["bind"] = args.bind
        save_config(cfg)
    if args.token:
        print(cfg["token"])
        return
    if args.new_code:
        cfg["token"] = new_code()
        save_config(cfg)
        show_code(cfg)
        return
    if args.code:
        show_code(cfg)
        return
    if args.task:
        w = Worker.__new__(Worker)
        w.cfg, w.tasks = cfg, {}
        t = Task("console", args.task)
        w.run(t)
        for s in t.steps:
            print(f"- {s['title']}")
        print(f"\n[{t.status}] {t.result}")
        return

    Api.worker = Worker(cfg)
    try:
        httpd = ThreadingHTTPServer((cfg.get("bind") or "0.0.0.0", int(cfg["port"])), Api)
    except OSError as e:
        log(f"Port {cfg['port']} is busy ({e}). Is D-Agent PC already running?")
        sys.exit(1)
    show_code(cfg)
    log(f"listening on port {cfg['port']}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
