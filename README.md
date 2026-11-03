# D-Agent

A personal AI agent for Android. It does the work instead of describing it: searches the live web, runs code on its own Linux computer, drives a real browser, makes images, and uses your phone (texts, calls, alarms, calendar, contacts, location, notifications). No D-Agent account needed; sign in to GitHub, Google or MCP servers only if you want those connectors.

## What it can do

- **Chat and Agent modes.** Chat answers fast with web search. Agent mode plans multi-step jobs, shows a live checklist, and keeps going until the job is done.
- **Native tool calling** with OpenAI-compatible APIs and Anthropic, streamed, with automatic retries, a loop guard, and a text fallback for small local models.
- **Linux computer.** Alpine Linux under PRoot, installed in one tap. Python, Node, git, curl and anything from `apk`. The agent's files live in `/workspace`, shared with the file tools. A `phone` command lets Linux scripts reach the phone.
- **Live browser** the agent can open, read, click and type in. You watch it in the chat and can take over for sign-ins, codes or payments.
- **Phone actions:** notifications, clipboard, open apps and links, alarms, timers, calendar, contacts, texts and calls placed by the agent (pick SIM 1 or SIM 2), email drafts, maps, location, battery, flashlight, volume, speech.
- **Images** from a prompt (Pollinations; works without a key).
- **Memory** of facts about you, **skills** the agent reads before tasks and writes after it learns something, and **scheduled tasks** that run later or daily and notify you.
- **Your agent:** name, personality (witty by default), accent color, and a face: your own picture or a **character** you describe. Pick one of four drawings in a style you like (3D toy, anime, comic, clay, pixel, neon, painted, sticker, realistic) and it gets a pose for each mood (resting, working, waiting on you, done, oops) that changes live in the chat. You can also ask for a new look in the chat.
- **Brains:** FreeLLMAPI in Termux (`http://localhost:3001/v1`, model `auto`), OpenRouter, Anthropic, OpenAI, Gemini, xAI, Groq, Mistral, Together, Ollama, any OpenAI-compatible server, or **offline on the phone** with GGUF models through a bundled llama.cpp server: one-tap Qwen3 (0.6B to 8B), Qwen2.5 3B, Llama 3.2, Gemma 3, Granite 3.3 and Phi-4 mini with a pick for your phone's RAM, any GGUF from a Hugging Face search, or your own .gguf file.

## Install

Grab the newest APK from [Releases](../../releases) on a 64-bit Android phone (Android 9+), open it, and allow installs from your browser or file manager.

## Build

GitHub Actions builds every push (`.github/workflows/android.yml`): it fetches PRoot from the Termux package repository, builds llama.cpp's server with the NDK, runs the core tests, and publishes a signed APK as a release.

Locally (JDK 17, Android SDK 35, NDK 27):

```sh
bash scripts/fetch-proot.sh app/src/main/jniLibs/arm64-v8a
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018 bash scripts/build-llama-server.sh
./gradlew :core:test :app:assembleRelease
```

Without an Android SDK, `./gradlew :core:test` builds and tests only the agent engine.

## Connectors and sign-in

Settings > Connectors lists every connector with its status (Connected, Needs sign-in, Error, Off). They all live in one `ConnectorRegistry` (`core/.../connectors/Connector.kt`, filled by `app/.../data/ConnectorHub.kt`); only connected ones give the agent tools.

- **GitHub:** "Sign in with GitHub" uses the OAuth device flow (you type a short code at github.com/login/device). A pasted personal access token still works as a fallback; the sign-in is used first when both exist.
- **Google (Gmail, Calendar, Drive):** browser sign-in with PKCE. By default it asks only for read scopes (`gmail.readonly`, `calendar.readonly`, `drive.readonly`, `openid`, `email`). "Allow drafts and calendar events" adds `gmail.compose` and `calendar.events` and needs a new sign-in; each draft or event still asks you first.
- **MCP servers:** "Add MCP server" takes a name, a Streamable HTTP URL (`https://`, or `http://` to localhost only), and no auth, a bearer token, or OAuth (authorize URL, token URL, client ID, scopes; redirect `dagent://oauth`). The app lists the server's tools as `mcp_<server>_<tool>`; you can switch off a server or single tools. Tools the server marks read-only (or named like `get_`/`list_`/`search_`) run freely; anything else asks you first, goes through the Guard reviewer, and is held in background runs. Tool output is treated as untrusted. MCP OAuth discovery and dynamic client registration are not supported; enter the details by hand.

