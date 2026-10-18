# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Fitness Tracker for Android — Project Guide

A native Android spin-off of the desktop/web Fitness Tracker at
`C:\Users\neera\Projects\FitnessTracker` (the "PC app"). Same idea: a personal,
local-first Strava replacement that imports your own workout files and computes real
training analytics. It runs **entirely on the phone**, with no server and no account.
The PC app's LLM chat is out of scope for now.

The PC app is the reference implementation. When this file and the PC code disagree
on *how something is calculated*, the PC code (`app/analytics/`, `app/importers/`) is
right. Read it before porting a feature. Its `CLAUDE.md` records the lessons learned.

## Status

**Phases 0, 1, 2 and 3 done** (see `docs/DESIGN-LOG.html`, which must be appended to at every phase). Phase 1: FIT, GPX and TCX import with parse-level parity to the PC (77 fixtures,
0 mismatches), Room database keyed by `file_hash`, the activity feed and detail screens (route, tiles, charts, laps),
import by file picker and share, delete with tombstones, and the theme system (Strava, iOS style, Terminal green, Amber,
Solarized, Gruvbox, Nord, Dracula). Phase 3: the whole 14-year history is on the phone (3,260 activities, about 208 MB)
and matches the PC database on every compared field; folder import as a WorkManager job that resumes after a kill,
same-run detection, rebuild from the original files, backup and restore. Phase 2: resampling, stream metrics,
training load (TSS / TRIMP / flat rate), CTL/ATL/TSB, best efforts and power/pace curves, estimated or user-set thresholds,
all matching the PC (0 mismatches on the 77 files and the whole history); a Training tab (fitness chart, curves, best efforts),
a thresholds editor in Settings and a load tile on the activity. Not ported from the PC's `stream_metrics` yet: efficiency and
the run profile (they belong to the efficiency and run-type phases). After phase 2 the user asked for the running-life poster and an app icon instead of the heatmap: the poster is the PC's own
`poster.html` bundled in `app/src/main/assets/poster/` and hosted in a WebView (`ui/poster/PosterScreen.kt`; the page's one
data request is answered from `Poster.data` in `:core`, every other request is refused, downloads are saved by the
`AndroidPoster` bridge into Pictures/Fitness Tracker). The analytics it needs are ported with parity tests: run profile (speed
histogram, interval reps), critical speed, HR zones, run types, route matching, and offline places (bundled GeoNames
cities1000, CC BY 4.0, `core/src/main/resources/places/`), so every layout and colour scheme works. Icon: adaptive lightning bolt on orange
(`res/drawable/ic_launcher_*`, with a monochrome layer for themed icons). Opened from the Training tab and from Settings > Extras. The Training tab has four parts: Fitness, Curves, Running
(critical speed and pace zones, race predictions and VO2max, the weekly run-type mix, efficiency) and Routes (repeated
routes with their runs and trends); the activity screen shows run type, efficiency, decoupling and time in pace and
heart-rate zones. `AnalyticsRepository` builds one cached snapshot of the running history for all of these and the
poster. A Log tab has totals by week, month and year, a month calendar, year against year, and goals (stored in the `goals`
table, keyed by a generated id, with `changes` entries); the Training tab has a Zones part (weekly heart-rate zones and the
polarized/pyramidal/moderate label). Elevation that does not come from the file: Strava's `activities.csv` (Settings action, or automatically when a
Strava export's top folder is imported) fills gaps by matching file names, and terrain lookup (opt-in, off by default, AWS
terrarium tiles cached on the phone, the PC's tuned 25 m / no smoothing / 2 m method) covers new files with GPS and no
altitude and, on request, existing ones. Both are kept in `elevation_overrides` / `terrain_elevation` (no foreign key: a
rebuild replaces activity rows) and re-applied after a rebuild, and travel in a backup (`elevation.json`). The file's own
altitude always wins. Next: phase 4 (heatmap), which the user does not need for now, or the other PC
analytics (load safety, volume and year in review, splits, cadence) and the phase 7 extras. Not applied yet: the seed's `pc-side-data.json` (Strava CSV and
terrain elevation, phase 7). Background: `docs/FEATURE-ANALYSIS.md`. Real-data fixtures and oracles made from the PC app
are in `seed/` (`golden/`, `pc-activities.json`; gitignored).

