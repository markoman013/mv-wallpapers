"""Download public-domain paintings as wallpaper candidates (run by .github/workflows/art.yml).

Sources:
  met - The Met Open Access API (searched by the words in MET_QUERIES)
  nga - National Gallery of Art open data (github.com/NationalGalleryOfArt/opendata): every
        open-access painting, filtered by the image size listed in the data, no searching needed
Keeps paintings that are wide enough (MIN_RATIO) and 1080p+ after a 16:9 crop, scaled to at
most 3840 px on the long edge. Files land in candidates/<source>/ for review; nothing is added
to the collection until they're sorted like any new photo.
"""
from __future__ import annotations

import csv
import io
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from threading import Lock
from pathlib import Path

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
OUT = Path("candidates")
MIN_HEIGHT = 1080
MIN_RATIO = float(os.environ.get("MIN_RATIO", "1.4"))  # width / height; 16:9 is 1.78
LONG_EDGE = 3840
# Stop collecting after this long so the results still get pushed before the job times out.
DEADLINE = time.time() + int(os.environ.get("FETCH_SECONDS") or 5 * 3600)
SOURCES = os.environ.get("SOURCES", "met,nga").split(",")
MET_QUERIES = [q.strip() for q in (os.environ.get("MET_QUERIES") or "painting,oil,canvas,wood,watercolor,ink,silk,paper,tempera,gouache,landscape,people,portrait,river,sea,city,flowers").split(",") if q.strip()]
LOCK = Lock()
MET_HIGHLIGHTS = (os.environ.get("MET_HIGHLIGHTS") or "true") == "true"
MET_MAX = int(os.environ.get("MET_MAX") or 0)  # 0 = no limit
MET_API = "https://collectionapi.metmuseum.org/public/collection/v1"
MET_SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1.1/search"
NGA_DATA = Path(os.environ.get("NGA_DATA", "nga/data"))
csv.field_size_limit(10**9)


def get(url: str, tries: int = 3) -> bytes:
    for n in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "mv-wallpapers/1.0"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception:
            if n == tries - 1:
                raise
            time.sleep(2 * (n + 1))
    return b""


def slug(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode()
    s = re.sub(r"\(.*?\)", "", s.lower())
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:60].strip("-")


