# D-Agent

A personal AI agent for Android. It does the work instead of describing it: searches the live web, runs code on its own Linux computer, drives a real browser, makes images, and uses your phone (texts, calls, alarms, calendar, contacts, location, notifications). No account, no sign-in.

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

## Layout

```
core/   pure Kotlin agent engine: LLM clients, agent loop, tools, memory, skills (unit tested)
app/    Android app: Compose UI, Linux computer, browser, phone actions, on-phone models, scheduler
scripts/ PRoot and llama.cpp packaging for CI
```

## Third-party programs

PRoot (GPL-2.0) and llama.cpp's server (MIT) ship as separate executables; Alpine Linux is downloaded on first use. Libraries: AndroidX, Jetpack Compose, OkHttp, jsoup, Coil, kotlinx (Apache-2.0 / MIT).

Copyright deezi74. All rights reserved.
