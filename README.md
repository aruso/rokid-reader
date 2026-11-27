# Rokid Glasses Reader

A local book reader for Rokid AI glasses. It is a plain Android app with no backend and no phone companion: books are uploaded from a computer's browser over the local network, and everything else happens on the glasses.

This is an English fork of [qhduan/rokid-reader](https://github.com/qhduan/rokid-reader). It translates the interface, wraps English text at word boundaries, and adds on-glasses font size and brightness control.

The UI is built for the **monochrome waveguide display**: white on black only, no colour and no greys, with highlights drawn as white outlines.

## Features

- Bookshelf of local books (txt / epub / md)
- Browser-based book transfer over Wi-Fi (drag and drop upload, download, delete)
- Six font sizes and seven brightness settings, adjustable on the glasses while reading
- Half, three-quarter or full-page turns
- Reading position saved automatically per book and kept when the font size changes
- Everything is driven by the temple touchpad

## Controls

The glasses pass only two gestures to an app, tap and swipe, so the reader is built around those. Long press is reserved by the system for the AI assistant, and double tap is the system Back gesture.

| Gesture | Bookshelf | Reading |
|---|---|---|
| Swipe forward / back | Move the selection down / up | Next / previous page |
| Tap | Open the book, or start / stop book transfer | Open the adjust box (see below) |
| Double tap (Back) | Exit the app | Close the adjust box, or return to the bookshelf |

### Font size and brightness

While reading, a tap steps through three modes:

1. **First tap** shows `Font: …`. Swipe forward for larger, back for smaller.
2. **Second tap** shows `Brightness: …`. Swipe forward for brighter, back for dimmer.
3. **Third tap** closes the box and swipes turn pages again. It also closes by itself after five seconds, or on a double tap.

| Setting | Steps |
|---|---|
| Font size | Micro (10 px), Tiny (12), X-Small (18), Small (22), Medium (26), Large (31) |
| Brightness | Night, Dim, Low, Medium, High, Max, System |

Brightness applies only while the reader is open. `System` follows the glasses' own brightness setting. Both settings are remembered between sessions.

## Adding books

The glasses need to be on the same Wi-Fi network as your computer.

1. On the bookshelf, select **Wi-Fi book transfer…** and tap.
2. The glasses show an address such as `http://192.168.1.100:8000`. Open it in a browser on your computer.
3. Drag txt / epub / md files onto the page. They appear on the bookshelf straight away.

The same page lists your books with download and delete buttons, and lets you set the font size and the page-turn step. Changes apply on the glasses immediately.

The transfer server has no password, so anyone on the same network can reach it while it is running. Tap the bookshelf entry again (it reads **Stop transfer**) when you are done. Files are limited to 30 MB each.

## Formats

| Format | Notes |
|---|---|
| `.txt` | UTF-8, falling back to GBK |
| `.epub` | Chapters read in spine order; chapter titles shown in bold |
| `.md` | Markdown markup stripped, shown as plain text |

mobi and PDF are not supported. [Calibre](https://calibre-ebook.com/) can convert most formats to epub.

## Build and install

Requires JDK 17 and the Android SDK (the build targets API 36; minimum is Android 9 / API 28).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Installing on the glasses needs developer mode / ADB debugging switched on in the Hi Rokid phone app.

`./gradlew assembleRelease` expects a `rokid-reader.keystore` in the repository root, which is not checked in. A release build and a debug build are signed differently, so uninstall one before installing the other:

```bash
adb uninstall com.risenav.rokid.reader
```

The debug build logs every key, scroll and touch event under the `RokidInput` tag, which is the quickest way to see what a gesture sends:

```bash
adb logcat -s RokidInput
```

## Implementation notes

- **No third-party libraries:** the HTTP server and the EPUB parser (zip + OPF + tag stripping) are hand-written.
- **Canvas-drawn UI:** no XML layouts; the bookshelf and the reading page are custom views.
- **Line-offset paging:** the whole book is wrapped into lines and the position is a starting line, which allows partial page turns. Progress is stored as a 0–1 ratio, and a re-wrap keeps the same text at the top of the page.
- **Input filtering:** one swipe arrives as several key events (`NOTIFICATION`, `LEFT`/`RIGHT`, then `UP`/`DOWN`); only the last pair is acted on.

[CLAUDE.md](CLAUDE.md) has the original author's architecture notes (in Chinese; its description of the controls predates this fork).
