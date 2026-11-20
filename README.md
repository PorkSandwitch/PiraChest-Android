# PiraChest for Android

An Android version of PiraChest, focused on anime and YouTube.

> [!WARNING]
> **AI-assisted development:** Some parts of this Android project have been updated, debugged, and refactored with the help of freely available AI models and tools. AI-generated suggestions were used during development, so the code may still contain mistakes or unfinished work. Please review changes, test the app, and report anything that does not work as expected.

This project is an **Android adaptation based on [TheJustRun/PiraChest](https://github.com/TheJustRun/PiraChest)**. It is not the original desktop application and is not an official release from the original maintainer. The Android code has been adapted for Kotlin, Android, and Jetpack Compose, with a narrower feature set.

## What it supports

This build currently focuses on two content areas:

- **Anime** – discover anime, browse available episodes, find playable streams through the integrated providers, and download supported episodes.
- **YouTube** – inspect supported YouTube links and download video or audio, with available format and quality options.

Other download categories from the desktop project are not part of this Android version. At the moment, anime and YouTube are the only supported download sections.

## Features

- Anime discovery and catalogue information through AniList.
- Episode browsing and stream selection from the providers included in the app.
- Anime episode downloads when a compatible source is available.
- YouTube link inspection and video/audio downloads using **yt-dlp**.
- **FFmpeg** integration for media processing and supported format handling.
- Download queue with progress information and download history.
- Local playback for downloaded videos.
- Playback controls, picture-in-picture support, and screen-off playback handling where supported by Android.
- Settings for app appearance and playback/download preferences.

Provider availability can change. A source may stop working or return different streams without an app update, and results depend on the source website and your network connection.

## A note about content

PiraChest does not host media or store a catalogue of hosted videos. It works with information and media sources provided by third-party services and websites.

Please respect copyright, the terms of the sites you use, and the laws that apply where you live. You are responsible for how you use the app and for the content you access or download.

## Installation

Download the APK from the repository's **Releases** page when a release is available.

The release build is configured for `arm64-v8a` devices. The minimum supported Android version is Android 8.0 (API 26). Android may ask you to allow installation from your browser or file manager when installing an APK manually.

## Build from source

### Requirements

- Android Studio with Android Gradle Plugin 8.10.1 support
- JDK 17
- Android SDK Platform 36
- Gradle 8.11.1
- An Android device or emulator (API 26 or newer)

### Steps

1. Clone the repository:

   ```bash
   git clone https://github.com/YOUR-USERNAME/PiraChest-Android.git
   cd PiraChest-Android
   ```

2. Open the project in Android Studio.
3. Install Android SDK Platform 36 if Android Studio prompts you to.
4. Set the Gradle JDK to Java 17 and sync the project.
5. Run the `app` configuration, or build an APK from the Build menu.

The repository does not include `local.properties`; Android Studio creates this file for your local SDK installation. Do not commit your own `local.properties` file.

### Release builds

A release APK must be signed with your own release keystore. Do not use a debug key for a public release. Test the signed APK on a physical device before distributing it.

## Tech stack

- Kotlin
- Jetpack Compose and Material 3
- AndroidX Media3 for playback
- yt-dlp through the `youtubedl-android` library
- FFmpeg through the `youtubedl-android` FFmpeg package
- OkHttp and Jsoup for network and page handling
- AniList for anime catalogue information

## Project status

**Version:** `0.1.0`

This is an early Android release. Some features may be incomplete, and third-party source changes can affect stream discovery and downloads. If you find a bug, please open an issue with your Android version, device model, the steps to reproduce it, and relevant logs. Avoid posting private information or account tokens.

## Credits

- **Original project:** [PiraChest by TheJustRun](https://github.com/TheJustRun/PiraChest) – the project this Android adaptation is based on.
- **yt-dlp** – YouTube media extraction.
- **FFmpeg** – media processing.
- **AniList** – anime catalogue data.
- The developers and maintainers of the libraries listed in the project dependencies.

This repository is maintained separately from the original desktop project. Please report Android-specific issues here; issues with the original desktop application should be reported to its [upstream repository](https://github.com/TheJustRun/PiraChest).

## License

This Android adaptation is based on the original PiraChest project, which is licensed under the **PolyForm Noncommercial License 1.0.0**. This project follows the same license for the portions derived from or covered by the upstream project. The license does not grant permission for commercial use. Check the license terms before using, modifying, or redistributing this project.

Some third-party libraries and components may be distributed under their own licenses. Those remain subject to their respective license terms.
