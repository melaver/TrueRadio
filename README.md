# TrueRadio

A standalone Android app (Kotlin + Jetpack Compose) that layers a witty, Hebrew-speaking AI
radio host on top of your own Spotify playback: hourly news flashes (personalized to topics you
pick) and between-track trivia, spoken with ElevenLabs and mixed in with audio ducking so it
never talks over the music.

> **Scaffold status:** this is a complete, modular starting codebase — architecture, wiring, and
> the DJ prompt/voice tuning are all implemented — but a few integration points are marked with
> `// TODO`/comments where you'll want to fill in production details (see "Known gaps" below)
> before shipping, in particular around Spotify's Web API search/queue lookups and secure key
> storage.

## Project layout

```
TrueRadio/
├── build.gradle.kts                 # top-level plugins
├── settings.gradle.kts              # module + repo config (adds JitPack for App Remote)
├── app/
│   ├── build.gradle.kts             # dependencies: Compose, Retrofit/OkHttp, Media3, App Remote
│   ├── proguard-rules.pro
│   ├── libs/                        # <- put spotify-app-remote-release-x.x.x.aar here
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/trueradio/app/
│       │   ├── RadioApplication.kt          # notification channel setup
│       │   ├── Models.kt                    # TrackInfo, DaySegment, DjUiState
│       │   ├── SecureSettings.kt            # DataStore-backed key storage
│       │   ├── spotify/SpotifyManager.kt    # App Remote connect/subscribe/controls
│       │   ├── spotify/SpotifyWebAuthManager.kt # Web API OAuth (PKCE) - separate from App Remote
│       │   ├── spotify/SpotifyWebApiClient.kt   # top artists/tracks, genre search, playlists
│       │   ├── spotify/HourlyMixEngine.kt       # composes the personalized hourly genre mix
│       │   ├── ai/GeminiClient.kt           # persona prompt + generateContent calls
│       │   ├── news/NewsRepository.kt       # RSS fetch + parse + preference-based prioritization
│       │   ├── tts/TtsManager.kt            # ElevenLabs REST + local TTS fallback
│       │   ├── audio/AudioPlaybackManager.kt# AudioFocusRequest ducking + ExoPlayer
│       │   ├── service/RadioForegroundService.kt # orchestrates everything
│       │   └── ui/MainActivity.kt           # Compose UI: key inputs, connect toggle
│       └── res/values/{strings.xml,themes.xml}
└── README.md
```

## 1. Spotify Developer Dashboard setup

1. Go to <https://developer.spotify.com/dashboard> and log in with the Spotify account you'll
   test with.
2. Click **Create app**. Fill in an app name/description (anything).
3. Under **Redirect URIs**, add **both**: `trueradio://callback` (App Remote) and
   `trueradio://spotify-web-callback` (Web API OAuth, used by the personalized hourly genre mix
   — see section 6). Missing either one means that specific feature can't connect later.
