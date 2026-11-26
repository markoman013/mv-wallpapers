# MV README (maintainer notes)

Everything about how this repo, the GitHub Actions and the **MV Wallpapers Plugin** work.
The public [README](README.md) is the short version for guests.

**Where to find new wallpapers (sites and search keywords): [MV-SOURCES.md](MV-SOURCES.md)**

## Collections

| Folder | Shown as |
|---|---|
| `wallpapers/architecture` | Architecture (cities, buildings, landmarks) |
| `wallpapers/landscapes` | Landscapes |
| `wallpapers/sea` | Sea (beaches, coasts, islands, ocean) |
| `wallpapers/mountains` | Mountains |
| `wallpapers/space` | Space |
| `wallpapers/animals` | Animals |
| `wallpapers/art` | Art |
| *(tag `~spring`)* | Spring (season) |
| *(tag `~summer`)* | Summer (season) |
| *(tag `~fall`)* | Fall (season) |
| *(tag `~winter`)* | Winter (season) |

Seasons are always listed spring → summer → fall → winter.

- A new folder under `wallpapers/` becomes a new collection automatically.
- Seasons are **tags only**. There are no season folders.
- Empty collections start deselected and select themselves when photos arrive.

### Sorting rules

- **Folder = main subject.** Add `~tags` for any other collection the photo clearly fits.
- Landmarks go in **architecture**. Islands, coasts and beaches go in **sea**.
- Sea photos are tagged `~summer` unless they obviously aren't summer.
- A photo can have several tags, e.g. `lauterbrunnen-valley~spring~summer.jpg`.
- **Real photos only.** Remove anything cartoony, stylised or AI-looking. Only **art** can be in other styles.
- **No people if possible** on landscapes, architecture and landmarks. People in city or street shots are fine
  when they're part of the scene, but clean shots are preferred.
- **No added text.** Crop out signatures, watermarks, links, text or lines added by software. If they can't be
  cropped out, remove the photo.
- **Art** is mainly paintings. Sculptures and monuments go in art *or* architecture, whichever fits best,
  tagged into the other when they fit both. A sculpture needs a wallpaper-worthy setting (usually outdoors),
  so no museum shots. Anything else is deleted.
- **Crop around the subject.** When cropping to 16:9, keep the focal point (e.g. an off-centre nebula) in frame.

## File names

`title--author~tag~tag.jpg`, e.g. `snowy-alps--jane-doe~winter.jpg`

- The part after `--` is the author (optional), shown as "by Author".
- Each `~tag` adds the photo to another collection without storing it twice.
- Crops to 16:9 take only a quarter of the cut from the top (more from the bottom), so mountain tops,
  heads and rooftops stay. Sideways crops are centred.
- Names start with real words: no leading numbers, no dates (or written like "26 Nov 2026"), no camera
  or file junk (`img`, `dsc`, `hdr`, `edit`, `crop`, long numbers), no "this photo was taken by" in the author.
  Photos never get "painting" or "untitled" as a title. Two photos that end up with the same name get `-2`, `-3`.
- "Spring" is the season: hot springs and water springs aren't tagged `~spring`.
- Season tags: Sea mostly `~summer`; green mountains `~summer` (fresh green or flowers also `~spring`);
  autumn colours `~fall`, without overdoing it. Art never gets tags or seasons.

## Adding wallpapers

### With Claude ("sort and upload")

1. Drop photos into `/Users/marko/Pictures/MV Wallpapers/NEW` on the Mac.
2. Ask Claude to **sort and upload**. It names and tags them, shrinks them to 4K, uploads them,
   and moves the unclear ones to `CHECK SORTING`.
3. Portrait or square photos are centre-cropped to 16:9 from the Mac originals first.

### By hand on github.com

Open a collection folder → **Add file → Upload files** → **Commit changes**.

### What the *Process wallpapers* action does (about a minute)

