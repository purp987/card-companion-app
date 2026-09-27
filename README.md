# Card Companion

Android app (Kotlin + Jetpack Compose) for Pokémon TCG collectors: work out what packs really cost per card, and track your collection set by set, down to every variant.

## Sections
The bottom bar switches between three areas:

- **Home**: greeting, quick stats (cards collected, sets started, average price per card), shortcuts into each section, the sets you're collecting now, and your recent purchases. ⚙ Settings lives here.
- **Pack Calculator**: pick a set (English, 日本語, 简体中文), choose a product and enter what you paid.
  - Shows price per card and per pack, with optional sales tax.
  - The result box turns green, amber or red depending on how the price compares with TCGplayer's market price.
  - Live market prices, plus price and units-sold history graphs (1M / 3M / 6M / 1Y).
  - Saved purchases, and your own custom sets.
- **My Collection**: browse every set by era, open one and tap the variants you own. Each tap adds a copy (Normal ×3). Press and hold a variant, then confirm, to remove all its copies.
  - Cards and master-set progress bars; filter by All / Missing / Owned, and switch Master set on to count every variant.
  - Tap a card for its full image, a copy counter per variant, and that variant's price (TCGplayer USD, or Cardmarket EUR when there's no TCGplayer price).
  - **Collection value** per set: copies owned × each variant's market price (TCGplayer, USD). Variants that only have Cardmarket prices, which is common for Japanese cards, are totalled separately in EUR rather than converted. Prices for owned cards are fetched when you open the set and cached for 24 hours. Each set's value shows in the set list and on Home, and Home adds them all up.
  - Special subsets have their own filter and progress bar, such as 30th Celebration's **30 Pikachu** (#023–052). The 30th Classic Collection is tracked as its own set.
  - Links to the set's PokeCottage card list and master-set guide.
- **Card gallery** (beta): collection sets open as a gallery of big card pictures, three across. Owned cards are in full colour with a copy count and a holo sheen on foils; missing ones are greyed out. The ▦/☰ button switches between gallery and list (remembered). Tap a card for a full-screen view: swipe between cards, and tilt the phone to turn the card in 3D with a moving glare and a rainbow holo shimmer. Version buttons under the card add or remove copies.
- **Inventory** (beta; its own tab and a Home shortcut): cards and sealed product held to sell or trade, kept apart from the collection.
  - *Add* → **Card** (search TCGplayer, price included), **Sealed** (pick a set, then one of its products) or **By hand**.
  - Each item has a quantity, cost each, condition (NM–DMG) or grade, version, asking price, where it's kept, notes, and a status: In stock, Listed or Sold. *Mark sold…* records the price; selling part of a stack keeps the rest in stock.
  - The summary shows units in stock, market value and profit on paper, plus units sold, revenue and realized profit. ↻ updates market prices from TCGplayer.
  - Search by name, set, number or location; filter by status and cards/sealed; switch to the gallery view.
  - Saved in `files/inventory/inventory.json` with hourly rolling backups (last 20); a damaged file falls back to the newest backup.
