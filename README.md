# Hootplay

An unofficial, open-source web-view shell for Android TV and Google TV. It
opens a video website's own TV interface full screen and maps the TV remote to
it.

> **Unofficial project.** YouTube is owned by Google LLC. This project is not affiliated with, endorsed by, or sponsored by
> Google LLC or YouTube LLC. "YouTube", "Google TV" and "Android TV" are
> trademarks of Google LLC, used here only to describe compatibility.
>
> **Use at your own risk.** Using this software may breach the terms of service
> of the website it loads and could lead to action against your account. You
> are responsible for how you use it. Read the full [DISCLAIMER](DISCLAIMER.md)
> before using or sharing it.

## What it is

- A single-activity Android app containing a full-screen WebView.
- A small script that decides which parts of the page's data are displayed and
  answers the page's device-capability questions using the TV's real limits.
- No content is hosted, downloaded, or redistributed. No DRM is bypassed. No
  code or assets from any proprietary app are included.

## Build

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

A signed release build needs your own `keystore.properties` and keystore in the
project root (both are ignored by git):

```bash
./gradlew assembleRelease
```

## Install on a TV

Enable developer options and network debugging on the TV, then:

```bash
adb connect <TV-IP>:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Privacy

The app has no servers, analytics, or telemetry. Anything entered into the
loaded website goes to that website under its own privacy policy.

## License

[MIT](LICENSE). Provided "as is", without warranty of any kind.
See also the [DISCLAIMER](DISCLAIMER.md).
