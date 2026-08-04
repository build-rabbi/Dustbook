# Dustbook

An Android client for Facebook that behaves like an installed app rather than a
browser tab: no ads, no "Get the app" banners, offline reading, and in-app
updates.

Dustbook wraps the real Facebook mobile site in a WebView and cleans it up. You
sign in on Facebook's own form, you browse Facebook's own pages, and Facebook's
own layout is what you see — just without the advertising, the install prompts,
and the parts of the feed you did not ask for.

**Current release: [v4.11.1](../../releases/latest) · Android 8.0+ · ~14 MB**

---

## Contents

- [Why a WebView](#why-a-webview)
- [Is my account at risk?](#is-my-account-at-risk)
- [Install](#install)
- [Hidden settings](#hidden-settings)
- [Features](#features)
- [How offline works](#how-offline-works)
- [Privacy](#privacy)
- [Permissions](#permissions)
- [Building from source](#building-from-source)
- [Credits](#credits)

---

## Why a WebView

A native Facebook client would have to reverse-engineer a private API, and
Facebook does not grant feed access to third-party apps. Every "native Facebook
client" either scrapes, or asks for credentials it should not have, or breaks
whenever Facebook ships a change.

Dustbook takes the opposite approach. The WebView **is** the client. Facebook
renders its own HTML, runs its own JavaScript, and manages its own session
exactly as it would in Chrome. Dustbook only adds a filtering layer on top and
a cache underneath.

That has three consequences worth knowing:

- **Nothing is faked.** The feed you see is the feed Facebook served.
- **Nothing breaks silently.** When Facebook changes their markup, at worst a
  filter stops matching — the app still works.
- **Nothing unusual reaches Facebook.** More on that next.

---

## Is my account at risk?

**Short answer: no more than using Facebook in Chrome with an ad blocker.**

This is the most common question, so here is exactly what the app does and does
not do, all of which you can verify in the source.

### What Facebook sees

| | |
|---|---|
| **Session** | Standard Android `CookieManager`. The same cookie jar any WebView app uses. |
| **User agent** | The device's own unmodified WebView user agent. Only the `wv` token is dropped, which is what every "open in app" browser does. Nothing is spoofed to impersonate the official app. |
| **Login** | Typed by you into Facebook's own login form, submitted by Facebook's own button. |
| **Requests** | Ordinary page loads from a real browser engine. |

There is no fake device fingerprint, no forged official-app headers, and no
attempt to look like something the app is not. From Facebook's side this is a
mobile browser session — because that is precisely what it is.

### What the app does *not* do

- **No Facebook API access.** No `access_token`, no Graph API calls, no
  `read_stream`. Search the source: there are none.
- **No automation.** No auto-liking, auto-following, auto-posting, auto-adding
  friends, no bulk actions, no bots. Every interaction is a real tap by you.
  This matters, because automation is what actually gets accounts restricted.
- **No credential storage.** Your password is never written to disk, never sent
  anywhere except Facebook's own form, and is not held in memory after
  submission.
- **No third-party servers.** The app talks to `facebook.com` and to GitHub for
  update checks. That is the complete list.
- **No account manipulation.** Nothing is posted, sent, deleted, or changed on
  your behalf.

### The honest caveats

Two things deserve a straight answer rather than a marketing one:

**1. Ad blocking is against Facebook's Terms of Service.**
So is every ad blocker, on every site. In practice Facebook responds by trying
to defeat blockers, not by banning readers — there is no known case of an
account being disabled purely for blocking ads. But "no known case" is not the
same as a guarantee, and this README will not pretend otherwise.

**2. Offline sync loads pages in the background.**
To make content available offline, a background WebView opens your feed, reels
and stories the same way you would by scrolling. It is ordinary browsing at
ordinary speed, not scraping — but it is still activity you did not personally
initiate. If that bothers you, turn it off:
**Offline → Save feed / Save reels / Save stories → all off.**

Everything else — the ad filtering, the banner removal, the section hiding —
happens locally in the page after Facebook has already served it. Facebook has
no way to observe it.

> **Use your judgement.** This is an unofficial client that blocks ads. If your
> account carries something you cannot afford to lose, that is a reason for
> caution with *any* third-party client, including this one.

---

## Install

1. Download the APK from [Releases](../../releases/latest).
2. Allow installation from unknown sources when Android asks.
3. Open it and sign in on Facebook's own login screen.

Updates are offered inside the app when a new release is published. Every build
is signed with the same certificate, so updates install over the previous
version without uninstalling.

---

## Hidden settings

**There is no settings icon anywhere in the app.** This is deliberate: the
interface should be Facebook's, not a wrapper's.

### Put three fingers on the screen and double-tap — or hold them still

Two gestures open this screen. Either one works.

<table>
<tr><td><b>Gesture</b></td><td>Three fingers down, tap twice <i>or</i> three fingers down, hold still</td></tr>
<tr><td><b>Where</b></td><td>Anywhere on the main screen — over the feed, over a video, over anything</td></tr>
<tr><td><b>Double tap timing</b></td><td>Each tap under 700 ms (measured until the first finger lifts); both taps within 900 ms of each other</td></tr>
<tr><td><b>Long press</b></td><td>Hold all three fingers still for about 0.8 s</td></tr>
<tr><td><b>Movement</b></td><td>Keep your fingers still — a drag is read as a scroll, not a tap</td></tr>
</table>

A short vibration confirms it worked (this can be turned off later under
*Browsing → Haptic feedback*).

The long press exists because some phones' system gestures swallow multi-touch
taps (three-finger screenshot and similar), which made the double tap
impossible to land on those devices. If the double tap does not open, just hold
the three fingers still for a moment instead.

The detector only *watches* touch events and never consumes them, so scrolling,
tapping and long-pressing are completely unaffected by it.

---

## Features

The settings screen is organised into seven sections.

### Blocking

| Setting | Default |
|---|---|
| Ad blocker (network level) | On |
| Hide ad elements (cosmetic) | On |
| Blocked-request counter, with reset | — |
| Filter list info | — |

Three independent layers:

1. **Network** — requests to known ad and tracker domains never leave the
   device, matched against a bundled list of ~656,000 domains. Facebook's own
   infrastructure (`fbcdn.net`, `graph.facebook.com`, `fbsbx.com`) is on a hard
   allowlist so media, login and the feed itself are never affected.
2. **API response** — sponsored posts are removed from Facebook's own GraphQL
   responses before the page renders them. This is the layer that actually
   works, because feed CSS class names are randomised and cannot be targeted.
3. **Cosmetic** — precise selectors plus a small set of structural rules for
   anything the first two layers miss.

App-install promotion is handled separately and always on: Play Store links,
`market://`, `fb://` and `intent://` navigations are swallowed, and "Get
Facebook for Android" style banners are removed before the page paints.

### Home page sections

Hide any of these from the feed — all off by default:

Stories · Reels · Rooms · People You May Know · Suggested Pages · Memories ·
Birthdays · Marketplace · Groups · Watch · Events · Gaming

### Appearance

- Theme: follow system / light / dark
- AMOLED black *(on by default)*
- **13 app icons to choose from** — the launcher icon changes immediately
- Loading progress bar

### Browsing

- Desktop site
- Pinch to zoom
- Pull to refresh
- Autoplay videos *(on by default)*
- **Background audio** — audio keeps playing when you leave the app
- Keep screen on
- Open outside links in the system browser
- Haptic feedback

### Offline

Save feed posts, reels and stories for reading with no connection. The number
of reels to keep is configurable (30–250). A live status line shows what is
currently stored, and everything can be cleared in one tap.

### Privacy & data

- Remember last page
- Clear cache
- Clear cookies
- Reset everything

Each destructive action asks for confirmation.

### About

App version, filter list details, the gesture reminder, an update check, and a
diagnostic toggle for inspecting ad markup.

---

## How offline works

Offline content is **the real Facebook page, served from disk** — not a
reconstruction. Dustbook stores Facebook's own markup, so an offline post looks
identical to an online one, with the same Like, Comment and Share controls in
the same places.

```
Background WebView                          Your browsing is never captured;
  loads feed / reels / stories              only the background pass stores
        │                                   anything
        ├─ page markup ──────► document store
        └─ media URLs ───────► download queue ──► media cache (LRU)
                                                        │
                        no connection                   │
                              └──────────► requests answered from disk
```

When the connection drops, page requests are answered from the stored document
and media requests from the cache. Video is served with byte-range support, so
seeking works exactly as it does online.

Storage is capped and trims itself oldest-first. Nothing you view while online
is saved — content comes only from the background pass, so the same reels do
not reappear after you have watched them.

---

## Privacy

Dustbook has **no backend**. There is no account, no telemetry, no analytics,
no crash reporting, and no data collection of any kind.

| Data | Where it lives |
|---|---|
| Facebook session cookie | Android's standard cookie store, on your device |
| Offline pages and media | App-private storage, on your device |
| Settings | App-private preferences, on your device |

Network connections are made to exactly two places: **Facebook** (including
`fbcdn.net`, `fbsbx.com` and `messenger.com`, which are Facebook's own
domains), and **GitHub** to check for updates. Nothing else.

Uninstalling removes all of it.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Load Facebook; detect going offline |
| `CAMERA`, `RECORD_AUDIO` | Only when you tap a camera or voice control on a Facebook page |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Attaching photos and videos to posts and messages |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Only when a Facebook page requests location, and only after you allow it |
| `POST_NOTIFICATIONS` | Download progress |
| `FOREGROUND_SERVICE`, `..._MEDIA_PLAYBACK` | Background audio playback |
| `REQUEST_INSTALL_PACKAGES` | Installing in-app updates |
| `VIBRATE` | Haptic confirmation of the settings gesture |

The media and location permissions exist so that Facebook's own features work
inside the WebView. The app never uses them on its own initiative.

---

## Building from source

Requires JDK 17 and the Android SDK (compileSdk 34).

```bash
git clone https://github.com/build-rabbi/Dustbook.git
cd Dustbook
./gradlew assembleRelease
```

The release build produced this way is **unsigned** — the signing key is not in
the repository. To sign your own build, create `keystore.properties` in the
project root:

```properties
storeFile=your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

An APK signed with a different key cannot be installed over an official
release; uninstall first.

### Tests

The regression suites run in CI on every push and must pass before a release:

```bash
npm install jsdom --no-save
for t in tools/test_*.js; do node "$t"; done
```

They cover ad detection, the guarantee that the feed is never hidden wholesale,
the offline pipeline, the update flow, and app-like behaviour.

---

## Credits

Filter rules are derived from
[uBlock Origin](https://github.com/gorhill/uBlock),
[AdGuard](https://github.com/AdguardTeam/AdguardFilters),
EasyList and EasyPrivacy. Those lists are the work of their respective
maintainers and are used under their original licences.

---

## Disclaimer

Dustbook is an unofficial, independent project. It is not affiliated with,
endorsed by, or connected to Meta Platforms, Inc. "Facebook" is a trademark of
Meta Platforms, Inc.

The app is provided as-is, with no warranty. You are responsible for your own
use of it, including compliance with Facebook's Terms of Service.