4. Under **Which API/SDKs are you planning to use?**, check **Android** and **Web API**.
5. Save, then open **Edit Settings** and add your app's **package name** (`com.trueradio.app`)
   and the **SHA-1 fingerprint** of the debug keystore this project ships with:

   ```bash
   keytool -list -v -keystore app/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```

   **Important:** this project uses a fixed, committed `app/debug.keystore` (wired up in
   `app/build.gradle.kts`'s `signingConfigs`) instead of the usual per-machine
   `~/.android/debug.keystore`. That's deliberate: if every machine — or worse, every ephemeral
   GitHub Actions run — generated its own random debug key, the SHA-1 you register here would
   stop matching after the very next build. Because everyone builds against the same checked-in
   key, the SHA-1 you register once stays valid forever, locally and in CI alike. **Never do
   this for a release keystore** — that one must stay private and out of version control.
6. Copy the **Client ID** shown on the dashboard — this is what you paste into the app's
   "Spotify Client ID" field (or hardcode into `SecureSettings` for local testing).
7. Download the **Spotify App Remote SDK** AAR from the same dashboard's SDK page (or
   <https://github.com/spotify/android-sdk> releases) and drop the `.aar` file into `app/libs/`.
   It isn't published to Maven Central, which is why `settings.gradle.kts` also configures
   JitPack as a fallback repository and `app/build.gradle.kts` pulls in anything under `app/libs`.
8. Make sure the official **Spotify** app is installed on the test device/emulator and that
   you're logged in — App Remote connects to that app over IPC, it does not stream audio itself.

## 2. Gemini API key

1. Get a key from <https://aistudio.google.com/app/apikey>.
2. Paste it into the "Gemini API Key" field in the app (stored via DataStore Preferences —
   see the security note below).
3. The app calls `gemini-2.5-flash` via the REST `generateContent` endpoint directly with
   OkHttp/Gson (no SDK dependency needed) — see `GeminiClient.kt`.

## 3. ElevenLabs API key + voice

1. Get a key from <https://elevenlabs.io> (Profile → API Keys).
2. Pick or clone a voice and copy its **Voice ID** from the Voice Library / "My Voices" page.
3. Enter both in the app. `TtsManager.kt` calls `eleven_multilingual_v2` with:
   - `stability: 0.4`, `similarity_boost: 0.8`, `style: 0.2`, `use_speaker_boost: true`
   (tunable within the ranges you specified — see the constants at the top of the file).
4. If the ElevenLabs call fails for any reason (offline, quota, bad key), `TtsManager`
   automatically falls back to Android's built-in `TextToSpeech` engine using a Hebrew locale.

## 4. Build & run

```bash
# from the TrueRadio/ directory, with Android Studio's Gradle or the CLI:
gradle wrapper --gradle-version 8.7   # generates gradlew if you don't already have it
./gradlew assembleDebug
```

Open the project in Android Studio (Giraffe/Koala or newer) for the easiest first run — it will
prompt to sync Gradle and download the SDK/platform automatically. Install on a device with the
Spotify app already installed and logged in.

### Exporting an APK without Android Studio

If you don't have Android Studio or a local Android SDK, use the included GitHub Actions
workflow (`.github/workflows/build-apk.yml`) instead — it builds on GitHub's own servers, which
have full access to the Android SDK and Google's Maven repo:

1. Push this project to a GitHub repo (`git init && git add . && git commit -m "init" && git
   push` to a new repo you've created on GitHub).
2. Before pushing, add the Spotify App Remote `.aar` to `app/libs/` and remove the
   `app/libs/*.aar` line from `.gitignore` so it's actually committed — the CI runner can't
   download it for you since it isn't published to Maven (see step 1.7 above).
3. The workflow runs automatically on every push to `main`/`master`, or trigger it manually from
   the repo's **Actions** tab → "Build debug APK" → **Run workflow**.
4. When the run finishes (a few minutes), open it and download the `TrueRadio-debug-apk` artifact
   from the **Artifacts** section at the bottom of the run page — that's a zip containing
   `app-debug.apk`, installable on any Android device with "install from unknown sources" enabled.

This produces a **debug**-signed APK, fine for personal use and testing. For a Play Store release
you'll additionally need to generate a release keystore and add a signing config — Android
Studio's Build → Generate Signed Bundle/APK wizard is the simplest way to do that.

## 5. News preferences & sources

Below the API key fields:

- **News sources**: add any RSS feed URL you trust (name + URL), toggle each on/off, or remove
  it. Four sources ship enabled by default, each verified to return valid RSS at the time of
  writing:
  - **Ynet** — general Israeli news (Hebrew)
  - **Formula1.com** — official F1 news feed
  - **RaceFans** — independent motorsport coverage (F1, IndyCar, WEC, Formula 2, and more)
  - **PetaPixel** — photography and camera news

  I didn't ship a dedicated WRC, MotoGP, or Formula E feed by default because I couldn't verify
  a stable, current RSS endpoint for those specifically — several "known" URLs I found were
  either broken or pointed to HTML index pages rather than actual feeds. RaceFans and
  Formula1.com give solid motorsport coverage out of the box; add a WRC/MotoGP/FE-specific feed
  yourself from the News Sources screen if you find one you trust, and it'll be included the
  same way. `NewsRepository` fetches every enabled feed in parallel, merges and de-duplicates
  the headlines, and continues with whatever succeeded if one feed is temporarily down.
- **Categories & liked topics**: pick predefined categories (General, Sports, Economy, Tech,
  Entertainment, World, Health, Motorsport, Photography) as filter chips, and optionally add
  free-text "liked topics" (e.g. a favorite driver, team, or camera brand) as a comma-separated
  list. The Motorsport category's keyword list covers F1, F2, F3, Formula E, WRC, WEC, MotoGP,
  Moto2/3, IndyCar, NASCAR and Le Mans in both Hebrew and English.

This is implemented as keyword-based prioritization (`NewsRepository.prioritizeByPreferences`):
matching headlines from your enabled feeds are moved to the front of the merged list, and the
same liked-topics list is also passed into the Gemini prompt (`GeminiClient.hourlyNewsPrompt`)
so the DJ gives matching stories extra emphasis when writing the hourly script.

## 6. Personalized hourly genre mix

The DJ can automatically switch music genre at the top of every hour while staying inside your
own taste, via a second, separate Spotify authorization (Web API OAuth, not App Remote):

**Why a second auth flow:** App Remote (the connection you set up in step 1) only lets the app
*control playback* on your device - it can't read your top artists, tracks, or manage playlists.
Personalizing to "your algorithm" needs the Spotify **Web API**, which requires its own OAuth
grant and scopes.

**Important - `/recommendations` is dead:** Spotify permanently deprecated the
`GET /v1/recommendations` endpoint (and `audio-features`/`audio-analysis`) for all apps created
after November 27, 2024 - it now 404s for new API clients with no replacement. Older tutorials
and sample code that build a "genre + seed tracks" recommendation this way no longer work.

**Important - the February 2026 Development Mode migration:** Spotify also restricted several
more endpoints for apps in Development Mode (see
[the official migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)),
three of which this feature depends on:
- `POST /users/{user_id}/playlists` (create playlist) → returns **403 for every caller** now;
  replaced by `POST /me/playlists`. If you see "create playlist failed 403", this is why - it
  means you're on a build from before this fix, not a setup problem on your end.
- `PUT /playlists/{id}/tracks` (replace playlist contents) → renamed to
  `PUT /playlists/{id}/items`.
- `GET /artists/{id}/top-tracks` → **removed entirely, no replacement.** The original widening
  strategy ("search artists by genre, then fetch each one's top tracks") depended on this and
  would fail on every call post-migration.

This app now:
1. Reads your real top artists/tracks (`/v1/me/top/artists`, `/v1/me/top/tracks`) - this **is**
   Spotify's own taste model's output for you.
2. Filters those to the ones tagged with the current hour's target genre (artist objects still
   carry genre tags).
3. If that's thin for a genre you don't listen to much yet, widens the pool by searching for
   *tracks* tagged with the genre directly (`/v1/search?q=genre:"..."&type=track`), paginating
   via `offset` since the Feb 2026 migration also capped search `limit` at 10 (was 50).
4. Writes the result into one reusable private playlist (`TrueRadio Hourly Mix`, created via
   `POST /me/playlists`) and tells Spotify to play it via App Remote.

See `HourlyMixEngine.kt` and `SpotifyWebApiClient.kt` for the exact implementation, and revisit
them if Spotify's API surface changes again - it's shifted more than once already.

### Setup

1. In the same Spotify Developer Dashboard app from step 1, add a **second** Redirect URI:
   `trueradio://spotify-web-callback` (in addition to `trueradio://callback` from App Remote).
2. In the app, enter your Spotify Client ID (same one) and tap **Connect Spotify Account** under
   "Personalized hourly genre mix". This opens a system browser tab for Spotify's consent screen
   requesting: `user-top-read`, `playlist-modify-private`, `playlist-read-private`,
   `user-read-private`. PKCE (no client secret) is used throughout, per Spotify's current
   recommended flow for installed/mobile apps - see `SpotifyWebAuthManager.kt`.
3. Add genres to the **Genre rotation** list using Spotify's own artist-genre vocabulary (e.g.
   `pop`, `hip hop`, `rock`, `lo-fi`, `house`, `reggaeton`, `mizrahi`) - there's no fixed enum,
   since Spotify's genre tags are broad and shift over time; type whatever you want. Toggle
   **sequential** on to cycle through the list in order (hour → `genres[hour % size]`), or off
   for a still-per-hour-stable but shuffled pick.