Code map: `:core` `Resample`, `TrainingLoad` (metrics, thresholds, load, daily series), `Curves` (bests, aggregation), `RunProfile`, `CriticalSpeed` (+ `Zones`), `RunTypes`, `TrainingLog` (totals, calendar, year over year, goals, weekly zones), `Efficiency`, `RunningReports` (predictions, critical-speed trend and zones, weekly mix, route progress), `RouteMatch`, `Places`, `Poster` (payload) and `Volume` (records, streaks); `:data` `AnalyticsRepository` (versioned metrics/bests/daily_load tables, settings; refreshed by a background job at start-up and after imports, deletes and threshold edits); `:core` `StravaCsv`, `Terrain` (profile, terrarium tiles), `Importer` (decode, hash, dispatch) with `FitImporter`/`GpxImporter`/`TcxImporter`, `ImportPlanner` +
`Similar` (duplicate / same run / import, pure and shared with the tests), `Common` (moving time and the other PC helpers),
`Routes`, `StreamCodec`, `Format`; `:data` `FitnessDatabase` (version 2, with a migration), `ActivityRepository`
(import, rebuild, derived data), `FolderImporter`, `BackupManager`, `ImportRunner` (progress state); `:app`
`work/BulkWorker.kt` (folder import, rebuild, restore as a foreground job), `ui/theme/Theme.kt` (tokens), `ui/components/`
(themed building blocks: use these, not raw Material, so a theme can change structure), `ui/activities/`, `ui/detail/`,
`ui/settings/`. Display preferences are in SharedPreferences (`ui/Prefs.kt`); the street map is off by default and the app
then makes no network requests. The system file picker cannot hand over thousands of files at once: use the folder import.
A repeat folder import only opens new or changed files (the `import_log` table); "Forget import history" resets that.

## Architecture (decided — don't re-litigate without reason)

