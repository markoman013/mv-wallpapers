#!/usr/bin/env python3
"""Optimize wallpapers and rebuild manifest.json.

For every image in wallpapers/<collection>/:
  * converts PNG/WebP/HEIC-free formats to JPEG,
  * scales down anything wider than MAX_WIDTH (keeps aspect ratio),
  * re-compresses files larger than MAX_BYTES,
  * strips metadata and gives the file a clean, URL-safe name.
Files that already meet all of that are left untouched, so running the
script again never re-compresses an image twice.

File name convention (author and tags are optional):
    <title>--<author>~<tag>~<tag>.jpg
    e.g. snowy-alps--jane-doe~winter.jpg
A photo lives in one folder (its main collection); each ~tag adds it to
another collection too (e.g. ~winter, ~spring, ~summer, ~fall), without
storing the file twice.

Then it writes manifest.json, which the MV Wallpapers Plugin reads.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import time
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = None  # big panoramas are fine

ROOT = Path(__file__).resolve().parent.parent
WALLS = ROOT / "wallpapers"
MANIFEST = ROOT / "manifest.json"
THUMBS = ROOT / "thumbs"
THUMB_WIDTH = 480
REPO = os.environ.get("GITHUB_REPOSITORY", "markoman013/mv-wallpapers")
BRANCH = os.environ.get("WALLPAPER_BRANCH", "main")

MAX_WIDTH = 3840
MAX_BYTES = 3_500_000
JPEG_QUALITY = 90
LOW_RES_WIDTH = 1920
# Hard floor: photos whose visible part on a 16:9 TV (after centre crop) is
# less than this many pixels tall are deleted automatically.
MIN_VISIBLE_HEIGHT = 1080
# No upscaling: on a TV, any clean photo 1080p or larger looks the same (Google TV draws its
# home screen at 1080p), so photos keep their real size and stay small.
EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff", ".gif"}

# Display names for the collection folders. New folders work automatically
# (their name is title-cased); add them here only to customise the label.
COLLECTION_NAMES = {
    "architecture": "Architecture",
    "landscapes": "Landscapes",
    "sea": "Sea",
    "mountains": "Mountains",
    "space": "Space",
    "animals": "Animals",
    "art": "Art",
    "spring": "Spring",
    "summer": "Summer",
    "fall": "Fall",
    "winter": "Winter",
}


# Seasonal collections: a photo tagged with a season only shows while at
# least one of its seasons is switched on in the plugin.
SEASONS = {"spring", "summer", "fall", "winter"}


def slugify(text: str) -> str:
    base, *tags = text.split("~")
    tags = [slugify_part(t) for t in tags]
    tags = sorted({t for t in tags if t and "--" not in t})
    return "~".join([slugify_part(base) or "wallpaper", *tags])


def slugify_part(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = text.lower().replace("&", " and ")
    # keep the "--" author separator, collapse everything else to single dashes
    parts = [re.sub(r"[^a-z0-9]+", "-", p).strip("-") for p in text.split("--")]
    parts = [p for p in parts if p]
    return "--".join(parts[:2])


def unique_path(folder: Path, stem: str, current: Path | None) -> Path:
    candidate = folder / f"{stem}.jpg"
    n = 2
    while candidate.exists() and candidate != current:
        candidate = folder / f"{stem}-{n}.jpg"
        n += 1
    return candidate


def needs_work(path: Path, img: Image.Image) -> bool:
    if path.suffix != ".jpg":
        return True
    if path.stem != slugify(path.stem):
        return True
    if img.width > MAX_WIDTH or path.stat().st_size > MAX_BYTES:
        return True
    if img.info.get("exif") or img.mode != "RGB":
        return True
    if not is_16_9(img.width, img.height):
        return True
    return False


def is_16_9(w: int, h: int) -> bool:
    """16:9 give or take 2 px of rounding."""
    return abs(w - h * 16 / 9) <= 2


def optimize(path: Path) -> tuple[Path, int, int, bool]:
    with Image.open(path) as src:
        src.load()
        if not needs_work(path, src):
            return path, src.width, src.height, False
        img = ImageOps.exif_transpose(src)
        if img.mode not in ("RGB",):
            if img.mode in ("RGBA", "LA", "P"):
                img = img.convert("RGBA")
                bg = Image.new("RGB", img.size, (0, 0, 0))
                bg.paste(img, mask=img.split()[-1])
                img = bg
            else:
                img = img.convert("RGB")
        # Every photo is cropped to 16:9: exactly what the TV shows, so the size on GitHub is
        # the size on screen. Sideways it's centred; up and down only a quarter of the cut comes
        # off the top, so mountain tops, heads and rooftops stay (more of the lake/street goes).
        if not is_16_9(img.width, img.height):
            ch = min(img.height, img.width * 9 // 16)
            cw = ch * 16 // 9
            left, top = (img.width - cw) // 2, (img.height - ch) // 4
            img = img.crop((left, top, left + cw, top + ch))
        if img.width > MAX_WIDTH:
            h = round(img.height * MAX_WIDTH / img.width)
            img = img.resize((MAX_WIDTH, h), Image.LANCZOS)

    target = unique_path(path.parent, slugify(path.stem), path)
    tmp = target.with_suffix(".tmp.jpg")
    quality = JPEG_QUALITY
    while True:
        img.save(tmp, "JPEG", quality=quality, optimize=True, progressive=True)
        if tmp.stat().st_size <= MAX_BYTES or quality <= 75:
            break
        quality -= 5
    if path != target and path.exists():
        path.unlink()
    tmp.replace(target)
    return target, img.width, img.height, True


def split_stem(stem: str) -> tuple[str, list[str]]:
    base, *tags = stem.split("~")
    return base, tags


def added_times() -> dict[str, int]:
    """When each wallpaper first entered the repo (renames/moves keep their date)."""
    try:
        log = subprocess.run(
            ["git", "log", "--reverse", "-M", "--name-status", "--format=@%at", "--", "wallpapers"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout
    except Exception:
        return {}
    added: dict[str, int] = {}
    when = 0
    for line in log.splitlines():
        if line.startswith("@"):
            when = int(line[1:])
        elif line.startswith("A\t"):
            added.setdefault(line.split("\t")[1], when)
        elif line.startswith("R"):
            _, old, new = line.split("\t")
            added[new] = added.get(old, when)
    return added


def base_name(rel: str) -> str:
    """'wallpapers/sea/foo~summer.jpg' -> 'foo' (same photo after a move or tag edit)."""
    return rel.rsplit("/", 1)[-1].rsplit(".", 1)[0].split("~")[0]


def previous_dates() -> tuple[dict[str, int], dict[str, int]]:
    """Dates from the last manifest, so they survive a history clean-up (by path, then base name)."""
    try:
        old = json.loads((ROOT / "manifest.json").read_text())
    except Exception:
        return {}, {}
    by_path: dict[str, int] = {}
    by_base: dict[str, int] = {}
    for it in old.get("items", []):
        if "f" in it and "d" in it:
            by_path[it["f"]] = it["d"]
            by_base.setdefault(base_name(it["f"]), it["d"])
    return by_path, by_base


def make_thumb(path: Path) -> None:
    """Small preview for the plugin's photo grid: thumbs/<collection>/<file>."""
    thumb = THUMBS / path.relative_to(WALLS)
    if thumb.exists() and thumb.stat().st_mtime >= path.stat().st_mtime:
        return
    thumb.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(path) as im:
        im.draft("RGB", (THUMB_WIDTH * 2, THUMB_WIDTH * 2))
        im = im.convert("RGB")
        im.thumbnail((THUMB_WIDTH, THUMB_WIDTH))
        im.save(thumb, "JPEG", quality=72, optimize=True, progressive=True)


