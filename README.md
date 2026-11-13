# Satellite World TV v0.3 — Build branch

This isolated branch builds a receiver-style Android IPTV application in Kotlin/Jetpack Compose/Media3. **Main is untouched.**

- v0.3 uses the base source archive `satellite-kotlin-v02.tar.xz` plus the verified patch `swt_v03_relative.diff.xz`, then compiles and tests it on GitHub Actions.
- TV directories: [iptv-org](https://github.com/iptv-org/iptv), [Free-TV/IPTV](https://github.com/Free-TV/IPTV); EG metadata: [iptv-org/database](https://github.com/iptv-org/database). Radio: [verified-radio-streams](https://github.com/eyalev/verified-radio-streams).
- Features: bilingual search, Egypt/Nilesat-oriented metadata guide, genre filters, full-screen receiver, CH±, fallback links, favorites, M3U/CSV/Enigma2/EPG import, HLS-TS recording by user-chosen folder/name and source quality.
- **Important:** Egyptian origin is not proof of Nilesat carriage. Directory-only != playable. Not every channel has an authorized internet stream, and some links are geo-restricted.
- HLS recording only for unencrypted TS streams with integrated audio. Quality choices are dependent on actual available variants; no DRM bypass or promise of lossless size compression.
- Read full application README on the exported source branch if available.

[APK builds](https://github.com/sadqagaria/-/actions/workflows/build-apk.yml) | [Source export branch](https://github.com/sadqagaria/-/tree/receiver-kotlin-source-v03)
