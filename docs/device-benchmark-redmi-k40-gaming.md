# Redmi K40 Gaming local inference benchmark

This benchmark must be run on the physical Redmi K40 Gaming (Dimensity 1200, Mali-G77 MC9, 12 GB RAM, Android 13). CI only validates compilation; it does not measure device inference.

## Preparation

1. Install the debug APK from the successful GitHub Actions artifact.
2. Import the pinned Qwen3-1.7B Q4_K_M GGUF from the trusted upstream revision documented in [qwen3-1.7b-model.md](qwen3-1.7b-model.md).
3. Keep at least 3 GB of free internal storage before import. The GGUF itself is about 1.28 GB; runtime needs additional memory for context/KV cache and compute buffers.
4. Reboot the phone, close other apps, disable battery saver, keep the phone unplugged and start at a similar battery/thermal state for each run.

## Test matrix

Run each case three times and record the median:

| Case | Context | Prompt | Generation |
|---|---:|---|---:|
| A | 1024 | 150–250 tokens | 64 tokens |
| B | 2048 | 300–500 tokens | 128 tokens |
| C | 2048 | 800–1,000 tokens | 128 tokens |

The current app engine caps generation at 256 tokens and defaults to context 2048. Do not increase context until the baseline is stable.

## Capture Android metrics

Replace the package name only if it changes:

```bash
adb shell dumpsys meminfo com.kenznguyen.seotaskai
adb shell dumpsys thermalservice
adb shell dumpsys battery
```

Capture memory immediately before model load, after model load, during/after inference, and after closing the model. Record PSS/Private Dirty, elapsed generation time, device thermal status, battery temperature and any Android low-memory kill or app crash.

For CPU and scheduling detail during a run:

```bash
adb shell top -b -n 1 | head -40
```

## Required report

- Model revision, filename, SHA-256 and file size
- Context size, CPU thread count and generated output length
- Model load time
- Prompt prefill time and generation time
- Generated token count and measured tokens/second (must use actual tokenizer token count, not whitespace estimates)
- PSS before load, after load, and peak during inference
- Android thermal status and battery temperature before/after each run
- Median of three runs, plus crashes, throttling or fallback events

Do not publish estimated tokens/second as measured tokens/second. The current JNI API returns generated text only, so exact generation token count and split prefill/decode timing still require native metrics instrumentation before a reliable tokens/second result can be reported.
