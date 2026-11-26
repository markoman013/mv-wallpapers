# Wallpaper sources for MV Wallpapers

## Search keywords by collection
Add "4K", "wallpaper" or "landscape orientation" to any search to get more TV-shaped results.
This list gets updated when collections are added.

- **Architecture:** architecture, city, cityscape, skyline, city at night, skyscraper, landmark, bridge, old town, cathedral, castle, street, aerial city
- **Landscapes:** landscape, nature, scenery, valley, forest, field, meadow, countryside, lake, river, waterfall, desert, canyon, sky, sunset, sunrise, clouds, aurora, fog, aerial landscape
- **Sea:** sea, ocean, beach, coast, coastline, island, tropical beach, waves, cliffs, lighthouse, harbour, bay, lagoon, underwater
- **Mountains:** mountain, mountains, alps, peak, summit, mountain lake, mountain range, snowy mountains, dolomites, glacier, volcano
- **Space:** space, astrophotography, astronomy, night sky, milky way, stars, nebula, galaxy, planet, moon, earth from space, star trails, deep space, Hubble, Webb
- **Animals:** animals, wildlife, birds, wildlife photography, safari, big cats, horses, deer, foxes, whales, underwater life, macro insects
- **Art:** art, painting, oil painting, landscape painting, impressionism, classical art, fine art, watercolor, Japanese woodblock print (ukiyo-e), Hokusai, Monet, Van Gogh, Turner, Hudson River School, Romanticism, landscape oil painting, seascape painting, still life, outdoor sculpture, statue, monument
- **Seasons** (tags, not folders):
  - **Spring:** spring, blossom, cherry blossom, flowers, tulips, green meadow
  - **Summer:** summer, sunny beach, golden field, sunflowers, lavender field
  - **Fall:** autumn, fall, fall foliage, autumn forest, golden leaves, misty autumn
  - **Winter:** winter, snow, snowy forest, frost, ice, winter village

## Sites
The repo is public, so stick to free-to-use or public-domain images.

### General: landscapes, sea, mountains, architecture
- **Unsplash**: https://unsplash.com. Free licence, lots of 4K.
- **Pexels**: https://www.pexels.com. Free licence.
- **Pixabay**: https://pixabay.com. Free licence.
- **Wikimedia Commons "Featured pictures"**: https://commons.wikimedia.org/wiki/Commons:Featured_pictures. Very high quality in every category, but check each photo's licence.
  - API: workflow source `wikimedia`. Featured pictures only, searched with the keywords above, open licences
    only (CC0 / public domain / CC BY / CC BY-SA); the author goes in the file name.

### Space
- **NASA**: https://images.nasa.gov. Public domain (check third-party credits). Bulk: the **Fetch art candidates**
  workflow with sources `nasa` (NASA Image Library API, no key needed).
- **ESA/Hubble**: https://esahubble.org/images. Free with a credit, which goes in the file name as `--author`.
- **ESA/Webb**: https://esawebb.org/images. Same terms as ESA/Hubble.
  - API: workflow source `esa` (both "d2d" JSON feeds; real observations only, wide and 1080p+). Hand-picked by Claude
    and stored on GitHub like everything else.

### Art
- **The Met Open Access**: https://www.metmuseum.org/art/collection. CC0 / public domain.
  - API: workflow source `met` (paged v1.1 search; v1 search was retired in Oct 2026). Public-domain paintings only.
  - Fetch in bulk with the **Fetch art candidates** workflow (Actions tab → Run workflow).
    It pulls public-domain paintings from the Met API and the NGA open data, keeps those wide enough and
    1080p+ after a 16:9 crop, and puts them on a temporary "candidates" branch, which Claude copies into
    NEW on the Mac for normal sorting. Run it again with "delete" to remove the branch.