def title_and_author(stem: str) -> tuple[str, str | None]:
    title, _, author = split_stem(stem)[0].partition("--")
    title = re.sub(r"-\d+$", "", title) if title else title
    pretty = lambda s: " ".join(w.capitalize() for w in s.split("-") if w)
    return pretty(title) or "Wallpaper", (pretty(author) or None)


def main() -> int:
    items, collections, changed, low_res = [], [], 0, []
    added = added_times()
    old_path, old_base = previous_dates()
    now = int(time.time())
    tag_counts: dict[str, int] = {}
    removed: list[str] = []
    for folder in sorted(p for p in WALLS.iterdir() if p.is_dir()):
        cid = folder.name
        count = 0
        for path in sorted(folder.iterdir()):
            if path.suffix.lower() not in EXTS or ".tmp." in path.name:
                continue
            if path.suffix != path.suffix.lower() or path.suffix == ".jpeg":
                # normalise extension first so needs_work() sees the real state
                fixed = path.with_suffix(".jpg" if path.suffix.lower() in (".jpg", ".jpeg") else path.suffix.lower())
                if fixed != path and not fixed.exists():
                    path = path.rename(fixed)
            try:
                path, w, h, did = optimize(path)
            except Exception as exc:  # broken / unsupported file
                print(f"::warning file={path.relative_to(ROOT)}::could not read image ({exc})")
                continue
            visible_h = min(h, w * 9 // 16)
            if visible_h < MIN_VISIBLE_HEIGHT:
                print(f"::warning file={path.relative_to(ROOT)}::deleted, only {visible_h}px tall on a 16:9 TV (minimum {MIN_VISIBLE_HEIGHT})")
                removed.append(f"{path.relative_to(ROOT)} ({visible_h}px tall)")
                path.unlink()
                continue
            changed += did
            make_thumb(path)
            title, author = title_and_author(path.stem)
            tags = [t for t in split_stem(path.stem)[1] if t != cid]
            rel = path.relative_to(ROOT).as_posix()
            entry = {"c": cid, "f": rel, "t": title, "w": w, "h": h, "s": path.stat().st_size,
                     "d": old_path.get(rel) or old_base.get(base_name(rel)) or added.get(rel, now)}
            if author:
                entry["a"] = author
            if tags:
                entry["tags"] = tags
                for tag in tags:
                    tag_counts[tag] = tag_counts.get(tag, 0) + 1
            items.append(entry)
            if w < LOW_RES_WIDTH:
                low_res.append(f"{rel} ({w}x{h})")
            count += 1
        collections.append({"id": cid, "count": count})

    # A collection = a folder and/or a ~tag. Counts include tagged photos.
    by_id = {c["id"]: c for c in collections}
    for tag, n in tag_counts.items():
        by_id.setdefault(tag, {"id": tag, "count": 0})["count"] += n
    order = list(COLLECTION_NAMES)
    collections = sorted(by_id.values(), key=lambda c: (order.index(c["id"]) if c["id"] in order else len(order), c["id"]))
    for c in collections:
        c["name"] = COLLECTION_NAMES.get(c["id"], c["id"].replace("-", " ").title())
        if c["id"] in SEASONS:
            c["season"] = True

    # Remove thumbnails whose wallpaper was deleted or renamed.
    live = {(THUMBS / Path(i["f"]).relative_to("wallpapers")).as_posix() for i in items}
    if THUMBS.exists():
        for th in THUMBS.rglob("*.jpg"):
            if th.as_posix() not in live:
                th.unlink()

    manifest = {
        "version": 1,
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "base": f"https://raw.githubusercontent.com/{REPO}/{BRANCH}/",
        "page": f"https://github.com/{REPO}/blob/{BRANCH}/",
        "collections": collections,
        "items": items,
    }
    old = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    if {k: v for k, v in old.items() if k != "updated"} != {k: v for k, v in manifest.items() if k != "updated"}:
        MANIFEST.write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n")
        print("manifest.json updated")
    else:
        print("manifest.json unchanged")

    summary = [f"**{len(items)} wallpapers** in {len(collections)} collections; {changed} optimized this run.", ""]
    summary += [f"- {c['name']}: {c['count']}" for c in collections]
    if removed:
        summary += ["", f"Deleted, below {MIN_VISIBLE_HEIGHT}px tall:"] + [f"- {x}" for x in removed]
    if low_res:
        summary += ["", f"Below {LOW_RES_WIDTH}px wide (may look soft on a 4K TV):"] + [f"- {x}" for x in low_res]
    text = "\n".join(summary)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
            fh.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
