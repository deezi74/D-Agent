# D-Agent PC

The always-on computer for the D-Agent app. It runs on your Windows, Linux or Mac computer, and the phone hands it work over your home Wi-Fi (or Tailscale):

- **Long tasks** that keep running when the phone sleeps or the app is closed. The phone gets a notification when they finish.
- **Commands** in PowerShell (Windows) or bash, and **files** in a workspace folder (`~/D-Agent`).
- **Web search and reading** pages.
- **Your own AI model** through LM Studio or Ollama on the same computer, or any OpenAI-compatible provider.

It uses only Python's standard library.

## Install

**Windows 10/11.** Open PowerShell and run:

```powershell
irm https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.ps1 | iex
```

**Linux, Chromebook (Crostini) or macOS:**

```bash
curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash
```

When it finishes, it prints a **pairing code** and the **address**. In D-Agent, go to Settings > Connectors > Your PC, enter both, and tap Test.

D-Agent PC listens only on `127.0.0.1` (this computer) by default. To let your phone reach it over the home network or Tailscale, run `python ~/.dagent-pc/dagent_pc.py --lan` once (the choice is saved in `config.json`, key `bind`) and restart it. Use `--host <address>` to listen on one specific address instead. Note: the D-Agent app only sends plain `http://` to `localhost`, `127.0.0.1` and `10.0.2.2` unless the PC's address is added to the app's network security config, so for a PC on your network prefer an HTTPS reverse proxy or Tailscale HTTPS (see the main README, "Plain HTTP").

## The AI model

D-Agent PC uses `http://localhost:1234/v1` by default, which is LM Studio's server.

- **LM Studio:** in the Developer tab, start the server. For an AMD card on Windows, choose the Vulkan runtime.
- **Ollama:** set `llm.base_url` to `http://localhost:11434/v1`.
- If `llm.model` is empty, D-Agent PC uses the first model the server lists.

You can change these from the phone (Settings > Connectors > Your PC), or by editing `config.json`.

## Files

| Path | What it is |
|---|---|
| `~/.dagent-pc/config.json` | Port, pairing code, workspace, model settings, `allow_shell` |
| `~/.dagent-pc/dagent-pc.log` | Log |
| `~/.dagent-pc/tasks/` | Saved tasks and their steps |
| `~/D-Agent/` | Workspace for anything tasks make |

## Commands

```
python dagent_pc.py              # run the server
python dagent_pc.py --code       # show the pairing code and addresses
python dagent_pc.py --new-code   # new pairing code (pair the phone again)
python dagent_pc.py --lan        # listen on the home network (saved; default is 127.0.0.1 only)
python dagent_pc.py --host ADDR  # listen on one address (saved); --bind is the old name
python dagent_pc.py --task "..." # run one task in the console
```

## Safety

- Every request needs the pairing code (12 characters, `XXXX-XXXX-XXXX`, from Python's `secrets`).
- After 5 wrong codes within a minute, that IP address is locked out for 5 minutes (HTTP 429).
- It listens only on this computer unless you start it with `--lan` or `--host`.
- Files can only be read and written inside the workspace.
- Commands that could wipe or break the computer are refused, such as `format`, `diskpart`, `rm -rf /` and `shutdown`.
- Tasks are told never to buy, send messages, post, or sign in to accounts.
- To turn off commands completely, set `"allow_shell": false`.
- Keep it on your home network or Tailscale. Don't forward port 8787 on your router.

## Uninstall

- **Windows:** delete `D-Agent PC` from `shell:startup`, end the `pythonw` process, then delete `%USERPROFILE%\.dagent-pc`.
- **Linux:** run `systemctl --user disable --now dagent-pc`, then `rm -rf ~/.dagent-pc ~/.config/systemd/user/dagent-pc.service`.

## On the phone itself (Termux)

No PC needed: D-Agent PC also runs inside Termux, so the phone is its own always-on computer.
In D-Agent open Settings ▸ Connectors ▸ Always-on computer ▸ **This phone** and tap
**Set up on this phone** (the Termux connector must be on). Or by hand in Termux:

```bash
curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash
```

On a phone it listens only on 127.0.0.1, uses FreeLLMAPI (`http://localhost:3001/v1`) until the
app hands it another model ("Use the app's AI"), and holds a Termux wake lock. Install
Termux:Boot from F-Droid to start it when the phone turns on; `bash ~/.dagent-pc/start.sh` starts it by hand.

## Reverse engineering with Ghidra

The worker has a `ghidra` tool. Ask from the phone, for example: "On my PC, decompile crackme.exe with Ghidra and explain main".
The first time, it downloads Ghidra (from github.com/NationalSecurityAgency/ghidra) and a Java 21 runtime (Eclipse Temurin)
into `~/.dagent-pc/tools` (about 600 MB, free). Results are saved as C code under `reverse/` in the workspace.
Use it only on your own files, CTFs, practice crackmes or software you're allowed to analyze.