- **Standalone now, syncable later.** The phone has its own database and does all
  processing. Syncing with the PC app over Tailscale is a planned *later* phase:
  don't build it until asked, but don't make choices that block it (see "Sync
  readiness").
- **Language/UI**: Kotlin, Jetpack Compose, Material 3, a single activity with
  Navigation Compose. No XML layouts, no Java.
- **Modules**:
  - `:core`: pure Kotlin/JVM, with no Android dependencies. Holds file parsers,
    analytics and formatting. This is where the ported logic lives, and it's unit
    tested on the JVM, which is fast.
  - `:data`: Room database, DAOs, repository, the raw-file archive, WorkManager jobs.
  - `:app`: Compose screens and ViewModels.
- **Database**: Room (SQLite). The schema mirrors the PC app's so that sync stays a
  mapping exercise, not a redesign.
- **Background work**: WorkManager for import and derived-data recompute. Never parse
  or recompute on the main thread.
- **Serialization**: kotlinx.serialization (stream JSON, test fixtures).

Don't swap in Flutter, React Native, KMP-with-iOS, a cloud backend or Firebase
without the user explicitly revisiting this.

## Target device

Google Pixel 7, minimum **Android 17** (confirm its API level in Android Studio; likely 37). With that floor there is
no need for core-library desugaring or legacy-storage workarounds; `java.time` is native. Personal use, sideloaded.

## UI direction

Strava-like (activity feed cards, bold stats, orange accent) with a **theme system**: a Strava theme, an iOS-style
theme, and Unix/terminal themes (terminal green, amber, Solarized, Gruvbox, Nord, Dracula), switchable in Settings >
Appearance. Themes are design tokens (colours, typography, shape, layout style), not just colour schemes, and every
chart and map reads them. Built in phase 1 so no screen is hard-coded to one look. Design and mock-ups: the "UI and
themes" section of `docs/DESIGN-LOG.html`.

## Environment notes (learned in phase 0)

- Local files: `local.properties` (`sdk.dir`, gitignored) and `seed/` (gitignored personal data).
- Test emulator: AVD `Pixel7_Spike` (3 GB RAM, Android 17, API 37). The stock 2 GB device is too small for Compose plus
  MapLibre. Judge speed on the real Pixel 7, not the emulator.
- The PC's disk was nearly full (about 4 GB free). The emulator refuses to start below its disk requirement.
- The real Pixel 7 (API 37) is the main test device since phase 1; `adb` is on the user PATH. Save screenshots with
  `adb shell screencap` and `adb pull`, never with a PowerShell `>` redirect.
- In Git Bash set `MSYS_NO_PATHCONV=1` for adb commands with `/sdcard/...` paths, and give Windows `adb` Windows paths
  (it cannot see Git Bash's `/tmp`). Files pushed with `adb push` show in the system picker under the "Pixel 7"
  storage root, not under "Downloads" (that root only lists indexed media).
- If a Gradle run dies in the Kotlin incremental cache (`dirty-sources.txt`), delete `core/build/kotlin` and rerun.
- Tests that must return void: write `fun x() = runBlocking<Unit> { ... }`.
- Instrumented tests use JUnit 4's `@Test`; JVM tests in `:core` use `kotlin.test`.
- The tests run in the `America/New_York` zone (set in `core/build.gradle.kts`) to match the golden fixtures.
- FIT enum names (sport, manufacturer) come from `core/src/main/resources/fit/fitparse_enums.json`, generated on the PC
  by `tools/export_fit_enums.py`, so stored strings equal the PC's.

## Key libraries

| Need | Library | Notes |
|---|---|---|
| FIT parsing | Garmin FIT SDK for Java (`com.garmin:fit`, Maven Central) | Check the latest version when adding it |
| GPX parsing | `XmlPullParser` | Built into Android; on the JVM (`:core` tests) the same parser comes from `kxml2` |
| Charts | Vico (Compose) | HR/pace/elevation stacks, fitness chart, curves |
| Maps | MapLibre Native for Android, OSM raster tiles | Needs to draw hundreds of polylines for the heatmap. Follow the OSM tile usage policy: set a real User-Agent and don't prefetch |
| DB | Room + KSP | |
| Background | WorkManager | |

Keep the dependency list short. Add a library only when the platform can't do the job
reasonably.

## Data model (mirrors the PC app)

- `activities`: **primary key `file_hash`** (sha256 of the *decompressed* file, the
  same value the PC app stores). Also sport, start_time (UTC ISO 8601), duration
  (**moving time** in seconds, as the PC app defines it: see docs/FEATURE-ANALYSIS.md 4.3), distance (m), elevation_gain (m), device, source_file, imported_at.
- `streams`: per activity, columns aligned to `time` (seconds from start): heart_rate,
  power, speed, cadence, altitude, distance, lat, lon. Keys with no data are omitted.
  **Stored as a gzipped JSON file `filesDir/streams/<file_hash>.json.gz`, not as a database
  row**: Android reads rows through a ~2 MB window and a plain blob fails from about 2.1 MB
  (measured in phase 0).
- `laps`: per-activity splits.
- `goals`: sport?, metric (distance/duration/activities/elevation), period (week/month/year), target.
- `elevation_overrides` / `terrain_elevation`: elevation that did not come from the file (Strava CSV, terrain lookup); re-applied after a rebuild.
- `settings`: ftp, max_hr, resting_hr, threshold_hr, each with `updated_at`. Unset
  keys are estimated from the data.
- Derived, versioned, and rebuilt when missing or outdated, never updated
  incrementally: `activity_metrics`, `activity_bests`, `routes`, `personal_records`,
  `daily_load`.
- `changes` (for future sync): an append-only log of `(seq, entity, key, op, changed_at)`
  for activity imports, deletes and settings edits. Deletes leave a tombstone so a
  future sync can't bring them back.

Raw imported files are kept in app-private storage (`filesDir/imports/`), so the
database can always be rebuilt from them. That's the same rule as the PC app, with
the same "reparse everything" escape hatch.

## Sync readiness (build none of the sync itself yet)

- Identity is `file_hash`, never a local auto-increment id, in every table that could
  sync.
- Only **raw files, settings and tombstones** would ever sync. Derived tables are
  always recomputed locally, so the two sides can't disagree about them.
- Store timestamps in UTC; convert to local time only for display and day-bucketing.

## Features, in priority order

1. **Import**: pick files with the system file picker (`ACTION_OPEN_DOCUMENT`,
   multiple files), or share from another app (`ACTION_SEND` / `SEND_MULTIPLE`).
   Supports `.fit`, `.gpx`, `.tcx` and their `.gz` versions, with dedupe by file hash and same-run-from-two-apps detection (PC: `importers/similar.py`).
2. **Activity list and detail**: summary tiles, HR/pace/elevation charts, a route
   map, laps.
3. **Training load**: per-activity load score and CTL/ATL/TSB, plus a thresholds
   settings screen.
4. **Power/pace curves and best efforts.**
5. **Heatmap.**
6. **Everything else the PC app has**, phased in `docs/FEATURE-ANALYSIS.md` section 12: HR zones, training log and
   goals, splits, cadence, efficiency, load safety, critical speed, run types, predictions, volume, route matching.
7. **Optional extras, wanted by the user**: Strava CSV elevation fill, terrain elevation, offline places (cities),
   and the running-life poster (WebView host first).
8. *(Later)* Sync with the PC app over Tailscale; Health Connect import.

Test each phase against the user's real files before starting the next (see "Test
data").

## Porting the analytics: rules that must match the PC app

These came from real bugs. Keep the constants identical unless both apps change
together.

- **All-zero sensor streams are "no sensor".** The user's watch (Amazfit Bip 6)
  writes 0 W power without a power sensor. Drop HR/power/cadence/speed streams that
  are all zero or missing (PC: `importers/common.py:keep_streams`).
- **Device name:** skip a numeric manufacturer id when a product name exists
  ("Amazfit Bip 6", not "339 Amazfit Bip 6").
- **Resampling** (PC: `analytics/streams.py:resample_1hz`): resample to 1 Hz
  moving time and drop pauses, i.e. gaps longer than 5 s. Forward-fill short gaps, but
  **linearly interpolate cumulative distance**, because "smart recording" devices
  sample every few seconds.
- **Training load** (PC: `analytics/training_load.py`):
  - Cycling with power and an FTP uses TSS from normalized power (30 s rolling
    average, 4th-power mean).
  - Otherwise, activities with HR use Banister TRIMP, weight 1.92, scaled so one hour
    at threshold HR = 100.
  - With neither, use 50 per hour.
  - Estimates for unset thresholds: FTP = 0.95 × best 20-min power; max HR = the
    99th-percentile HR; threshold HR = resting + 0.85 × HR reserve; resting HR
    defaults to 60.
- **CTL/ATL:** 42/7-day exponential averages. TSB = the *previous* day's CTL − ATL.
  Days are bucketed in the phone's **local** timezone.
- **Curves** (PC: `analytics/power_curve.py`): use the same duration list, the same
  standard distances, and no speed records under 5 s. Zero out per-second distance
  jumps above 12 m/s for run/walk/hike and 30 m/s for everything else (GPS glitches).
- **Routes** (PC: `analytics/heatmap.py`): split tracks at gaps over 250 m, simplify
  with RDP at 5 m tolerance, store as Google encoded polylines.
- **GPX:** Strava writes sport types as numbers (`9` = running, `1` = cycling), and
  there are no laps. Derive speed with a centred difference over ±2 samples, and
  moving time where speed ≥ 0.3 m/s and gaps ≤ 30 s.

**Parity tests (required for every ported metric):** use the PC app to generate
golden JSON (parsed summary, load, bests, simplified route) for a set of real and
synthetic files. Check those fixtures into `core/src/test/resources/golden/`, and
assert that the Kotlin results match within a small tolerance. The PC repo's
`tests/fit_builder.py` can generate synthetic FIT files for edge cases.

## History seed

`seed/` (gitignored, personal data) holds a copy of the PC app's 14-year history: `imports/` (3,265 raw
`.fit.gz`/`.gpx`/`.tcx` files), Strava's `activities.csv`, and `pc-side-data.json` (elevation overrides and terrain
elevation, keyed by `file_hash`). Plan: copy `seed/` to the phone and bulk-import it through the normal import path;
that also serves as the end-to-end parity test. Push with
`adb push seed/imports /sdcard/Download/fitness-seed/imports` (and the other two files) once a phone is connected.