4. Tap **Save Genre Rotation**, then **Connect & Start** as usual. Each hour, in the same
   0-3-minutes-past-the-hour window as the news flash, the DJ rebuilds the mix, optionally
   speaks a one-line genre-change intro, and switches Spotify to the new playlist.

If you don't connect the Web API, the app falls back to the original static per-daypart
playlists (`SpotifyManager.playForSegment`) with no hourly genre rotation.

## 7. Using the app

1. Launch the app, fill in all four fields (Spotify Client ID, Gemini key, ElevenLabs key,
   ElevenLabs voice ID), tap **Save Keys**.
2. Set your news categories/liked topics and tap **Save News Preferences**; add/remove/toggle
   RSS sources and tap **Save News Sources**.
3. Tap **Connect & Start**. Spotify will show its standard authorization prompt the first time.
4. The DJ starts a foreground service with a persistent notification (Play/Pause/Stop). Start
   playing anything in Spotify — the DJ will:
   - Speak a trivia/transition line in Hebrew when a track has ~15s left.
   - Speak a 3-sentence Hebrew news flash in the first few minutes of every hour, pulled from
     your enabled sources and leaning into your selected topics when a headline matches.
   - If you connected the Spotify Web API (step 6), switch to a freshly built, personalized
     genre mix at the top of every hour, with a short spoken genre-change intro.
   - Duck Spotify's volume automatically while speaking, and let it recover afterward.