Storage: OAuth tokens, MCP bearer tokens and the in-progress sign-in (PKCE verifier and state) are kept only in `SecretStore`. MCP server definitions (name, URL, switched-off tools) are plain app preferences. Access tokens are refreshed automatically two minutes before they expire; a refused refresh (`invalid_grant`) signs that connector out.

### Setting up the OAuth clients (owner steps)

No client IDs are built in. Without them the GitHub and Google sign-in buttons say "not set up" and everything else still works. Client IDs are not secrets, but keep them out of git: put them in `local.properties` (git-ignored) or CI secrets.

```properties
# local.properties
dagent.githubClientId=Iv1.xxxxxxxx
dagent.googleClientId=1234567890-xxxx.apps.googleusercontent.com
```

In CI set the repository secrets `DAGENT_GITHUB_CLIENT_ID` and `DAGENT_GOOGLE_CLIENT_ID` and pass them as environment variables to the Gradle step.

**GitHub OAuth app**
1. github.com > Settings > Developer settings > OAuth Apps > New OAuth App.
2. Any homepage URL; the authorization callback URL is required but not used by the device flow (for example `https://github.com/deezi74/D-Agent`).
3. After creating it, tick **Enable Device Flow** and save.
4. Copy the **Client ID** into `dagent.githubClientId`. No client secret is used, so don't make one.

**Google OAuth client (Android type)**
1. console.cloud.google.com: create or pick a project, enable the **Gmail API**, **Google Calendar API** and **Google Drive API**.
2. OAuth consent screen: External, add yourself as a test user, and add the scopes listed above (the write ones only if you want them).
3. Credentials > Create credentials > OAuth client ID > **Android**.
   - Package name: `dev.dagent` for release builds, `dev.dagent.debug` for debug builds (make one client per package if you use both).
   - SHA-1: from the key that signs that build, e.g. `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` for local debug builds, or the release keystore's SHA-1. CI debug builds are signed with a throwaway key, so Google sign-in only works on builds whose SHA-1 you registered.
4. Under the client's **Advanced settings**, turn on **Custom URI scheme**. The redirect is then `dev.dagent:/oauth2redirect` (or `dev.dagent.debug:/oauth2redirect`), which the app handles.
5. Copy the client ID into `dagent.googleClientId`. Android clients have no secret.

## Security

- **Secrets** (API keys, pairing code, email accounts, personal details) are stored by `SecretStore`: each value is encrypted with AES-256-GCM (fresh random IV per value) under a key generated in the Android Keystore. On first launch after updating, keys from the old encrypted file and the old plain-text fallback are moved in and the old files are deleted. If the Keystore doesn't work, nothing is saved in plain text: secrets read as empty and Settings shows a warning.
- **Plain HTTP** is only allowed to `localhost`, `127.0.0.1` and `10.0.2.2` (emulator host); see `app/src/main/res/xml/network_security_config.xml`. Android can't allow IP ranges, so a server on your home network (LM Studio, Ollama, D-Agent PC) needs HTTPS (reverse proxy or Tailscale HTTPS) or its exact address added to that file in your own build.
- **D-Agent PC** listens on `127.0.0.1` unless started with `--lan`/`--host`, uses a 12-character pairing code from Python's `secrets`, and locks an IP out for 5 minutes after 5 wrong codes in a minute. See `pc-worker/README.md`.

## Layout

```
core/   pure Kotlin agent engine: LLM clients, agent loop, tools, memory, skills (unit tested)
app/    Android app: Compose UI, Linux computer, browser, phone actions, on-phone models, scheduler
scripts/ PRoot and llama.cpp packaging for CI
```

## Third-party programs

PRoot (GPL-2.0) and llama.cpp's server (MIT) ship as separate executables; Alpine Linux is downloaded on first use. Libraries: AndroidX, Jetpack Compose, OkHttp, jsoup, Coil, kotlinx (Apache-2.0 / MIT).

Copyright deezi74. All rights reserved.
