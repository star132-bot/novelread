# Development Speech Assets

Phase 1 uses upstream sherpa-onnx release artifacts only for local engineering validation:

- `sherpa-onnx-1.13.4.aar`
- `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2`
- `vocos_24khz.onnx`

The fixed upstream URLs, byte sizes, and SHA-256 values are recorded in `speech-assets.lock.json`. Downloaded binaries remain under ignored local directories or `app/libs/*.aar`; they are not committed to Git.

The `test_wavs/leijun-1.wav` sample extracted from the upstream model archive is internal engineering material. It is not the MKread production built-in voice and must not enter a release artifact without separate authorization, consent review, and license review. A separately authorized production voice package will replace it before release.