## Known gaps to close before a production release

- **Spotify search-by-query fallback**: `SpotifyManager.playForSegment()` (used only if you
  haven't connected the Web API) plays a fixed playlist URI per daypart as a placeholder.
- **Genre tagging is approximate**: Spotify tags *artists* with genres, not individual tracks.
  `HourlyMixEngine` approximates a track's genre via its artist's tags, which is what most
  genre-aware tools built on the current Web API do post-`/recommendations`-deprecation, but it
  isn't as precise as the old audio-features-based approach was.
- **Market code is hardcoded**: `SpotifyWebApiClient.getArtistTopTracks()` defaults to
  `market=US`; pass the user's actual market (from `/v1/me`) for accurate top-tracks results
  outside the US.
- **Next-track lookup for trivia**: `runTrackTrivia()` passes `nextTitle = null` because App
  Remote doesn't expose "peek next track" directly; read it from `PlayerContext`/queue state via
  the Web API, or track your own playback history, and pass it into
  `GeminiClient.generateTrackTransition()`.
- **Key storage**: `SecureSettings` uses plain DataStore Preferences (unencrypted on disk),
  including the Spotify Web API refresh token. For a real release, wrap it with
  `androidx.security:security-crypto` or another Keystore-backed store.
- **Play/Pause notification action**: `togglePlayback()` always calls `play()`; cache the last
  `TrackInfo.isPaused` value in the service and branch on it for a correct toggle.
- **Gradle wrapper files** (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) aren't included here —
  generate them locally with `gradle wrapper` as shown above, or open directly in Android Studio.
