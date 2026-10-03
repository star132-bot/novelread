# MKread Android App Icon Design

## Status

Approved by the user on 2026-08-07.

## Goal

Create an original, friendly Android launcher icon that communicates both novel reading and expressive audio narration. The icon must remain recognizable at launcher size and must not directly reproduce the supplied reference character.

## Visual Design

- Use a centered chibi half-body portrait of an original golden-haired fox-eared reader.
- Place an open burgundy novel across the lower third, with the character looking over it.
- Add three restrained sound-wave curves beside the book to represent narration.
- Use golden yellow, burgundy, warm ivory, and charcoal as the main palette.
- Use clean, moderately thick outlines and simple cel shading.
- Omit text, logos, flowers, detailed hand poses, scenery, and small decorative elements.
- Keep the face, ears, book, and sound waves inside the Android adaptive-icon safe zone.

The supplied image is a mood and color reference only. The generated character, pose, clothing details, facial features, and accessories must be original.

## Generation

- Use the `imagegen2` workflow with a square image of at least 1K suitable as the production master.
- Use the supplied image only as a style reference and explicitly request an original character and composition.
- Generate one recommended master first, inspect it, and only regenerate if it has illegible details, malformed anatomy, a watermark, unintended text, or unsafe cropping.
- Generate a uniform warm-ivory master and derive a transparent foreground from its connected background. Android supplies the sampled `#FDF9E5` background layer so masks never expose square image edges.

## Android Integration

- Add launcher and round-launcher resources for `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, and `xxxhdpi`.
- Add an adaptive icon for API 26 and later with a matching background color.
- Add a monochrome adaptive layer derived from the foreground alpha where supported so themed icons remain usable.
- Reference the launcher resources through `android:icon` and `android:roundIcon` in the application manifest.
- Preserve the 2K source artwork at `design-assets/app-icon/mkread-icon-master.png`; generated APK resources use optimized PNG derivatives.

## Verification

- Validate image signature, dimensions, transparency or edge treatment, and generated file sizes.
- Inspect the 2K master and downscaled 48 px and 96 px previews.
- Build and lint the Android application.
- Install the APK on the API 35 emulator and verify the icon on the launcher and recent-apps surfaces.
- Reject any result with clipped ears, unreadable book shape, accidental lettering, visible square seams, or excessive fine detail.