def wide_enough(w: int, h: int) -> bool:
    return h > 0 and w / h >= MIN_RATIO and min(h, w * 9 // 16) >= MIN_HEIGHT


def save(img: Image.Image, folder: Path, title: str, artist: str, oid: str) -> str | None:
    w, h = img.size
    if not wide_enough(w, h):
        return None
    if max(w, h) > LONG_EDGE:
        s = LONG_EDGE / max(w, h)
        img = img.resize((round(w * s), round(h * s)), Image.LANCZOS)
    name = slug(title) or "painting"
    a = slug(artist)
    fname = f"{name}--{a}.jpg" if a else f"{name}.jpg"
    if (folder / fname).exists():
        fname = fname[:-4] + f"-{oid}.jpg"
    img.convert("RGB").save(folder / fname, quality=88, optimize=True)
    return fname


def met_ids() -> list[int]:
    """Every public-domain painting with an image the search can reach. The v1.1 search is paged
    (500 a page, 10,000 per query), so several broad queries are combined and de-duplicated."""
    ids: dict[int, None] = {}
    for query in MET_QUERIES:
        got = 0
        while got < 10_000:
            params = {"hasImages": "true", "isPublicDomain": "true", "medium": "Paintings",
                      "q": query, "offset": got, "limit": 500}
            if MET_HIGHLIGHTS:
                params["isHighlight"] = "true"  # the Met's own picks of its most important works
            q = urllib.parse.urlencode(params)
            try:
                page = json.loads(get(f"{MET_SEARCH}?{q}")).get("objectIDs") or []
            except Exception as e:
                print(f"met: search '{query}' stopped at {got}: {e}")
                break
            ids.update(dict.fromkeys(page))
            got += len(page)
            if len(page) < 500:
                break
        print(f"met: '{query}' gave {got}, {len(ids)} unique so far")
    return list(ids)


def met_one(oid: int, folder: Path) -> str | None:
    time.sleep(float(os.environ.get("MET_PAUSE") or 0.5))
    try:
        o = json.loads(get(f"{MET_API}/objects/{oid}"))
        url, small = o.get("primaryImage"), o.get("primaryImageSmall")
        if not (o.get("isPublicDomain") and url):
            return None
        if small:  # check the shape on the small image first (portraits are skipped cheaply)
            s = Image.open(io.BytesIO(get(small)))
            if s.width / s.height < MIN_RATIO:
                return None
        img = Image.open(io.BytesIO(get(url)))
        img.load()
    except Exception as e:
        print(f"met: skip {oid}: {e}")
        return None
    with LOCK:
        return save(img, folder, o.get("title"), o.get("artistDisplayName"), str(oid))


def met() -> None:
    folder = OUT / "met"
    folder.mkdir(parents=True, exist_ok=True)
    ids = met_ids()
    kept = 0
    # 6 at a time stays well under the Met's limit of 80 requests a second.
    # One request at a time with a pause: the Met blocks (403) clients that go faster.
    with ThreadPoolExecutor(int(os.environ.get("MET_THREADS") or 1)) as pool:
        for f in pool.map(lambda i: None if time.time() > DEADLINE else met_one(i, folder), ids):
            if f:
                kept += 1
                if kept % 25 == 0:
                    print(f"met: {kept} kept")
                if MET_MAX and kept >= MET_MAX:
                    break
    print(f"met: kept {kept} of {len(ids)}")


def nga() -> None:
    folder = OUT / "nga"
    folder.mkdir(parents=True, exist_ok=True)
    objs = {r["objectid"]: r for r in csv.DictReader(open(NGA_DATA / "objects.csv", encoding="utf-8"))
            if r["classification"] == "Painting"}
    artists: dict[str, str] = {}
    for r in csv.DictReader(open(NGA_DATA / "objects_constituents.csv", encoding="utf-8")):
        if r.get("roletype") == "artist" and r["objectid"] in objs and r["objectid"] not in artists:
            artists[r["objectid"]] = r.get("constituentid", "")
    names = {r["constituentid"]: r.get("forwarddisplayname") or r.get("preferreddisplayname") or ""
             for r in csv.DictReader(open(NGA_DATA / "constituents.csv", encoding="utf-8"))}
    kept = 0
    for r in csv.DictReader(open(NGA_DATA / "published_images.csv", encoding="utf-8")):
        o = objs.get(r["depictstmsobjectid"])
        if not o or r["viewtype"] != "primary" or r["openaccess"] != "1":
            continue
        w, h = int(r["width"] or 0), int(r["height"] or 0)
        if not wide_enough(w, h):
            continue
        try:
            img = Image.open(io.BytesIO(get(f"{r['iiifurl']}/full/!{LONG_EDGE},{LONG_EDGE}/0/default.jpg")))
            img.load()
        except Exception as e:
            print(f"nga: skip {o['objectid']}: {e}")
            continue
        artist = names.get(artists.get(o["objectid"], ""), "") or o.get("attribution", "")
        f = save(img, folder, o["title"], artist, o["objectid"])
        if f:
            kept += 1
            print(f"nga {kept:4} {f}")


NASA_SEARCH = "https://images-api.nasa.gov/search"
NASA_ASSET = "https://images-api.nasa.gov/asset/"
NASA_QUERIES = [q.strip() for q in (os.environ.get("NASA_QUERIES") or
    "nebula,galaxy,milky way,star cluster,supernova,aurora from space,earth from space,earth at night,"
    "sunrise from space,earth limb,jupiter,saturn,mars surface,moon,solar eclipse,hubble,webb").split(",") if q.strip()]
NASA_PAGES = int(os.environ.get("NASA_PAGES") or 5)  # 100 results a page per search
NASA_MAX = int(os.environ.get("NASA_MAX") or 0)  # 0 = no limit


def nasa_one(item: dict, folder: Path) -> str | None:
    try:
        d = item["data"][0]
        manifest = json.loads(get(NASA_ASSET + urllib.parse.quote(d["nasa_id"])))
        hrefs = [i["href"] for i in manifest["collection"]["items"]]
        # The original JPEG (skip TIFFs and videos); fall back to ~large.
        url = next((h for h in hrefs if "~orig." in h and h.lower().endswith((".jpg", ".jpeg", ".png"))), None) \
            or next((h for h in hrefs if "~large." in h), None)
        if not url:
            return None
        img = Image.open(io.BytesIO(get(url.replace("http://", "https://"))))
        img.load()
    except Exception as e:
        print(f"nasa: skip: {e}")
        return None
    credit = d.get("secondary_creator") or d.get("photographer") or ""
    with LOCK:
        return save(img, folder, d.get("title") or d["nasa_id"], credit, d["nasa_id"])


def nasa() -> None:
    """NASA Image and Video Library (images-api.nasa.gov): no key needed. Mostly public domain;
    credits from the data go in the file name (check third-party credits when sorting)."""
    folder = OUT / "nasa"
    folder.mkdir(parents=True, exist_ok=True)
    items: dict[str, dict] = {}
    for query in NASA_QUERIES:
        for page in range(1, NASA_PAGES + 1):
            q = urllib.parse.urlencode({"q": query, "media_type": "image", "page": page, "page_size": 100})
            try:
                found = json.loads(get(f"{NASA_SEARCH}?{q}"))["collection"]["items"]
            except Exception as e:
                print(f"nasa: search '{query}' page {page}: {e}")
                break
            for it in found:
                items.setdefault(it["data"][0]["nasa_id"], it)
            if len(found) < 100:
                break
        print(f"nasa: '{query}' -> {len(items)} unique so far")
    kept = 0
    with ThreadPoolExecutor(6) as pool:
        for f in pool.map(lambda it: nasa_one(it, folder), list(items.values())):
            if f:
                kept += 1
                if kept % 25 == 0:
                    print(f"nasa: {kept} kept")
                if NASA_MAX and kept >= NASA_MAX:
                    break
    print(f"nasa: kept {kept} of {len(items)}")


# Wikimedia Commons: featured pictures only (the community's best), searched with the MV-SOURCES keywords.
WIKI_API = "https://commons.wikimedia.org/w/api.php"
WIKI_UA = "mv-wallpapers/1.0 (https://github.com/markoman013/mv-wallpapers)"
WIKI_PER_KEYWORD = int(os.environ.get("WIKI_PER_KEYWORD") or 50)
WIKI_ONLY = [c.strip() for c in (os.environ.get("WIKI_COLLECTIONS") or "").split(",") if c.strip()]
WIKI_KEYWORDS = {
    "architecture": "architecture, city, cityscape, skyline, city at night, skyscraper, landmark, bridge, old town, cathedral, castle, street, aerial city",
    "landscapes": "landscape, nature, scenery, valley, forest, field, meadow, countryside, lake, river, waterfall, desert, canyon, sky, sunset, sunrise, clouds, aurora, fog, aerial landscape",
    "sea": "sea, ocean, beach, coast, coastline, island, tropical beach, waves, cliffs, lighthouse, harbour, bay, lagoon, underwater",
    "mountains": "mountain, mountains, alps, peak, summit, mountain lake, mountain range, snowy mountains, dolomites, glacier, volcano",
    "space": "astrophotography, night sky, milky way, star trails, nebula, galaxy, moon, earth from space",
    "animals": "wildlife, birds, safari, big cats, horses, deer, foxes, whales, underwater life",
    "art": "painting, oil painting, landscape painting, seascape painting",
    "spring": "spring, blossom, cherry blossom, tulips, green meadow",
    "summer": "summer, golden field, sunflowers, lavender field",
    "fall": "autumn, fall foliage, autumn forest, golden leaves",
    "winter": "winter, snow, snowy forest, frost, winter village",
}
OPEN_LICENCES = ("cc0", "public domain", "pd", "cc by", "cc-by")


def wiki_get(params: dict) -> dict:
    q = urllib.parse.urlencode({"format": "json", "formatversion": 2, **params})
    req = urllib.request.Request(f"{WIKI_API}?{q}", headers={"User-Agent": WIKI_UA})
    for n in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read())
        except Exception:
            time.sleep(3 * (n + 1))
    return {}