## Test data

The user's real exports, archived by the PC app, are in
`C:\Users\neera\Projects\FitnessTracker\data\imports\`: Amazfit Bip 6 FIT files,
plus one FIT file recorded through Strava. These are personal data: **copy selected
files into test resources only with the user's OK**, and never commit them to a
public remote.

## Conventions

- Local-first and single-user: no accounts, analytics, crash reporting or cloud
  services. The only network permission is for map tiles.
- Compose screens take state from a ViewModel. Business logic lives in `:core`/`:data`,
  never in composables.
- Units are formatted in `:core` (`Formatter`) and chosen in Settings; the default is **imperial** (the user's choice,
  2026-10-18): pace "m:ss /mi" for run/walk/hike and mph for everything else. Metric gives "m:ss /km" and km/h, as the
  PC app does. Storage and every calculation stay metric.
- Recompute derived data after imports, deletes and settings changes, as one
  WorkManager job (PC: `recompute_all`).
- Android Studio and the Android SDK run on Windows here. Use `gradlew.bat`, or
  `./gradlew` in Git Bash.

## Commands

```bash
./gradlew assembleDebug                                    # build the debug APK
./gradlew installDebug                                     # install on a connected phone/emulator
./gradlew test                                             # all JVM unit tests (fast: :core)
./gradlew :core:test --tests "*TrainingLoadTest*"          # one test class
./gradlew :core:test --tests "*TrainingLoadTest.ctlDecay*" # one test
./gradlew connectedAndroidTest                             # Room/UI tests on a device
./gradlew lint
```

Real test classes so far: `:core` `ImportParityTest` (all 77 FIT/GPX/TCX fixtures vs the PC: summary, streams, laps,
routes), `ImportCorpusTest` (every file in `seed/imports` through the pipeline; a measurement that prints counts and
failures), `FullHistoryParityTest` (replays the whole archive in the PC's order and compares every activity with
`seed/pc-activities.json`, the PC database's activities table; needs that file), `AnalyticsParityTest` (single files with fixed thresholds, then the whole archive vs `full_history`: settings, loads, daily series, curves), `PolylineTest`, `UnitsTest` (formatting,
sports, stream codec, chart series, same-run rules); `:data` androidTest `ImportFlowTest` (import, duplicate, delete,
failure), `BulkFlowTest` (same-run, rebuild, derived data, backup round trip, migration) and `BlobLimitTest`
(`:data:connectedDebugAndroidTest`, needs the phone or emulator). Regenerate the fixtures on the PC with
`venv\Scripts\python.exe -m tools.export_golden --out <FitnessTrackerAndroid>\seed\golden --full`, and the activities
oracle by reading the PC's `data/fitness.db` read-only (`activities`, `laps` counts, `elevation_overrides`) into
`seed/pc-activities.json`. To compare the phone with the PC after an import: pull `databases/fitness.db*` with
`adb exec-out run-as app.fitness.android cat ...` and diff against that file.