- Converts to JPEG and scales anything wider than 3840 px down to 4K (≤ 3.5 MB each).
- Makes 480 px thumbnails in `thumbs/` and strips metadata.
- **Deletes anything under 1080 px tall** after a 16:9 centre crop. The run summary lists them.
- **Crops every photo to 16:9** (centre), exactly what the TV shows. The size on GitHub is the size on screen.
  Mac originals keep the full picture.
- **No upscaling.** Google TV draws its home screen at 1080p, so any clean photo 1080p or larger looks the
  same on the TV. Photos keep their real size (smaller files).
  The 1080p floor gets a final review once the library has 1,000+ photos and the share of each
  resolution range is known.
- **Crops follow the subject.** Every photo is cropped to 16:9 around its real focal point, checked by
  eye, never a blind centre crop.
- Rebuilds `manifest.json`: per photo, its collection, path, title, author, tags, size, bytes
  and date added.

Unsorted images can sit in `inbox/`. They're ignored until moved into a collection.

### Standing rules

- **Minimum resolution:** never below 1080p. Anything under it is deleted from GitHub *and* the Mac.
- **Mac mirrors GitHub:** whatever is deleted on GitHub (from the TV too) is moved to
  `MV Wallpapers/_Deleted` on the Mac, unless it was a mistake.

## The plugin

### Screens

- **Top bar:** Collections and Settings tabs. A tab opens as soon as it's highlighted.
  The **Update** button appears when there's a new version.
- **Filter row:** Select, Show, Sort and **Slideshow**.
  - **Select:** all → none → custom. Your last mixed selection (**custom**) is always remembered,
    on home and per collection.
  - **Show:** all, selected, not selected, and on home also **hidden**.
  - **Sort:** collections A–Z ↔ new (empty ones always last); photo grids new ↔ A–Z.
  - **Slideshow:** selected photos (all, if none are selected) in random order, one a minute. On home it
    plays the whole library, inside a collection just that collection. Small bar with the title and chips;
    **OK** hides the bar, **◀ ▶** skip, **Hold OK** opens the menu and pauses while it's open (also Pause/Resume).
