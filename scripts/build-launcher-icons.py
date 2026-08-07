from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw


BACKGROUND = (253, 249, 229, 255)
LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FOREGROUND_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def remove_uniform_background(image: Image.Image) -> Image.Image:
    rgb = image.convert("RGB")
    marker = (0, 255, 1)
    for seed in ((0, 0), (rgb.width - 1, 0), (0, rgb.height - 1), (rgb.width - 1, rgb.height - 1)):
        ImageDraw.floodfill(rgb, seed, marker, thresh=18)

    alpha = Image.new("L", rgb.size, 255)
    alpha.putdata([0 if pixel == marker else 255 for pixel in rgb.getdata()])
    foreground = image.convert("RGBA")
    foreground.putalpha(alpha)
    return foreground


def circular_icon(image: Image.Image) -> Image.Image:
    scale = 4
    mask = Image.new("L", (image.width * scale, image.height * scale), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, mask.width - 1, mask.height - 1), fill=255)
    mask = mask.resize(image.size, Image.Resampling.LANCZOS)
    result = image.copy()
    result.putalpha(mask)
    return result


def build_icons(master_path: Path, resource_root: Path) -> None:
    master = Image.open(master_path).convert("RGBA")
    if master.width != master.height or master.width < 1024:
        raise ValueError("launcher icon master must be square and at least 1024 px")

    foreground_master = remove_uniform_background(master)
    for density, legacy_size in LEGACY_SIZES.items():
        output_dir = resource_root / f"mipmap-{density}"
        output_dir.mkdir(parents=True, exist_ok=True)

        legacy = master.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        legacy.save(output_dir / "ic_launcher.png", optimize=True)
        circular_icon(legacy).save(output_dir / "ic_launcher_round.png", optimize=True)

        foreground_size = FOREGROUND_SIZES[density]
        foreground = foreground_master.resize(
            (foreground_size, foreground_size),
            Image.Resampling.LANCZOS,
        )
        foreground.save(output_dir / "ic_launcher_foreground.png", optimize=True)

        monochrome = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
        monochrome.putalpha(foreground.getchannel("A"))
        monochrome.save(output_dir / "ic_launcher_monochrome.png", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build MKread Android launcher icons.")
    parser.add_argument("--master", type=Path, required=True)
    parser.add_argument("--res-root", type=Path, required=True)
    args = parser.parse_args()
    build_icons(args.master, args.res_root)


if __name__ == "__main__":
    main()