- **Card scanner** (Scan cards on Home, or the Scan button on the Collection tab): point the camera at a card, or tap *Photo* to scan a picture.
  - On-device text recognition (Google ML Kit, offline) reads the set code and number at the bottom of the card, e.g. "PBL EN 111/084" or Japanese "SV2a 025/165", and looks the card up. Older cards that only print "215/203" are matched by set size plus the card's name.
  - Photos get extra enlarged passes over the bottom strip and the bottom-left corner, where the small print is.
  - **"What are you scanning?"** comes first (last choices remembered; *Change* reopens it).
    - **Finish** (Normal / Reverse Holo / Holo / Mixed): with a finish picked, cards that come in several finishes are added as that finish without a prompt. Only the plain version counts, so Poke Ball and Master Ball reverses still ask.
    - **Language** (English / 日本語): limits matching to that language.
    - **Set** (any, or one you're collecting): with a set picked, only the card number has to be read.
  - **Auto-add** (on by default, switchable in the scanner): a sure match is added without tapping, and shows an "Added ✓" banner with **Undo** until the next card is read. "Sure" means all of these:
    - the number's "/total" matches the set;
    - the printed set code or the card name confirms the set;
    - no other match comes close;
    - from the camera, the same card was read twice in a row (a photo counts on one read).
  - **Confirmed splash:** a big green ✓ with the card and "Confirmed" flashes over the camera for about a second whenever a card is verified and added (also for +1), with a short vibration.
  - **Screen stays on** while the scanner is open; normal screen timeout resumes after leaving it.
  - **Scanning the same card again:** a card isn't added twice while it stays in view.
    - **Move it away:** when no card has been readable for about 1.5 seconds and the same card appears again, it counts as another copy. A brief wobble or glare doesn't.
    - **+1:** the "Added ✓" banner has a +1 button for extra copies without moving the card. Undo takes back everything added under that banner.
    - **Remembered version:** the version you pick for a card with several finishes is reused for later copies of that card during the session.
  - **Cards with more than one variant are only auto-added when the chosen finish matches one of them.** Otherwise the scanner asks which version you have. The same card isn't auto-added twice in a row either; tap + for extra copies.
  - Less-sure results show the card with its variant buttons (tap to add a copy, hold to remove) and up to three candidates. The rules are in `data/scan/AutoAddPolicy.kt`.
  - For a moment after a result appears, taps on it are ignored, so a tap meant for something else can't add a card.
  - Printed set codes come from `data/scan/ScanIndex.kt`, generated from TCGdex.
- **Binder pages** (scanner → *Binder page*): choose 9-pocket (3×3), 12-pocket (4×3) or 4-pocket (2×2), line the page up with the on-screen grid and tap *Capture page*, or tap *Photo* to use a picture of a page.
  - A full-resolution picture is split into pockets and each one is read like a single card, with the enlarged bottom-strip and corner passes.
  - The review grid pre-ticks sure matches whose version is known (from the chosen finish, or a version you picked before). Unsure pockets and pockets needing a version are left for you; tap a pocket to pick the card, the version, or to skip it. Empty pockets are skipped.
  - *Add N cards* adds the page, with a Confirmed splash; *Undo page* takes it all back.
- **Scanning improves with use** (`data/scan/ScanLearning.kt`):
  - Keeping or picking a result ties that exact reading (e.g. "PBL 111/084") to the card, so it's a sure match next time.
  - Undoing a single auto-add marks the card wrong for that reading.
  - Sets you scan often win close calls.
  - Versions you pick are remembered across sessions.
  - Undoing a binder page only withdraws its confirmations (it doesn't mark the cards wrong).
  - ⚙ Settings shows what it has learned and can reset it.
- **Scan history** (*History* in the scanner): every change the scanner makes, grouped by day with a daily total. That covers auto-added, added or removed by tapping, and undone. Tap an entry to open that card in its set. *Clear* only clears the list; cards stay in your collection.
- **Search**
  - *Home → Search all cards*: searches every English or Japanese card on TCGplayer, most popular (best-selling) first, or sorted by highest price. With no text it lists the most popular cards right now. Each result shows its market price and "You have N" if it's in your collection. Tapping a result opens it in its set; promos and one-off products open on TCGplayer instead. `data/search/TcgSetMap.kt` maps TCGplayer sets to the collection's sets.
  - *In a set*: "Find a card by number or name" filters instantly. "111", "#111", "111/084" and "TG05" all work, as does part of a name.
- **Cloud backup** (beta; ⚙ Settings → *Cloud backup…*): sign in to a [Card Companion server](https://github.com/purp987/card-companion-server), or create an account with an invite code. *Back up now* uploads the collection, inventory, scan learning and history, and settings such as purchases, favorites and custom sets. API tokens aren't included. Server backups are listed with their card counts and can be restored (the current collection is kept as a local backup first) or deleted. Server addresses must be HTTPS; beta builds may also use `http://localhost` through `adb reverse` for a test server.
- **Restore points**: ⚙ Settings → *Save a restore point…* keeps a named copy of your collection that automatic backups never replace, such as "Manual entries".
- **Backups**: before the collection is saved, the version being replaced is kept as a backup (at most once an hour, last 20 kept). If the collection file is ever missing or damaged, the app loads the newest backup and says so on Home. ⚙ Settings → *Restore a backup…* lists them. Restoring keeps your current collection as a backup too, so it can be undone.
- **Set pictures** are the same everywhere (both set lists, set headers, Home). English sets use the TCGdex logo, then TCGplayer pack art, then the set symbol. Japanese sets use pack art. Simplified Chinese sets use a code badge.

## Data sources
- **TCGdex** ([tcgdex.dev](https://tcgdex.dev), MIT-licensed): set lists, card lists, card images, detailed variants (Poké Ball/Master Ball reverses, cosmos, stamps, 1st Edition, shadowless) and per-variant prices.
  - English loads in bulk through GraphQL. Japanese and Chinese load card by card the first time a set is opened, then it's cached on the phone.
  - TCGdex has only card counts for mainland Simplified Chinese sets, so those appear as numbered checklists until TCGdex adds the cards.
- **PokeCottage**: its terms don't allow copying or storing its content, so the app only *links* to its card list and master-set guide pages (`data/collection/PokeCottage.kt`, generated from its sitemap).
- **TCGplayer**: sealed-product market prices and sales history. It no longer issues public API keys, so the app reads the same endpoints tcgplayer.com's own pages use. They're undocumented and could change; failures show an error with a retry button.
- **PriceCharting** (optional): paste your API token under ⚙ Settings.

### Calculator set lists
- **English**: 69 sets, XY through Mega Evolution.
- **Japanese**: 98 sets. Box contents come from TCGplayer product descriptions.
- **Simplified Chinese**: SV-era boosters with slim and jumbo boxes, plus Gem Packs. Calculator only; there's no market data for these.

## Branches
- **`release`**: the public version that's shared with others. Only tested, finished work lands here.
- **`beta`**: new features being tried out on the owner's phone before they go to `release`.

## Build
Open the folder in Android Studio and press Run, or use Gradle. The build needs JDK 17–21 (Gradle 8.14 won't run on JDK 25).

| Build | Command | Installs as |
|---|---|---|
| Public release | `./gradlew assembleRelease` | **Card Companion** (`com.cardprice.app`) |
| Beta | `./gradlew assembleDebug` | **Card Companion β** (`com.cardprice.app.beta`), a separate app with its own data |

- Release builds are shrunk (R8) and ARM-only, about 22 MB.
- Release builds are signed with the building computer's debug key, so copies built on the same computer install over each other and keep people's data. A copy built on another computer can't update one built here.
- Raise `versionCode` in `app/build.gradle.kts` for every copy you hand out.
- Unit tests: `./gradlew testDebugUnitTest`.

## Security
See [SECURITY.md](SECURITY.md): how data and tokens are stored, HTTPS-only networking, and release signing.

## Troubleshooting
The scanner keeps a log of what it read and did (and any crashes) on the phone. ⚙ Settings → About → *Send debug log…* shares it.
