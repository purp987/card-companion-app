# Card Companion

Android app (Kotlin + Jetpack Compose) for Pokémon TCG collectors. It's the phone client of **TCG Vault**, the owner's self-hosted price tracker: the server holds the catalog, prices, portfolios and watchlists; the app adds the **card scanner** and the **pack calculator**, and mirrors the rest of the web app in its dark "deep sea" look.

## Architecture
- **Server-backed.** Everything except the scanner's on-device reading and the calculator's own set list comes from TCG Vault over its JSON API (`docs/app-api.md` in the server repo). The client is `data/vault/VaultApi.kt` (plain `HttpURLConnection`, `org.json`); `data/vault/Vault.kt` holds the settings, connection state, currency rates and the offline queue.
- **Auth.** Every request sends `Authorization: Bearer <token>`. The token comes from pairing and is stored encrypted with an Android Keystore key (`data/SecretStore.kt`). `/api/app/*` routes need it; the catalog routes work without it.
- **Offline.** Cards scanned while the server can't be reached are kept in `files/vault/pending-adds.json` and sent in order when it's back (`data/vault/PendingAdds.kt`). Settings shows the queue and any scan the server couldn't match.
- **Prices** are USD from the server and shown in the currency picked in Settings, converted with the server's daily rates (`/api/rates`).
- **Look.** Material 3 dark theme from TCG Vault's CSS tokens (`ui/theme/Theme.kt`), Space Grotesk for headings and prices, Lato for text (bundled, SIL OFL; see `licenses/`).

## Screens
Bottom bar: **Home · Explore · Scan · Portfolio · More**.
- **Home**: TCG Vault's home rows (Vault Movers, On Your Radar, Rising Stars, Cooling Off, Worth the Rip, Fresh Off the Press, Modern Heavy Hitters, Still in the Shrink, On the Horizon, Big in Japan, The Grail Gallery…). "See all" opens the matching list. Pull to refresh.
- **Explore**: search every tracked product; sort (best match, price, gainers, losers, newest, name), cards/sealed, game and minimum-price filters.
- **Product**: price with change over 7D/2W/1M/3M/6M/1Y/MAX, chart per printing with the Cardmarket trend as a dashed line, current prices, your holdings (add/edit/remove), **Slab Prices**, **Across the Pond** (Cardmarket), **Japanese Twin** for Chinese cards, **Recent eBay Comps**, shop links, details, and the Chase List / Shrink Wrap Watch star. With a PriceCharting token, a **PriceCharting** card shows its ungraded/Grade 9/PSA 10/BGS 10 (or sealed) prices.
- **Portfolio**: all portfolios or one; value, change and history chart, cost basis, **What's in the Vault** (cards/sealed/graded), **Crown Jewels**, and the holdings list (sort, edit, remove). Create, rename and delete portfolios.
- **More**: Watchlists (**Chase List** = cards, **Shrink Wrap Watch** = sealed), Sets (English/Japanese/Chinese tabs, set pages with cards and sealed), Pack Calculator, Scan history, Settings.
- **Settings**: server address, connection status, pairing, currency, which portfolio scans go to, the offline queue, scanner learning, the one-time import, PriceCharting token, debug log.

