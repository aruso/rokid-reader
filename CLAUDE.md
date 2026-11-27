# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A local book reader for Rokid AI glasses. Plain Android (Kotlin, minSdk 28), no backend; books are uploaded from a computer through a LAN HTTP server the app runs itself. The target device has a **monochrome diffractive waveguide display**, so all UI is white on black with no colour and no grey fills.

This is an English fork of qhduan/rokid-reader. All user-facing strings are English; many code comments are still the original Chinese.

## Build and install

```bash
# Needs JDK 17 and the Android SDK (compileSdk 36)
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # signed release APK (needs rokid-reader.keystore, which is git-ignored)

adb install -r app/build/outputs/apk/debug/app-debug.apk
# release and debug are signed differently: adb uninstall com.risenav.rokid.reader before switching
```

There are no tests and no lint (`lint.checkReleaseBuilds=false`, because the original author's machine could not download the lint tooling).

`app/build.gradle.kts` explicitly sets `buildFeatures.buildConfig = true` (off by default in AGP 8); `MainActivity.logEvent` gates its logging on `BuildConfig.DEBUG`.

On the glasses, `adb install` only works with developer mode / ADB debugging enabled in the Hi Rokid phone app. Rokid's adbd also rejects many direct shell commands (`pm`, `wm`, `dumpsys`, `logcat`...) with `error: closed`; wrap them as `adb shell sh -c '...'`.

## Key architecture

### Rendering: all Canvas, no XML layouts

Every screen (bookshelf `LibraryView`, reading page `ReaderView`) is a custom `View` drawn on a `Canvas`. Font sizes, row heights and margins are hard-coded in pixels: to change them, edit the `textSize`, `rowHeight` and `paddingX` constants in the relevant view.

### Paging model: line offset, not a list of pages

`Book` does not keep a list of pages. The whole text is wrapped into `lines: List<String>` and the reading position is `position`, the index of the first visible line. A page turn is `position += lines per page × page-turn step`, which is what allows half and three-quarter page turns (with overlap).

**Wrapping** (`Book.paginate`): `Paint.breakText` finds how many characters fit; for space-separated scripts the break then backs up to the last space so words are not split. CJK text (code points ≥ 0x2E80) keeps per-character breaks. Leading spaces on a wrapped line are dropped.

**Position across re-wraps:** line numbers change with the font size, so two mechanisms keep the place:

- *Persisted progress* is a `progressRatio` float (0–1), never a line number.
- *Live font changes* use a text anchor. `lineStarts` holds the text offset at which each wrapped line begins, and `anchorOffset` is the offset of the top line as of the last page turn or seek. `paginate()` puts the line containing `anchorOffset` at the top. The anchor is deliberately **not** recomputed after each re-wrap; otherwise every font change would snap back to a line start and a round trip through the sizes would drift upward by a line.

**Key pitfall:** `Book.load()` only fills `paragraphs`; wrapping waits for the first `paginate()` from `ReaderView` (which needs a measured view). Calling `seekToRatio` before wrapping would compute `maxPos = 0` and reset the position to 0, so `Book` parks the ratio in `pendingRatio` and consumes it after the first `paginate()`. When `pendingRatio` is set, `paginate()` uses the ratio and ignores the anchor. The `ReaderView.book` setter must call `repaginate()` itself; it cannot rely on `onSizeChanged`, because the view size does not change when a book is opened from the bookshelf.

The upper bound in `scrollBy` is `lineCount - linesPerPage` (not `lineCount - 1`), so the last page is always full and you cannot scroll to a "dead page" with one line left.

Files are limited to 30 MB (`Book.maxFileBytes`); txt and epub are read fully into memory and larger files would run out of memory. Re-wrapping a whole novel takes one to two seconds on the glasses and runs on the main thread.

### Input: what the glasses actually deliver

The temple touchpad gives an app only **tap** and **swipe**:

- **Swipe** arrives as four keys: `NOTIFICATION`, then `RIGHT`/`LEFT`, then `DOWN`/`UP`.
- **Tap** arrives as `NOTIFICATION` followed by `KEYCODE_ENTER`, plus touch events (`ACTION_DOWN/UP`).
- **Double tap** is turned into `KEYCODE_BACK` by the system before the app sees it.
- **Long press** starts the Rokid AI assistant. The app receives only the leading `NOTIFICATION`; the assistant overlay (`com.rokid.os.sprite.assistserver`) then takes input focus until dismissed.

Do not design features around long press or double tap; both were tried for brightness and neither can work.

In `MainActivity.dispatchKeyEvent`:

- `NOTIFICATION` and `RIGHT`/`LEFT` are consumed and ignored (leading noise).
- Only `UP`/`DOWN` drive navigation, so one swipe never moves twice.
- `BACK`: closes the adjust box if one is open; otherwise returns to the bookshelf from the reader, or exits from the bookshelf.
- `ENTER` on the bookshelf opens the selected book or toggles book transfer (handled on key down).
- `ENTER` in the reader steps the adjust mode (handled on key **up**, in `onReaderConfirmKey`). `confirmArmed` makes sure the press started in the reader, so the release of the tap that opened a book is not counted.

**Adjust modes** (`MainActivity.Mode`): a tap steps `READ → FONT → BRIGHTNESS → READ`. Outside `READ`, a swipe calls `adjust(dir)` instead of turning the page: `ReaderView.stepFontSize(dir)` or `stepBrightness(dir)`, both clamped at the ends. The mode falls back to `READ` after `ADJUST_TIMEOUT_MS` (5 s) without input. `ReaderView.overlay` holds the label drawn at the top of the page (`Font: …` / `Brightness: …`); setting it to null hides it.

**A tap sends both a touch and a key, and only one channel may be bound to an action.** Touch handling on the reading page was removed (`MainActivity.onTouchEvent` just calls `super`); otherwise one tap would act twice. On the bookshelf, tap-to-select is handled by `LibraryView.onTouchEvent`, with a 24 px touch slop to tell taps from swipes.

Some input devices send `MotionEvent.ACTION_SCROLL` (`AXIS_VSCROLL`) instead of keys. That is intercepted in `dispatchGenericMotionEvent` (child views would otherwise grab `onGenericMotionEvent`) and goes through the same `adjust()` check as key swipes.

Debugging input: `adb logcat -s RokidInput` prints every key, scroll and touch event (debug builds only; `BuildConfig.DEBUG` turns it off in release). When simulating input with `adb shell sh -c 'input keyevent ...'`, check `dumpsys window | grep mCurrentFocus` first: if the assistant overlay has focus, the keys never reach the app. The display also sleeps within seconds, and screenshots are black while it is asleep (`input keyevent KEYCODE_WAKEUP`).

### Brightness

`SettingsStore.brightnessIndex` is an index into `BRIGHTNESS_LEVELS` (Night … Max), or `-1` for "System", meaning no override. `MainActivity.applyBrightness()` sets `window.attributes.screenBrightness`, a per-window override: it applies only while the reader is in front and leaves the system setting untouched. In the UI, "System" sits one step past Max. The panel's minimum backlight is about 0.035, which is why Night is 0.036.

### LAN server: hand-written, zero-dependency HTTP

`TransferServer` is a minimal hand-written HTTP server (no NanoHTTPD: the original author could not fetch dependencies, and only a few endpoints are needed). **Key pitfall:** request parsing must work on the raw byte stream and split headers from body at `\r\n\r\n`. Never use `BufferedReader.readLine()`: its internal buffer swallows part of the body, so reading the body by `Content-Length` never completes and blocks until timeout (the browser reports `ERR_EMPTY_RESPONSE`).

Multipart uploads likewise find the boundary in the **raw byte array** (`indexOf(ByteArray, ByteArray)`), never by decoding to a String and calling `indexOf`: the boundary byte sequence can appear inside binary content (an epub is a zip), and string decoding corrupts the match and truncates the file.

The server also provides `/api/books`, `/api/settings`, `/download/xx` and `/delete/xx`. The management page (book list, download, delete, font size, page-turn step) is a single HTML file embedded in `managePage()`. Downloads are streamed with `inputStream().copyTo(output)` rather than read into memory. The request body limit is 200 MB.

The font-size buttons on the page are hard-coded (`data-v="0"` … `"5"`) and must be kept in step with `SettingsStore.FONT_SIZES` / `FONT_LABELS`. Brightness is not exposed on the page.

When an upload has a duplicate name, `uniqueFile()` **keeps the original extension** (`name_1.epub`, not `name_1.txt`; the latter would send `Book.load()` to the wrong parser). `title` strips the extension with `substringBeforeLast('.')` (do not hard-code `removeSuffix(".txt")`).

The server has no authentication and listens on port 8000 only while book transfer is switched on.

### Format parsing: routed by extension

`Book.load()` dispatches on the file extension:

- `.epub` → `EpubParser` (unzip + OPF spine order + regex tag stripping, zero dependencies)
- `.md` → read as plain text, then Markdown markup stripped
- anything else → plain text with encoding detection (strict UTF-8, falling back to GBK)

**EpubParser pitfalls:**

- Only `.opf` / `.html` / `.xhtml` / `.htm` / `.ncx` entries are read from the zip; images, fonts and CSS are skipped to save memory.
- OPF `<item>` / `<itemref>` tags can span lines (newlines between attributes). The regex must use `DOT_MATCHES_ALL` to match the whole tag and then extract attributes; `<item[^>]*` is not enough.
- An OPF `href` can contain `../` (the OPF lives in a subdirectory and references a parent). Joining `$opfDir/$href` directly gives `OEBPS/../text/ch1.xhtml`, so it goes through `normalizePath()`.
- When empty chapters are skipped, chapter numbering must use the **spine index** (`spine.withIndex()`), not `chapters.size`, or numbers are skipped. The fallback title is `Chapter N`.
- The list of XHTML block-level tags must include `td` / `th`, or table cells run together on one line.
- Chapter titles are inserted as their own paragraph wrapped in `「」`; `ReaderView.onDraw` recognises that and draws the line in bold.

### Settings and progress persistence

- `LibraryStore`: SharedPreferences + JSON; the bookshelf (uri / title) plus a progress ratio per book.
  - **Progress keys use the full uri string** (`progress:file:///...`), not `uri.hashCode()`. Older versions used the hash and could collide; `migrateProgressKeys()` in the `init` block migrates old keys once and removes orphaned keys for deleted books.
  - `BookEntry.progress` is not written to JSON (`saveBooks` stores only uri / title); `loadBooks` looks it up in SharedPreferences.
- `SettingsStore`: SharedPreferences; font size index, page-turn step (0.5 / 0.75 / 1.0) and brightness index.
  - `FONT_SIZES` is `10, 12, 18, 22, 26, 31` px, labelled Micro, Tiny, X-Small, Small, Medium, Large. The stored value is the index, and the default is 0, so a fresh install starts on Micro (10 px).
  - Both the setter and the getter of `fontSizeIndex` must `coerceIn`; the web page can send out-of-range values, and the meaning of a stored index changes if sizes are added or removed.
- After a settings change from the web page, the server calls `onLibraryChanged` → `ReaderView.applySettings()`, which re-wraps the text.

### Bookshelf UI pitfalls

- `LibraryView.hitTest` must start at `listStartY` (not `listStartY - 58f`). The "Bookshelf" title is drawn at y=88; starting at 74 treats the title area as the first row, so tapping the title opens the first book.
- `ellipsize` computes the cut position once with `Paint.breakText` (O(n)). Do not loop `dropLast + measureText` (O(n²), and it can split UTF-16 surrogate pairs / emoji).
- Toggling the transfer server changes the height of the `ServerToggle` row (76 ↔ 130); the `serverRunning` setter must call `ensureSelectedVisible()` so the selected row is not pushed out of view.

## Things to watch when making changes

- **Never write `*/` inside a class comment.** The class comment in `TransferServer.kt` once contained `/download/*`, whose `*/` closed the comment early and broke compilation of the whole file. Write `xx` in place of `*` when documenting endpoints.
- **Non-ASCII file names:** the multipart `filename=` field may arrive as raw UTF-8 bytes or percent-encoded. `TransferServer.decodeFileName()` must handle both (URLDecoder first, then re-decode latin-1 → UTF-8).
- **Monochrome UI:** new UI elements use a `Color.BLACK` background and `Color.WHITE` text, with highlights as white outlines only (`Paint.Style.STROKE`). No grey fills; the display cannot show grey levels.
- **Status feedback while reading** goes through `ReaderView.overlay`, not Toasts. Toasts are still used for errors and the server-started message.
- The keystore password is hard-coded in `app/build.gradle.kts` (a personal project of the original author); it should move to an environment variable.
- **Do not add external intents.** The manifest has no ACTION_VIEW intent filter; every book must come through the bookshelf (LAN upload). Opening txt files directly from a file manager was once supported, but `content://` uris could not be added to the bookshelf and progress restore took a different path, so it was removed.