- **Home:** collections (empty ones last), a faint line, then a **Seasons** row and the four seasons
  (spring → summer → fall → winter, never A–Z).
  - The Seasons row is one wide button: **OK** switches **On** (your selection of seasons to show; turning
    it on selects all four, deselect any you don't want) and **Off** (no seasonal photos). The bottom bar shows
    "N of N seasonal photos selected · N in 2+ seasons".
- **Cards:** **OK** opens. **Hold OK** gives select/deselect, Edit multiple, Slideshow, hide/unhide, and an
  info block (photos, selected, yet to show, 4K count, tagged in, newest photo).
  Hiding also deselects that collection; its photos stay on through their other collections.
- **Inside a collection:** 5 per row.
  - **OK** shows a photo full screen. **Hold OK** opens the options menu.
  - Badges left of the checkbox (only while selected): folder (photo tagged in from another folder), 🏷 teal
    (also in another collection), ☀ gold (in a season; not shown inside a season).
  - Hidden by the seasons setting (its seasons are off): dimmed, with a crossed-out ☀ in the checkbox spot.
    Selecting it by hand shows it anyway.
- **Edit multiple** (Hold OK → Edit multiple, on a photo or a card): Select/Show/Sort/Slideshow turn into
  **Done · Mark all · Edit marked**. **OK** marks (white circle top left), **Hold OK** or **Edit marked** opens
  the menu (with **Quit editing multiple**). Photo marks stay while you go back and open other collections;
  **Mark all** at home marks the whole library.
  - photos: Select all, Deselect all, Set as wallpaper (up to 50), Move all, Edit tags of all (a tag only some
    have shows "(some)" and is left alone unless changed; main folders stay, use Move), Delete all.
    GitHub changes go in **one commit**.
  - collections: Select, Deselect, Hide, Unhide.
  - **Back** only leaves it for collection cards; photo marking ends with Done or Quit.
- **Set as wallpaper:** the chosen photos (up to 50) become the batch Projectivy gets next, for the usual
  hour, even if they aren't selected. They count as shown this round.
- **Bottom bar for a photo:** title, author, then chips: folder (theme colour) for the main folder,
  🏷 tag chip (teal) for other collections, ☀ season chip (gold). Several tags in one chip are separated by dots.
- **Full-screen preview:** **◀ ▶** browse, **OK** selects/deselects, **Hold OK** opens the options menu.
- **Options menu (Hold OK on a photo):** Select/Deselect, Edit multiple and Start slideshow (grid), or
  Hide/Show UI and Start / Pause / Resume slideshow (one button, full screen), Set as wallpaper, Move to collection, Edit tags (folders A–Z, a faint line, then seasons;
  **Back** saves), Delete from GitHub, and a **photo info** block under the buttons.
- **Bottom bar:** focused photo / collection / totals; blurred on Android 12+.
- **Settings:**
  - Library settings (first): Refresh library, Reshuffle, Check for updates, GitHub token; library info and
    repo size below the buttons
  - Wallpapers per hour
  - App theme (Amber, Blue, Green, Lavender, Rose, always A–Z; all accents follow it)
  - Blur on menus
  - Choices use white radio circles.
- **Back:** leaves Edit multiple first; inside a collection: first photo → home; Settings: top bar → Collections → exit.

### How wallpapers reach Projectivy

- **Shuffle bag:** every selected photo is shown once in random order. When all have shown, it reshuffles.
  New uploads join the current round.
  Changing the selection, collections or season rule starts a new round.
- Projectivy gets a batch (default 20) and keeps it for an hour.
  Set *Projectivy → Appearance → Wallpaper → change interval* to 3 minutes for 20 per hour.
- Projectivy's **About this wallpaper** title is `Title · Collection · tags`.
  The QR code opens the file on GitHub.

### GitHub token (move, tags, delete from the TV)

Fine-grained token named "MVWalls - Delete from TV". It covers only this repo, with
**Contents: Read and write**.

- Enter it under **Settings → GitHub token**.
- It's saved on the TV and included in Projectivy's backup, so keep that backup file private.
- It's never stored in the repo.

### Cache

Thumbnails take up to 15 MB and full-screen photos up to 15 MB, oldest first. Downloaded update APKs
are deleted as soon as the update finishes.

## Releases and versions

- Any change under `plugin/` runs **Build MV Wallpapers Plugin**, which builds, signs and publishes a release.
  Signing uses the secret `MV_KEYSTORE_BASE64`, with password and alias `mvwallpapers`.
- **Version names:** `1.0.<run number − 12>`, i.e. 1.0.50, 1.0.51, …
  The update check compares the **build number** (`Build: N` in the release notes), so renaming
  versions never breaks updates.
- **Workflow changes:** GitHub won't publish a release from a commit that changes a workflow file.
  Commit workflow edits on their own with `[skip ci]`, then push the app change separately.
- **Clean up old plugin releases** (run it by hand in Actions) deletes every release except the
  latest, plus leftover tags without a release.
- **Fetch art candidates** (run by hand) downloads public-domain Met + NGA paintings as review candidates
  (see MV-SOURCES.md). Nothing goes into the collection without review.
- **Repo size:** watch it as the library grows; at 3–4 GB, stop and slim it down (remove, or shrink without
  visible quality loss). Paintings are about 2.4 MB each.
- **History:** old versions of every photo stay in git history and count towards the repo size.
  Every so often the history is squashed to one commit (dates added are kept in the manifest),
  then **Clean up old plugin releases** is run so old tags stop holding the old files.

## Credits

Wallpapers belong to their original artists and are credited in file names where known.
The plugin is based on Spocky's
[wallpaper provider template](https://github.com/spocky/projectivy-plugin-wallpaper-provider) (Apache-2.0).
