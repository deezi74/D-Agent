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
python dagent_pc.py --task "..." # run one task in the console
```

## Safety

- Every request needs the pairing code.
- Files can only be read and written inside the workspace.
- Commands that could wipe or break the computer are refused, such as `format`, `diskpart`, `rm -rf /` and `shutdown`.
- Tasks are told never to buy, send messages, post, or sign in to accounts.
- To turn off commands completely, set `"allow_shell": false`.
- Keep it on your home network or Tailscale. Don't forward port 8787 on your router.

## Uninstall

- **Windows:** delete `D-Agent PC` from `shell:startup`, end the `pythonw` process, then delete `%USERPROFILE%\.dagent-pc`.
- **Linux:** run `systemctl --user disable --now dagent-pc`, then `rm -rf ~/.dagent-pc ~/.config/systemd/user/dagent-pc.service`.

## Old laptop as an always-on box (Linux, 4 GB is enough)

`laptop-setup.sh` installs D-Agent PC plus the rest of a free, card-free helper box:

- **FreeLLMAPI** on port 3001 (production build, runs as a service). The worker uses it as its model, and the phone can use it too, which takes it off the phone's battery.
- **Freebuff**, the free coding agent CLI.
- **Tailscale**, to reach everything from anywhere.
- **Server settings**: zram swap, closing the lid doesn't sleep it, services survive logout. `--headless` boots to text mode.

```bash
curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/laptop-setup.sh | bash
# options (download first to pass them): --llm pc   --headless   --skip-freebuff   --skip-tailscale   --status
```

No AI model runs on the laptop. Use `--llm pc` to send the worker's tasks to LM Studio on the PC instead (`PC_IP=192.168.1.113` by default; override with `PC_IP=... bash laptop-setup.sh`).

After it runs:
1. Open `http://localhost:3001` in the laptop's browser. Create the FreeLLMAPI account, add your provider keys, and copy the `freellmapi-...` key. From another device, you also need the setup code: `journalctl --user -u freellmapi | grep -i setup`.
2. In D-Agent, go to **Settings > Connectors > Always-on computer > A PC or laptop**. Enter `http://<laptop-ip>:8787` and the pairing code the script printed.
3. In D-Agent, go to **Settings > AI providers > FreeLLMAPI**. Base URL `http://<laptop-ip>:3001/v1`, model `auto`, and the `freellmapi-...` key.

`bash laptop-setup.sh --status` shows what's running and the values to enter. Re-running the script updates everything.

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