def strip_html(s: str) -> str:
    return re.sub(r"<[^>]+>", "", s or "").strip()


def wikimedia() -> None:
    seen: set[str] = set()
    for coll, words in WIKI_KEYWORDS.items():
        if WIKI_ONLY and coll not in WIKI_ONLY:
            continue
        folder = OUT / "wikimedia" / coll
        folder.mkdir(parents=True, exist_ok=True)
        kept = 0
        for word in [w.strip() for w in words.split(",") if w.strip()]:
            data = wiki_get({
                "action": "query", "generator": "search", "gsrnamespace": 6, "gsrlimit": WIKI_PER_KEYWORD,
                "gsrsearch": f'incategory:"Featured_pictures_on_Wikimedia_Commons" {word}',
                "prop": "imageinfo", "iiprop": "url|size|mime|extmetadata", "iiurlwidth": LONG_EDGE,
            })
            for page in (data.get("query") or {}).get("pages", []):
                title = page.get("title", "")
                if title in seen or not page.get("imageinfo"):
                    continue
                seen.add(title)
                ii = page["imageinfo"][0]
                w, h = ii.get("width", 0), ii.get("height", 0)
                if ii.get("mime") not in ("image/jpeg", "image/png") or not wide_enough(w, h):
                    continue
                meta = ii.get("extmetadata") or {}
                lic = strip_html((meta.get("LicenseShortName") or {}).get("value", "")).lower()
                if not lic.startswith(OPEN_LICENCES):
                    continue
                url = ii.get("thumburl") or ii.get("url")
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": WIKI_UA})
                    with urllib.request.urlopen(req, timeout=120) as r:
                        img = Image.open(io.BytesIO(r.read()))
                        img.load()
                except Exception as e:
                    print(f"wikimedia: skip {title}: {e}")
                    continue
                name = re.sub(r"\.[a-z]+$", "", title.removeprefix("File:"), flags=re.I)
                author = strip_html((meta.get("Artist") or {}).get("value", ""))[:40]
                if save(img, folder, name, author, str(page.get("pageid", ""))):
                    kept += 1
                time.sleep(0.2)  # be gentle with Wikimedia
        print(f"wikimedia: {coll}: kept {kept}")