- **National Gallery of Art (Washington) Open Access**: https://www.nga.gov/open-access-images.html. Data: https://github.com/NationalGalleryOfArt/opendata (used by the workflow).
  Public domain, full-size downloads.
  - API: workflow source `nga` (open-data CSVs + IIIF image server; no key, filtered by size before downloading).
- **European paintings (Wikimedia Commons featured pictures)**: the "Featured pictures of paintings from <country>"
  categories for European countries — the community's best scans (Rijksmuseum, Louvre, Google Art Project…).
  - API: workflow source `euro` (walks the categories, keeps wide, open-licence, 1080p+ files).
- **SMK – Statens Museum for Kunst (Danish national gallery)**: https://open.smk.dk. Public domain, no key.
  - API: workflow source `smk` (api.smk.dk search: paintings, public domain, with image; IIIF downloads).
    Strong on Danish Golden Age landscapes and seascapes (Købke, Eckersberg, Skovgaard, Dahl, Kyhn).
- Art mix: keep the collection European-leaning. NGA/Met wide paintings skew American (Hudson River School,
  folk art, still lifes); keep only the strong ones. Titles go in English.

### Animals and nature
- **US Fish & Wildlife Service**: https://www.fws.gov/library. Public domain.
- **US National Park Service**: https://www.nps.gov/media. Public domain.

### Bulk downloading: what works
- Works (free, full size): The Met API, NGA open data, NASA Image Library, Wikimedia Commons API (featured
  pictures, with sizes and licences), ESA/Hubble and ESA/Webb JSON feeds, Commons category listings, SMK open API.
- Not for this: Unsplash (API terms forbid downloading/re-hosting and wallpaper apps), Pixabay (API gives
  1280 px unless approved for full access), Pexels (possible, 200 requests/hour, but needs a key and
  attribution), NPS (API images are small; NPGallery has no public bulk API).

### Use with care
- **Wallhaven**: https://wallhaven.cc. The best resolution and 16:9 filters for bulk grabbing, but many images aren't free to share. Only use photos you've checked.

## Tools for downloading
Chrome extensions for grabbing a whole page of images at once:
- **ExtractPics**: https://chromewebstore.google.com/detail/bcbjifkmiciffmjcebcacchjcgboojkj. Note: it has no .zip download.
- **Imageye**: https://chromewebstore.google.com/detail/agionbommeaifngbhincahgmoflcikhm
- **Bulk Image Downloader** (green icon): https://chromewebstore.google.com/detail/lamfengpphafgjdgacmmnpakdphmjlji

## Workflow
This file is also pinned in the Claude project as `MV-SOURCES.md`. Edits made there get synced to GitHub.
Drop downloads into `MV Wallpapers/NEW` on the Mac and ask Claude to **sort and upload**.
- Every photo is cropped to 16:9, keeping the subject in frame. Up/down, only a quarter of the cut comes off
  the top (the rest off the bottom), so mountain tops, heads and rooftops are never cut.
- Signatures, watermarks, links, text or added lines are cropped out. If that's not possible, the photo is removed.
- Real photos only: cartoony, stylised or AI-looking images are removed (only Art can be other styles).
- Space: astronauts in space are a nice detail and fine; people on the ground (events, crowds, briefings) are not.
- People are avoided on landscapes, architecture and landmarks where possible.
- Art is mainly paintings. Sculptures and monuments only with a wallpaper-worthy (usually outdoor) setting, never museum shots.
- Art is its own thing: paintings get no other tags or seasons (no painted landscape mixed into photo collections).
- Paintings: painters' signatures can stay. Crop for the best look on the TV: ideally the painting only, no
  frame; if the frame shows, show all of it at a good angle. If neither works but it's a good picture, keep it;
  if not, delete it or put it in CHECK SORTING.
- CHECK SORTING is never deleted as a folder; only the photos inside, once they've been checked.
- Anything under 1080p is deleted, so grab generously.
- 1080p and up all look the same on the TV, so nothing is upscaled.
