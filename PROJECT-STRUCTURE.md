# Project structure

Application source lives under `app/src/main/java/com/pirachest/android/`.

- `core/downloads/` — shared download state, persistence, foreground service, and media publishing.
- `core/media/` — yt-dlp and FFmpeg runtime initialization and update handling.
- `core/player/` — shared player controls, gestures, picture-in-picture, and screen-off handling.
- `core/preferences/` — persistent application preferences.
- `feature/anime/` — anime screens, view model, catalog, stream providers, download and playback logic.
- `feature/downloads/` — download list and management UI.
- `feature/settings/` — settings and appearance controls.
- `feature/youtube/` — YouTube inspection, download, and local playback.
- `ui/` — application shell and shared theme.
