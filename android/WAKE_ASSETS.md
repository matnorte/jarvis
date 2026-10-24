# Offline JARVIS wake assets

The app uses sherpa-onnx rather than Picovoice. Wake recognition runs locally with
one CPU inference thread and needs no account, credential or network connection.
The existing encrypted device token remains necessary for the Jarvis server.

Gradle's `prepareWakeAssets` downloads and verifies these pinned upstream artifacts
into ignored build folders; binaries/models are not checked into the repository.

| Artifact | Source | SHA-256 |
|---|---|---|
| sherpa-onnx 2.0.0, static ONNX Runtime Android AAR | https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.0/sherpa-onnx-static-link-onnxruntime-2.0.0.aar | 59366e37cf2760b419e895d2872aa1664027fd721f6e4c827cdb0e805088fcbe |
| KWS Zipformer Chinese/English 3M, 2025-12-20 | https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2 | 68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6 |

Only the chunk-8 int8 encoder/joiner, fp32 decoder, and tokens are packaged (~5.5 MB).
The JARVIS phonemes in `app/src/main/assets/wake/keywords.txt` match the upstream
`en.phone` lexicon: `JH AA1 R V AH0 S`. This is an English wake-word model; spoken
questions remain Portuguese/English through the existing server STT.

The native runtime is statically linked for the two supported ABIs, arm64-v8a
(the user's phone) and x86_64 (emulator), avoiding a shared-ORT version collision
with Silero. Legacy x86/ARMv7 native files are excluded from packaging.

The runtime is Apache-2.0: [upstream license](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.0/LICENSE).
Model provenance and usage: [official model guide](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html).
The small `androidTest/assets/jarvis.wav` test fixture was generated locally using
Windows System.Speech at 16 kHz mono PCM16, speaking only “Jarvis”; it contains no
recorded user audio or credentials.