# ESA/Hubble and ESA/Webb: their "d2d" JSON feeds list every image with sizes. Real observations only
# (entries with ObservationData; illustrations and event photos have none). CC BY 4.0: credit in the name.
ESA_FEEDS = {"hubble": "https://esahubble.org/images/d2d/", "webb": "https://esawebb.org/images/d2d/"}


def esa() -> None:
    for name, feed in ESA_FEEDS.items():
        folder = OUT / "esa" / name
        folder.mkdir(parents=True, exist_ok=True)
        url, kept, seen = feed, 0, 0
        while url:
            try:
                page = json.loads(get(url))
            except Exception as e:
                print(f"esa {name}: stop at {url}: {e}")
                break
            url = page.get("Next")
            for c in page.get("Collections", []):
                seen += 1
                asset = (c.get("Assets") or [{}])[0]
                if not asset.get("ObservationData"):
                    continue
                large = next((r for r in asset.get("Resources", []) if r.get("ResourceType") == "Large"), None)
                if not large or not str(large.get("URL", "")).lower().endswith(".jpg"):
                    continue
                w, h = (int(float(x)) for x in large.get("Dimensions", [0, 0])[:2])
                if not wide_enough(w, h):
                    continue
                try:
                    img = Image.open(io.BytesIO(get(large["URL"])))
                    img.load()
                except Exception as e:
                    print(f"esa {name}: skip {c.get('ID')}: {e}")
                    continue
                if save(img, folder, c.get("Title") or c.get("ID", "image"), c.get("Creator") or f"ESA/{name}", c.get("ID", "")):
                    kept += 1
            print(f"esa {name}: {seen} looked at, {kept} kept")



# European paintings: Commons "featured pictures of paintings" for European countries (the
# community's best scans; walks subcategories), and the Danish national gallery (SMK) open API.
EURO_COUNTRIES = [c.strip() for c in (os.environ.get("EURO_COUNTRIES") or
    "the Netherlands,Belgium,France,Germany,Italy,the United Kingdom,Spain,Russia,Denmark,Norway,Sweden,"
    "Austria,Switzerland,Poland,Finland,Czech Republic,Hungary").split(",") if c.strip()]


def commons_files(category: str, depth: int, seen_cats: set[str]) -> list[str]:
    if category in seen_cats or depth < 0:
        return []
    seen_cats.add(category)
    files, cont = [], {}
    while True:
        data = wiki_get({"action": "query", "list": "categorymembers", "cmtitle": category,
                         "cmtype": "file|subcat", "cmlimit": 500, **cont})
        for m in (data.get("query") or {}).get("categorymembers", []):
            if m.get("ns") == 14:
                files += commons_files(m["title"], depth - 1, seen_cats)
            elif m.get("ns") == 6:
                files.append(m["title"])
        cont = data.get("continue") or {}
        if not cont:
            return files


