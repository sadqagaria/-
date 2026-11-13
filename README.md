# Satellite World TV — Android APK

This branch contains an original Android Java/Media3 receiver-style MVP created for this account. It does not change the repository's default branch.

- Internet TV directory: https://github.com/iptv-org/iptv (public M3U listing).
- Internet radio directory: https://github.com/eyalev/verified-radio-streams (public M3U listing).
- Country/category filters, search, favorites, channel numbers, next/previous, import a local M3U file, cached playlists.
- Plays HTTP(S) media via AndroidX Media3 including HLS/DASH when supported.
- Public listings do not imply content licensing, guaranteed access, or working streams.
- Satellite names are *filters only*: a satellite DVB frequency does not imply internet streaming.

**Build:** Actions > Satellite World TV - Build APK > run > Artifacts > SatelliteWorldTV-debug-apk > app-debug.apk.

**Status:** Initial MVP. The earlier full Kotlin source bundle is separate; this branch exists specifically for APK production. It does not yet implement Enigma2 imports, EPG, assured 1000 channels per satellite, parental locks, or background radio service. App must be tested on an actual device.