## Connecting to TCG Vault
1. Install **Tailscale** on the phone and sign in to the same tailnet as the computer running TCG Vault.
2. The default server address comes from `tcgVault.serverUrl` in `local.properties` at build time (e.g. `tcgVault.serverUrl=https://<pc-name>.<tailnet>.ts.net:8443`, the Tailscale Serve address). It's kept out of the repository; without it the app asks for an address in Settings. Change it under ⚙ Settings → *TCG Vault server* if needed. Release builds only accept https; beta builds also accept `http://localhost:3000` through `adb reverse tcp:3000 tcp:3000`.
3. In the TCG Vault web UI: **Settings → Phone app → Pair a phone**. Enter the 6-digit code in the app under Settings → *Pair this phone* (it's valid 10 minutes). The phone shows up in the web UI's list of paired devices, where it can be revoked.

## Updates over the air
The app updates itself from TCG Vault.
- It asks `GET /api/app/update?appId=<package>&versionCode=<code>` when it starts or comes back to the front (at most every 6 hours), and from ⚙ Settings → **App updates → Check for updates**, which also shows the installed version.
- A newer build shows a banner with its version and notes. **Update** downloads the APK with the pairing token (with progress), checks its SHA-256 against the published one (a mismatch deletes the file) and installs it with a PackageInstaller session. The app reopens afterwards.
- The first time, Android asks to allow **Install unknown apps** for Card Companion β (the app opens that page) and confirms the install. On Android 12+ later updates install without a prompt, because the app is then its own installer.
- An update must be signed with the same key as the installed app; otherwise Android refuses it and the app says so.

**Publishing a build** (on the server PC):
```
./gradlew :app:assembleBeta
cd E:\Projects\PersonalServer
npm run publish-apk -- "E:\Projects\card pricing app\app\build\outputs\apk\beta\app-beta.apk" --notes "What's new"
```
Raise `versionCode` first; the script refuses builds that aren't newer or are signed with a different key.

## Moving the old local collection
Settings → **Move my collection to TCG Vault** reads the old collection (`files/collection/collection.json`) and inventory (`files/inventory/inventory.json`) without changing them. *Check matches* runs `/api/app/import` as a dry run and shows how many items match; *Import matched items* then sends the collection to the portfolio **Collection** and the inventory to **Inventory**. Importing again updates the same holdings instead of duplicating them. Sold inventory and inventory rows mirrored from the collection aren't sent.

## Card scanner
Everything on the phone works as before: Google ML Kit reads the card (offline), and the auto-add policy, set/finish/language setup, binder pages, motion and same-card handling and learning are unchanged. What changed is where cards go: each identified card is looked up on TCG Vault (`POST /api/app/resolve`) and added to the chosen portfolio (`POST /api/holdings`, Near Mint, today). **Undo**, **+1** and tap/hold on a version change that holding. Results show the server's market price per version and how many you own. Offline, adds are queued and sent later.

- **Using it** (the Scan button in the middle of the bottom bar): point the camera at a card, or tap *Photo* to scan a picture.
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
- **Scan history** (*History* in the scanner): every change the scanner made, by day. Tapping an entry opens that card's TCG Vault page.

## Pack Calculator
Pick a set (English, 日本語, 简体中文), choose a product and enter what you paid: price per card and per pack, with optional tax, saved purchases and your own sets, all kept on the phone.
- Each calculator set is linked to its TCG Vault set (by printed code or name; *Not the right set? Choose another* picks one by hand) and its sealed products come from `/api/search?set=…&type=sealed`. The result turns green, amber or red against that market price (converted to your currency). Tapping a product opens its TCG Vault page with the price chart.
- **PriceCharting** (optional): paste your API token in Settings to compare its sealed prices too.
- **English**: 69 sets, XY through Mega Evolution. **Japanese**: 98 sets. **Simplified Chinese**: SV-era boosters, slim and jumbo boxes, Gem Packs (no market prices).

## Data sources
- **TCG Vault** (the owner's server): catalog, TCGplayer market prices and history, Cardmarket, graded prices, eBay comps, portfolios, watchlists.
- **TCGdex** ([tcgdex.dev](https://tcgdex.dev), MIT): card lists the scanner matches against, cached on the phone.
- **PriceCharting** (optional, your own token).

## Branches
- **`release`**: the public version that's shared with others. Only tested, finished work lands here.
- **`beta`**: new features being tried out on the owner's phone before they go to `release`.

## Build
Open the folder in Android Studio and press Run, or use Gradle. The build needs JDK 17–21 (Gradle 8.14 won't run on JDK 25).

| Build | Command | Installs as |
|---|---|---|
| Public release | `./gradlew assembleRelease` | **Card Companion** (`com.cardprice.app`) |
| Beta (phone, OTA) | `./gradlew assembleBeta` | **Card Companion β** (`com.cardprice.app.beta`), a separate app with its own data; optimized (R8, baseline profile), not debuggable |
| Development | `./gradlew assembleDebug` | the same app id, debuggable and unoptimized (slow scrolling; `run-as` works) |

- Release and beta builds are shrunk (R8) and ARM-only, about 23 MB. Beta builds are signed with this computer's debug key, like debug builds, so either installs over the other and over-the-air updates keep working.
- `src/main/baseline-prof.txt` (installed by androidx.profileinstaller) pre-compiles the app and Compose code used at startup and while scrolling.
- Release builds are signed with the building computer's debug key, so copies built on the same computer install over each other and keep people's data. A copy built on another computer can't update one built here.
- Raise `versionCode` in `app/build.gradle.kts` for every copy you hand out.
- Unit tests: `./gradlew testDebugUnitTest` (the API client, offline queue and import mapping are tested against an in-memory fake server; no network).
- Install a beta build on a connected phone without losing its data: `adb install -r app/build/outputs/apk/beta/app-beta.apk`.

## Security
See [SECURITY.md](SECURITY.md): how data and tokens are stored, HTTPS-only networking, and release signing.

## Troubleshooting
The scanner keeps a log of what it read and did (and any crashes) on the phone. ⚙ Settings → About → *Send debug log…* shares it.
