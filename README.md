# SEO Task AI

Offline-first Android assistant for understanding SEO and microtask instructions, preparing compliant work, analyzing webpages, and organizing evidence.

> **Project status:** foundation scaffold. This repository is being rebuilt as a clean Android project. It is not yet a production-ready automation agent.

## Product principles

- Android phone first; local-first storage and analysis.
- Local AI inference is planned through llama.cpp and a separately downloaded GGUF model.
- Browser features use Android WebView; CloakBrowser is not a native Android embeddable engine.
- The user remains in control of account actions and final submission.
- No fake traffic, fake engagement, fabricated reviews, CAPTCHA bypass, or anti-bot evasion.

## Planned architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md).

## Build

Requirements: JDK 17, Android SDK 35, Gradle 8.9.

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

The GitHub Actions workflow runs unit tests and builds a debug APK on pushes and pull requests.

## Repository layout

- `app/` — Android application and Compose UI
- `docs/` — architecture, development and roadmap
- `.github/workflows/` — CI and APK artifact workflow

Feature modules will be extracted when their boundaries and dependencies are stable. This avoids premature modularization in the first buildable milestone.

## License

Apache-2.0. See [LICENSE](LICENSE).