def euro() -> None:
    folder = OUT / "euro"
    folder.mkdir(parents=True, exist_ok=True)
    seen_cats: set[str] = set()
    titles: list[str] = []
    for c in EURO_COUNTRIES:
        for cat in (f"Category:Featured pictures of paintings from {c}", f"Category:Featured pictures of paintings in {c}"):
            titles += commons_files(cat, 2, seen_cats)
    titles = list(dict.fromkeys(titles))
    print(f"euro: {len(titles)} featured paintings to check")
    kept = 0
    for i in range(0, len(titles), 40):
        if time.time() > DEADLINE:
            break
        data = wiki_get({"action": "query", "titles": "|".join(titles[i:i + 40]), "prop": "imageinfo",
                         "iiprop": "url|size|mime|extmetadata", "iiurlwidth": LONG_EDGE})
        for page in (data.get("query") or {}).get("pages", []):
            if not page.get("imageinfo"):
                continue
            ii = page["imageinfo"][0]
            w, h = ii.get("width", 0), ii.get("height", 0)
            if ii.get("mime") not in ("image/jpeg", "image/png", "image/tiff") or not wide_enough(w, h):
                continue
            meta = ii.get("extmetadata") or {}
            lic = strip_html((meta.get("LicenseShortName") or {}).get("value", "")).lower()
            if not lic.startswith(OPEN_LICENCES):
                continue
            try:
                req = urllib.request.Request(ii.get("thumburl") or ii["url"], headers={"User-Agent": WIKI_UA})
                with urllib.request.urlopen(req, timeout=120) as r:
                    img = Image.open(io.BytesIO(r.read()))
                    img.load()
            except Exception as e:
                print(f"euro: skip {page.get('title')}: {e}")
                continue
            name = re.sub(r"\.[a-z]+$", "", page["title"].removeprefix("File:"), flags=re.I)
            name = re.sub(r"\s*-\s*Google Art Project.*$", "", name)
            author = strip_html((meta.get("Artist") or {}).get("value", ""))[:40]
            if save(img, folder, name, author, str(page.get("pageid", ""))):
                kept += 1
            time.sleep(0.2)
    print(f"euro: kept {kept}")


SMK_API = "https://api.smk.dk/api/v1/art/search/"


def smk() -> None:
    folder = OUT / "smk"
    folder.mkdir(parents=True, exist_ok=True)
    kept, offset = 0, 0
    while time.time() < DEADLINE:
        q = urllib.parse.urlencode({"keys": "*", "offset": offset, "rows": 100,
                                    "filters": "[has_image:true],[object_names:maleri],[public_domain:true]"})
        try:
            data = json.loads(get(f"{SMK_API}?{q}"))
        except Exception as e:
            print(f"smk: stop at {offset}: {e}")
            break
        items = data.get("items") or []
        if not items:
            break
        for it in items:
            w, h = it.get("image_width") or 0, it.get("image_height") or 0
            if not wide_enough(w, h) or not it.get("image_native"):
                continue
            iiif = it.get("image_iiif_id")
            url = f"{iiif}/full/!{LONG_EDGE},{LONG_EDGE}/0/default.jpg" if iiif else it["image_native"]
            try:
                img = Image.open(io.BytesIO(get(url)))
                img.load()
            except Exception as e:
                print(f"smk: skip {it.get('object_number')}: {e}")
                continue
            title = ((it.get("titles") or [{}])[0]).get("title", "")
            artist = ((it.get("production") or [{}])[0]).get("creator", "")
            if save(img, folder, title, artist, str(it.get("object_number", ""))):
                kept += 1
        offset += 100
    print(f"smk: kept {kept}")

def main() -> int:
    if "met" in SOURCES:
        met()
    if "nga" in SOURCES:
        nga()
    if "nasa" in SOURCES:
        nasa()
    if "wikimedia" in SOURCES:
        wikimedia()
    if "esa" in SOURCES:
        esa()
    if "euro" in SOURCES:
        euro()
    if "smk" in SOURCES:
        smk()
    return 0


if __name__ == "__main__":
    sys.exit(main())
