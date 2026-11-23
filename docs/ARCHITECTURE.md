# Architecture

## Goals

SEO Task AI is an Android-first, local-first assistant. The initial milestone prioritizes a buildable foundation and clear boundaries over premature multi-module complexity.

## Layers

- **Presentation** — Jetpack Compose screens and ViewModels.
- **Domain** — task types, classification, policies and use cases; no Android framework dependencies.
- **Data** — Room-backed local persistence and repository implementations.
- **Engines** — adapters for local inference, browser inspection, OCR and task workflows.

## Planned engine boundaries

| Engine | Responsibility | Initial implementation |
|---|---|---|
| AI | On-device inference and prompt orchestration | Interface/design stage; no GGUF inference yet |
| Browser | User-visible webpage navigation and permitted page extraction | Android WebView planned |
| OCR | Extract text from user-provided screenshots | ML Kit planned |
| Task | Parse task instructions and produce a safe workflow plan | Basic rule-based classifier |

## Dependency direction

Presentation -> Domain <- Data/Engine implementations.

Domain must not depend on Android UI, WebView, ML Kit, Room, or a specific LLM runtime.

## Privacy and safety

- Keep task history and evidence on-device by default.
- Do not collect cookies, authentication tokens, or private browser storage.
- Require user review before account actions or external submission.
- Do not implement fake traffic or engagement, fabricated reviews, CAPTCHA bypass, or anti-bot evasion.
- Download model weights separately; never commit multi-gigabyte model files or secrets.

## Android target

- Kotlin, Jetpack Compose, AndroidX
- minSdk 26, compileSdk 35
- JDK 17, AGP 8.7.3, Gradle 8.9
