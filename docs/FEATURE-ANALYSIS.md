# Fitness Tracker → Android: Feature Analysis and Porting Plan

Status: **complete first draft** (written incrementally, section by section, from the PC source). See section 13.3 for what was not verified.
Source of truth analysed: `C:\Users\neera\Projects\FitnessTracker` (the "PC app"), read on 2026-10-17.
Target: a native, standalone, personal-use Android app (sideloaded APK, no Play Store).
Existing decisions (from `FitnessTrackerAndroid/CLAUDE.md`): Kotlin + Compose + Material 3, modules `:core` / `:data` / `:app`,
Room, WorkManager, Vico charts, MapLibre maps, Garmin FIT SDK, no LLM chat, sync with PC deferred.

## Contents

1. Scope and method
2. Feature inventory (what the PC app does)
3. Data model and storage
4. Import pipeline
5. Analytics catalogue
6. API surface → Android repository/use-case mapping
7. UI pages → Compose screens
8. Features that do not port directly 
9. Library mapping (Python → Kotlin)
10. Performance, storage and background-work considerations
11. Parity-testing strategy
12. Proposed phased roadmap
13. Risks and open questions

<!-- S1 -->
## 1. Scope and method

**Question answered:** what does the current Python Fitness Tracker do, and how can each part become a native Android app for personal use (sideloaded, no Play Store)?

**Method**
1. Read the PC repo's project guide, schema (`app/db.py`), API (`app/main.py`), every importer, every analytics module, the chat tools, the desktop launcher and the front-end pages.
2. Measured the real database (3,260 activities) for size and shape (section 10.1).
3. Compared the result with the existing `FitnessTrackerAndroid/CLAUDE.md` (architecture and feature list written 2026-09-30) and recorded where the PC app has moved past it (3.4, 2).
4. Checked three time-sensitive external facts on the web (FIT SDK distribution, Vico axis support, Android sideloading policy); sources are cited where used.

**Headline findings**
1. The PC app is two things: a **data pipeline** (import, 1 Hz resampling, per-activity summaries, daily load) and a **large set of cheap query-time analytics** over those summaries. Both are pure computation, which ports cleanly to a Kotlin `:core` module. The web UI, server and chat are the parts that do not port.
2. The existing Android guide covers about half the PC's current functionality. **Missing from it:** TCX, same-run-from-two-apps detection, goals, HR zones, load safety, the whole training log, efficiency, critical speed, run types, predictions, volume/year-in-review, route matching, and the poster (section 2).
3. One **semantic drift** matters immediately: the PC's `duration` is *moving time*, not timer time (3.4, 4.3).
4. The hard part of the port is **numerical fidelity**, not UI: Python `round()`, pandas gap-fill semantics and window edges, and local-date bucketing (5.4). A golden-fixture workflow generated from the PC app is the control (11).
5. Android-specific traps the PC never faced: the ~2 MB SQLite row window vs. stream blobs, a 10+ minute first import under foreground-service rules, no backup of a 14-year history, and signing/sideloading policy (10.2).
6. Storage fits comfortably: about 200 MB on the phone vs. 434 MB on the PC (10.1).

**Out of scope here:** writing Android code, choosing UI visual design, and the later PC/phone sync design (the Android guide already defers it).

**Terminology:** *PC app* = `C:\Users\neera\Projects\FitnessTracker`. *Layer 1/2* = stored per-activity summaries vs. query-time analytics (section 5). *Golden fixtures* = JSON produced by the PC code, used as expected values in Kotlin tests.

<!-- S2 -->
## 2. Feature inventory (what the PC app does)

Everything below was read from the code, not from the README. "In Android guide" says whether the existing `FitnessTrackerAndroid/CLAUDE.md` feature list (§"Features, in priority order") already covers it.

**Port class:** **Direct** = pure logic, port line by line to `:core`. **Adapt** = same behaviour, different platform mechanism. **Replace** = the PC mechanism does not exist on Android. **Optional / Drop** = see section 8.

| # | Feature | What the PC app does | In Android guide | Port class | Effort |
|---|---|---|---|---|---|
| **Import and data management** | | | | | |
| F1 | FIT import | Parses `record`/`session`/`lap`/`file_id` messages into streams, laps, summary | Yes | Adapt (Garmin FIT SDK instead of `fitparse`) | M |
| F2 | GPX import | Derives distance, speed, moving time; reads HR/power/cadence extensions | Yes | Adapt (`XmlPullParser`) | M |
| F3 | TCX import | Track-derived distance and moving time; namespace-agnostic | "Later" | Adapt (`XmlPullParser`) | M |
| F4 | `.gz` support, dedupe by sha256 of decompressed bytes | `x.fit` and `x.fit.gz` are one activity | Yes | Direct (`GZIPInputStream`, `MessageDigest`) | S |
| F5 | Same-run-from-two-apps detection (`similar.py`) | moment / zone / (opt-in) day rules | **No** | Direct | S |
| F6 | Sport normalisation and inference for untyped files | Strava numeric codes, keywords, `FOOT_SPEED` 1.9 m/s | Partly (numeric codes) | Direct | S |
| F7 | Bulk folder import | Recursive, never stops on one bad file, failure log, one recompute at the end, dry run | Partly (multi-file picker) | Adapt (`OPEN_DOCUMENT_TREE` + WorkManager) | M |
| F8 | Reparse / rebuild from archived raw files | Keeps ids and settings; reapplies overrides | Yes (escape hatch) | Adapt | S |
| F9 | Raw file archive | `data/imports/<hash12>_<name>`; kept when an activity is deleted | Yes | Adapt (`filesDir/imports/`) | S |
| F10 | Strava `activities.csv` elevation fill | Match by `Filename`; metres; only fills gaps; survives reparse | No | Optional | S |
| F11 | Terrain elevation lookup for files with GPS but no altitude | Opt-in; terrarium tiles / Open-Meteo / local SRTM; tuned to 3.8% median error | No | Optional (needs network, or bundled DEM) | M |
| F12 | Delete one / many (typed `DELETE` confirmation for bulk) | One transaction, one recompute | Not stated | Adapt | S |
| **Activities** | | | | | |
| F13 | Activity list | Filter by local-date range and sport; defaults to the current month; checkbox multi-select with shift-range | Yes (list) | Adapt (Compose `LazyColumn`, long-press multi-select) | M |
| F14 | Activity detail: summary tiles | Distance, moving time, pace/speed, elevation (asterisk when looked up), load with method, device | Yes | Adapt | S |
| F15 | Activity detail: stacked charts | Panels for HR, pace/speed, elevation (filled), cadence (spm-corrected), power; unified hover; hover moves a marker on the map | Yes (HR/pace/elevation) | Adapt (Vico) | M |
| F16 | Activity detail: route map | Polyline fit to bounds, base-map switcher | Yes | Adapt (MapLibre) | M |
| F17 | Laps table, auto splits table (km or mi, fastest/slowest, negative-split verdict) | Computed on request | Laps only | Direct + Adapt | M |
| F18 | HR zone bar and table for the activity | From `hr_hist` and max HR | No | Direct | S |
| F19 | Pace zones, run type with the reason in a sentence, interval rep table | From critical speed as of that date | No | Direct | M |
| **Fitness** | | | | | |
| F20 | Fitness / fatigue / form chart | CTL, ATL, TSB over selectable ranges, daily load as bars | Yes | Direct logic, Adapt chart | M |
| F21 | Load safety card | ACWR, ramp rate, monotony, strain, rest days, warnings, 180-day chart | **No** | Direct | S |
| F22 | Weekly time in HR zones + polarization label | Stacked bars, easy/moderate/hard split | No | Direct | S |
| F23 | Aerobic efficiency and decoupling trend | One basis per sport, % per month trend | No | Direct | S |
| F24 | Thresholds (FTP, max/resting/threshold HR) | Entered or estimated, source shown; save recomputes load | Yes | Adapt (settings screen) | S |
| **Training log** | | | | | |
| F25 | Totals per week / month / year, by sport and metric | With `elevation_missing` warnings | **No** | Direct | M |
| F26 | Month calendar | Per-day activities | No | Adapt (custom Compose grid) | M |
| F27 | Year over year | Cumulative per month, current year truncated | No | Direct | S |
| F28 | Goals | Sport, metric, week/month/year, target, on-track flag | No | Direct (+ new `goals` table) | S |
| **Running** | | | | | |
| F29 | Year in review | Totals, change vs previous year, biggest week/month, favourite weekday, PBs set | **No** | Direct | M |
| F30 | Weekly distance, consistency | Rolling average, streaks, longest gap, runs per week | No | Direct | S |
| F31 | Run-type mix per week | easy/steady/tempo/long/intervals | No | Direct | M |
| F32 | Personal-best timeline | Per standard distance, per year | No | Direct | S |
| F33 | Routes you repeat | Grouping, per-route pace/HR trend, map | No | Direct, vectorise for speed | M |
| **Curves** | | | | | |
| F34 | Power curve and pace curve (log time axis), date-range presets | Best effort per duration with the activity that set it | Yes | Direct logic, Adapt chart | M |
| F35 | Best-efforts / PR table | Standard distances | Yes | Direct | S |
| F36 | Race predictions and VDOT | Riegel 1.06 + Daniels | No | Direct | S |
| F37 | Critical speed and pace zones, monthly CS trend | 2-parameter fit | No | Direct | M |
| **Maps** | | | | | |
| F38 | Heatmap | All routes, filter by sport and dates, selectable line | Yes | Adapt (MapLibre line layers) | M |
| F39 | Base-map switcher (7 options incl. "None") | Remembered; **no tile requests in None mode** | OSM only | Adapt | S |
| **Extras** | | | | | |
| F40 | Running-life poster | 11 layout modes, 7 colour schemes, SVG/PNG export, optional city names | No | Optional (see section 8) | L |
| F41 | Places (cities run in), offline | GeoNames gazetteer | No | Optional | L |
| F42 | Units toggle metric / imperial | Display only; storage is always metric | Metric only | Adapt (`SharedPreferences`/DataStore) | S |
| **Not portable as-is** | | | | | |
| F43 | Ollama chat panel with 7 tools | Local LLM calling analytics functions | Out of scope | Drop / Later | L |
| F44 | Desktop shell (`pywebview`), uvicorn server | Native window around the web UI | n/a | Replace (native app) | n/a |
| F45 | PWA + Tailscale phone access | Phone reaches the PC | n/a | Replace (this project *is* the phone app) | n/a |

**Feature rows missing from the Android guide's priority list (the main finding):** F5, F10, F11, F18, F19, F21–F23, F25–F33, F36, F37, F40, F41. The Android guide covers roughly the PC app as it was after "step 12"; the two roadmaps added about half of today's functionality.

<!-- S3 -->
## 3. Data model and storage

Source: `app/db.py` (`SCHEMA`), `app/models.py`. The PC app uses plain `sqlite3`; every table below maps to a Room entity.

### 3.1 Source tables (user data; must never be silently dropped)

| PC table | Columns | Android treatment |
|---|---|---|
| `activities` | id (autoincrement), sport, start_time (UTC ISO), duration, distance, elevation_gain, source_file, **file_hash UNIQUE**, device, imported_at | Room entity. Android `CLAUDE.md` already decides **PK = `file_hash`** (sync-ready). Keep an internal `Long` rowid only if Room relations need it; never expose it. |
| `streams` | activity_id PK, `data` = one JSON blob of columns aligned to `time` | Keep as one JSON/BLOB per activity. Consider gzip-compressing the blob (see §10). |
| `laps` | lap_index, start_time, duration, distance, avg/max HR, avg_power, avg_speed, elevation_gain | Plain entity, FK cascade. |
| `settings` | key → REAL (`ftp`, `max_hr`, `resting_hr`, `threshold_hr`) | Key/value entity, plus `updated_at` (sync). |
| `goals` | sport?, metric (distance/duration/activities/elevation), period (week/month/year), target > 0 | Entity. **Missing from the Android `CLAUDE.md` data model**; add it. |
| `elevation_overrides` | activity_id → gain, source `strava_csv` | Only needed if Strava CSV import is ported (§8). |
| `terrain_elevation` | provider, gain, altitude JSON | Only needed if terrain lookup is ported (§8). |

### 3.2 Derived tables (rebuilt, never migrated)

| PC table | Contents | Version const |
|---|---|---|
| `activity_metrics` | normalized_power, power_seconds, best_20min_power, hr_max (p99), hr_hist JSON, efficiency, efficiency_basis, decoupling, speed_hist JSON, reps JSON, load, load_method | `METRICS_VERSION` (currently 3) |
| `activity_bests` | JSON `{power:{secs:W}, speed:{secs:m/s}, distance_time:{metres:secs}}` | `BESTS_VERSION` |
| `routes` | JSON list of Google encoded polylines, one per continuous GPS segment | `ROUTES_VERSION` |
| `activity_places` | `[name, region, cc, lat, lon]` per route | `PLACES_VERSION` |
| `personal_records` | best per sport/metric/span with activity_id, achieved_at | recomputed wholesale |
| `daily_load` | date, load, ctl, atl, tsb | recomputed wholesale |

Lifecycle rules to copy exactly (`app/db.py`, `app/analytics/__init__.py`):
- Per-activity derived tables carry a `version` column and are filled only where the row is missing or outdated (`PER_ACTIVITY_TABLES`).
- `init_db` drops any derived table whose columns changed. **Room cannot do this automatically**: a migration or destructive-migration policy is needed *for derived tables only*, while source tables get real migrations. Recommended: put derived tables in a separate Room database file (`derived.db`) that is simply deleted and rebuilt on any schema bump, so source data can never be lost by a derived-schema change.
- Everything is refreshed by one entry point, `recompute_all`, after startup, imports, deletes and settings changes.

### 3.3 Stream format

Keys: `time, heart_rate, power, speed, cadence, altitude, distance, lat, lon`. All lists are aligned to `time` (seconds from start), `null` where the device recorded nothing; keys with no data are omitted (`common.keep_streams`: sensor streams `heart_rate/power/cadence/speed` that are all zero are also dropped, because Amazfit writes 0 W without a power meter).

### 3.4 Drift between the Android `CLAUDE.md` and the PC app

The Android guide was written 2026-09-30; the PC app has moved on. Items to correct before coding:
1. `activities.duration` is **moving time**, not timer time (PC `CLAUDE.md` "Duration means moving time"; `common.moving_time`, `fit_importer._duration`). Android `CLAUDE.md` says "duration (timer seconds)".
2. `goals` table is missing.
3. TCX is listed as "later"; the PC app already imports TCX and its rules were validated against 422 real Strava TCX files, so a Strava export will contain TCX that the Android app would otherwise reject.
4. The roadmap analytics (zones, log, splits, cadence, efficiency, predictions, critical speed, run types, load safety, volume, route matching, poster, places) are not in the Android feature list at all. See §5.
5. Duplicate detection of the *same run from two apps* (`importers/similar.py`) is not mentioned.
6. `streams.align_1hz` and `resample_1hz` semantics must be checked against the Android guide's "gaps longer than 5 s" wording (see §5).

<!-- S4 -->
## 4. Import pipeline

Source: `app/importers/*`, `app/import_dir.py`, `app/reparse.py`, `app/models.py`.

### 4.1 Pipeline (format independent: `importers/__init__.py`)

1. Pick the parser by extension (`.fit`, `.gpx`, `.tcx`); `.gz` is decompressed first. Anything else is `ImportFailed`.
2. `file_hash = sha256(decompressed bytes)`; if present in `activities`, the result is `duplicate` (reason `identical`). `x.fit` and `x.fit.gz` therefore count as one activity.
3. Parse to a `ParsedActivity` (sport, start_time UTC, duration, distance, elevation_gain, device, streams, laps).
4. **Similar-activity check** (`similar.py`): skip a file when a stored activity has the same sport, distance within 10%, and starts within 2 min (`moment`), or a whole number of hours away (≤14 h, ±60 s) with distance within 5% (`zone`, one source labelled local time as UTC). `day` (same local date) is opt-in only, because it wrongly dropped real separate runs. Files with sport `unknown` match any sport.
5. Optional sport labelling for untyped sources (`--sport auto`): running if average speed ≥ 1.9 m/s, else walking.
6. Optional terrain elevation lookup (opt-in; see §8).
7. Archive the original bytes (`<hash12>_<safe name>`) and insert activity, streams, laps in one transaction.
8. After a batch, run `recompute_all` **once**, not per file.

Per-file failure never aborts a batch. Results per file: `imported | duplicate | error`, with `reason`, `similar`, `sport`.

### 4.2 Format parsers

| Format | Library on PC | Key rules to replicate |
|---|---|---|
| FIT | `fitparse` | `record` messages → streams; lat/lon semicircles × 180/2³¹; speed = `enhanced_speed` else `speed`; altitude = `enhanced_altitude` else `altitude`; sport from `session.sport`; start from `session.start_time`; elevation gain = `session.total_ascent` else computed from altitude (2 m hysteresis); laps from `lap` messages; device name drops a numeric manufacturer id when a product name exists. |
| GPX | `gpxpy` | Needs timestamped points; sort by time; derives distance (haversine), speed (centred difference ±2 samples) and moving time itself; HR/power/cadence from namespaced extensions flattened by lower-case local tag (`hr`, `power`, `cad`); `<number>` tags are stripped (Strava puts a UUID there); sport from `<type>` (Strava numeric codes); no laps. Fails on files with no timestamped points. |
| TCX | `xml.etree` | First `<Activity>` only; tags matched by *local name* (namespace independent); per-point distance/speed win, else derived from GPS; cadence falls back to `RunCadence`; duplicate-timestamp points at lap boundaries are dropped; **track-derived distance and moving time beat lap totals** (laps were up to 16% off Strava); leading whitespace before `<?xml` is tolerated; courses/workouts rejected. |

All three produce the same normalised `ParsedActivity`; the shared helpers (`common.py`) are the real porting unit:
`normalize_sport`, `infer_foot_sport`, `keep_streams`, `elevation_gain` (2 m hysteresis), `path_distance` (haversine, R = 6,371,000 m), `centred_speed`, `ffill`, `moving_time`.

### 4.3 "Duration means moving time" (highest-risk rule)

`common.moving_time(t, dist)`: speed smoothed over ±3 samples; a sample interval counts as moving if smoothed speed ≥ 0.3 m/s; gaps > 30 s are pauses and the smoothing never crosses one. FIT uses the watch timer unless a GPS track with distance exists *and* the track-derived time is at least 1% shorter (`TIMER_TOLERANCE`). GPX and TCX always use the derived value. Indoor/pool activities keep the timer (track method gave -94% for pool swims).
Validated on the PC against Strava's Moving Time over 3,039 activities: median +0.01%, 93% within 10%. The Android port must reproduce this exactly, because it feeds average pace, Log totals, load (duration fallback) and splits.

### 4.4 FIT on Android: the main porting difference

`fitparse` is a lenient pure-Python reader; the Garmin FIT SDK (Java) is strict and decodes through a listener API. Things to verify against the user's real Amazfit Bip 6 files, not assume:
- field naming/units (`enhanced_speed` vs `speed`, semicircles, scale/offset already applied by the SDK);
- timestamps: SDK gives FIT-epoch seconds (1989-12-31); `fitparse` gives datetimes. Convert to UTC instants explicitly;
- manufacturer/product name resolution (`file_id`): SDK exposes `Manufacturer.getStringFromValue`, and unknown ids (Amazfit/Zepp = 339) come out as numbers: keep the product-name rule;
- CRC/ chained-file handling: the SDK throws on a bad CRC where `fitparse` often reads on. Decide whether to fall back to ignoring CRC (`Decode` option) so previously importable files still import.
- Memory: decode streaming; do not hold both the raw bytes and a full message list for multi-hour files.

### 4.5 Bulk import and rebuild

- `import_dir`: recursive walk, skips non-activity files, ignores `routes/` and `media/` siblings of a Strava `activities/` folder, never stops on a bad file, writes a failure log, recomputes once, safe to re-run and to interrupt. `--dry-run` runs the same `plan_import` without writing. Android equivalent: a WorkManager job over a `DocumentFile` tree chosen with `ACTION_OPEN_DOCUMENT_TREE`, with a notification showing progress, and the same dry-run summary (imported / duplicates / failed / similar skipped).
- `reparse`: rebuild every activity from its archived raw file after a parser change, keeping ids/settings, deleting per-activity derived rows, then re-applying `elevation_overrides` and `terrain_elevation`. Android needs this too (a "Rebuild from archived files" action in Settings); with `file_hash` as the key it also gives a clean path for parser version bumps.
- Throughput reference: 3,045 real files imported + analytics in ~12 min on the PC, dominated by `fitparse` (≈0.6 s per hour of 1 Hz data). The Garmin SDK is faster than `fitparse`, but a phone is slower than a PC, so a first bulk import must be a foreground-service/WorkManager job that survives the screen turning off.

### 4.6 Android import entry points (from the Android `CLAUDE.md`, confirmed feasible)

- `ACTION_OPEN_DOCUMENT` (multiple) and `ACTION_OPEN_DOCUMENT_TREE` for folders;
- `ACTION_SEND` / `SEND_MULTIPLE` intent filters for sharing from other apps (mime types `application/octet-stream`, `application/gpx+xml`, `application/vnd.garmin.tcx+xml`, `*/*` fallback by extension);
- content URIs have no reliable file name or extension; read `OpenableColumns.DISPLAY_NAME` and fall back to sniffing the content (FIT header `.FIT` at bytes 8-11, `<gpx`, `<TrainingCenterDatabase`, gzip magic `1f 8b`).

<!-- S5 -->
## 5. Analytics catalogue

Source: `app/analytics/*.py`. Every module follows one of two patterns, which is also how it should be ported:

- **Layer 1, per-activity, stored, versioned** (expensive, reads streams): `training_load.stream_metrics`, `power_curve.activity_best_efforts`, `heatmap.route_segments`, `places`. Computed once in a background job and skipped while the stored `version` is current.
- **Layer 2, query-time, cheap** (reads only Layer-1 rows): curves, zones, log, critical speed, run types, load safety, volume, route matching, predictions. Computed on demand in a repository/use-case; never stored.

Effort scale: S = a day or less, M = a few days, L = a week or more (single developer, including parity tests).

### 5.1 Layer 1: per-activity summaries

| Module | What it produces | Key constants and rules | Effort |
|---|---|---|---|
| `streams.py` | `resample_1hz` / `align_1hz`: 1 Hz moving-time series. Index i is roughly i seconds of *moving* time. | Round `time` to int; drop duplicate seconds; reindex to every second; `ffill(limit=5)` (or linear interpolation with `limit=5, limit_area="inside"` for cumulative distance); then **drop** whatever is still missing, which removes pauses. `align_1hz` also keeps a row only if every column has a value. | S, but every other module depends on it being exact |
| `training_load.stream_metrics` (`METRICS_VERSION` 3) | normalized_power, power_seconds, best_20min_power, hr_max (p99), hr_hist, plus efficiency and run-profile fields | NP = (mean of (30 s rolling mean)^4)^(1/4) over the 1 Hz power series, needs 30 s or more; best 20-min = max 1200 s rolling mean, needs 1200 s or more; `hr_hist` = {round(bpm): seconds}, HR <= 0 ignored, a sample interval counts only if 0 < dt <= **10 s**; `hr_max` = 99th percentile (linear interpolation) of positive HR samples. | M |
| `efficiency.py` | efficiency factor (EF) and aerobic decoupling | `align_1hz(hr, speed or power)`; keep rows with HR > 0 and output > 0.5 m/s (or > 0 W); needs at least **1200** rows; EF = mean(out) x scale / mean(hr), scale 60 for m/min; decoupling = (EF1 - EF2) / EF1 x 100 for first vs second half. Power basis only for cycling/e_biking with power. | S |
| `run_profile.py` | `speed_hist` ({tenths of m/s: seconds}, moving >= 0.3 m/s) and candidate interval `reps` (running only) | Histogram bucket = `round(v/0.1)`. Reps: need 600 s or more; 5 s centred rolling mean; contrast (p80 - p20) / median >= 0.30; "fast" = at or above (p20 + p80) / 2; merge dips under 10 s; keep stretches of 20 s or more; need at least 3; store at most 60 with recovery length and speed. | M |
| `power_curve.activity_best_efforts` (`BESTS_VERSION` 1) | power curve (27 durations from 1 s to 6 h), speed curve (durations >= 5 s), fastest time over standard distances | Cumulative-sum window maximum. GPS glitch filter: a per-second distance step < 0 or > 12 m/s (run/walk/hike) or > 30 m/s (others) is zeroed. Distance fallback: integrate speed if there is no distance stream. Distances: run/walk/hike `[400, 1000, 1609.34, 5000, 10000, 16093.4, 21097.5, 42195]`; cycling `[5, 10, 20, 40, 50, 100, 160.934 km]`. Best time over a distance uses a `searchsorted` sweep on the monotonic distance array. | M |
| `heatmap.route_segments` (`ROUTES_VERSION` 1) | list of Google encoded polylines per activity | Drop null positions; project with a local equirectangular projection (lat0 = mean latitude, R = 6,371,000 m); split where consecutive points are > **250 m** apart; per segment (2+ points) Ramer-Douglas-Peucker at **5 m** (iterative stack form); encode at precision 1e5. | M |
| `places.py` | gazetteer places along each route | Offline reverse geocoding; see section 8 (not a straight port). | L, optional |

### 5.2 Layer 2: settings-dependent scoring

**Training load** (`training_load.py`: `activity_load`, `recompute`, `effective_settings`).

| Item | Rule |
|---|---|
| Method order | 1) cycling/e_biking with NP and FTP: `power_seconds * (NP/FTP)^2 / 3600 * 100` (TSS, counting only seconds that have power); 2) HR histogram with `rest < threshold < max`: Banister TRIMP; 3) otherwise `duration/3600 * 50`. |
| TRIMP | per bpm bucket: `minutes * w(hrr)`, hrr = clamp((bpm - rest) / (max - rest), 0, 1) (bpm cast with `int()`), `w(x) = x * 0.64 * e^(1.92 x)`. Normalised by `60 * w((threshold - rest) / (max - rest))` and multiplied by 100, so one hour at threshold HR = 100. |
| Effective settings | user value ("set"), else an estimate: max_hr = round(max of per-activity p99 HR), resting_hr = 60 ("default"), threshold_hr = round(rest + 0.85 (max - rest)), ftp = round(0.95 x best 20-min power over cycling activities). |
| CTL / ATL | daily series from the first activity day through **today** (days with no activity have load 0); `tsb = ctl_before - atl_before`; then `ctl += (load - ctl) / 42`, `atl += (load - atl) / 7`. |
| Day bucketing | activity start converted to the device's **local** zone; a 23:30 run belongs to that local date. |
| Lazy extension | `load_series` re-runs `recompute` if the calendar moved past the last stored day. Android: do the same on app start; no midnight job is needed. |

**Changing a threshold must not re-read streams**: only `activity_load` and the daily fold are re-run over stored metrics. Preserve that; it makes the Settings screen instant.

### 5.3 Layer 2: query-time analytics by feature

| Feature | Module | Inputs | Algorithm summary | Effort |
|---|---|---|---|---|
| Power / pace curves and PRs | `power_curve.curves`, `recompute` | `activity_bests`, sport, local-date range | Best value per span across activities in range (higher is better for power/speed, lower for distance_time); each point records the activity id and local date. `personal_records` is the same over all time. | S |
| HR zones, time in zone, polarization | `zones.py` | `hr_hist`, max HR | Floors `[0, .60, .70, .80, .90] x maxHR`, each rounded to an int; zone = last floor at or below the bpm. Weekly (Monday) totals with zero-filled weeks. Polarization: easy = Z1+Z2 share, moderate = Z3, hard = Z4+Z5; label *polarized* (easy >= 75% and hard > moderate), *pyramidal* (easy >= 75%), *moderate* (easy >= 50%), else *intensity-heavy*. | S |
| Training log | `log.py` | `activities`, load, route presence | Totals per week/month/year (zero-filled), month calendar (Monday-first), year-over-year cumulative per month (the current year stops at the current month), goals with `fraction`, `expected_fraction`, `on_track`. `elevation_missing` counts activities with a GPS route but null elevation, so totals show as incomplete rather than as 0. | M |
| Auto splits | `splits.py` | streams (not stored) | Cumulative moving time (sample gaps > 10 s or negative are ignored) and distance (ffill/bfill distance, or speed x dt) with the same glitch filter; cut at 1000 m or 1609.344 m with linear interpolation of time; a final partial split is kept only if it is 5% of a unit or more; per-split time-weighted average HR and cadence; altitude median-smoothed (5 samples) then 2 m hysteresis gain; summary: fastest, slowest, first vs second half speed, negative-split flag. | M |
| Cadence | `cadence.py` | cadence stream | Multiply by 2 if the median is < 120 (running) or < 80 (walking/hiking): the watch stores per-leg cadence. Display only; the stream is untouched. | S |
| Efficiency trend | `efficiency.series` | per-activity EF | One basis per chart (the most common); least-squares slope of EF per day x 30 / mean EF x 100 = % per month; needs 3 or more points over 14 or more days. | S |
| Predictions and VDOT | `predictions.py` | running `distance_time` best efforts in a window (default 180 days) | Riegel `T2 = T1 (D2/D1)^1.06`; basis efforts >= 1000 m and >= 180 s; comparable if within 1/2.5 to 2.5 times the target, the best prediction wins, else the nearest by log-ratio. Targets 5K/10K/Half/Marathon. VDOT (Daniels-Gilbert) from the best effort lasting 300 s to 3 h. | S |
| Critical speed | `critical_speed.py` | `speed` best efforts at 180, 240, 300, 420, 600, 900, 1200 s | 90-day trailing window (widens once to 180) of best speed per duration; needs 4 or more durations; linear fit of distance vs time gives CS (slope) and D' (intercept); `D' < 0` refits through the origin; `D' > 800 m` is flagged; R^2. Pace zones as fractions of CS: Easy 0, Steady 0.80, Threshold 0.90, Interval 1.00, Repetition 1.10. Monthly trend; Mile and 5K predictions `t = (D - D') / CS` only (the backtest showed longer distances are worse). | M |
| Run types | `run_types.py` | `speed_hist`, `hr_hist`, `reps`, CS timeline, typical distance | Precedence: intervals, long, tempo, easy, steady. Intervals: 3 to 30 hard reps (>= 0.95 CS), speed CV <= 0.15, duration CV <= 0.40. Long: distance >= max(1.5 x median of the last 90 days, 7 km), or >= 90 min, or >= 16 km. Tempo: hard share >= 0.40 and >= 20 min. Easy: easy share >= 0.75. Otherwise steady. CS is taken as of the run's date, not today. | M |
| Load safety | `load_safety.py` | `daily_load` | ACWR = mean(7 d) / max(mean(28 d), 10); ramp = CTL(t) - CTL(t-7); Foster monotony = mean / population SD of the last 7 days, strain = sum x monotony; thresholds 0.8 / 1.3 / 1.5, ramp 5 / 8, monotony 2; a warning list with level and code. Needs 28 or more days. | S |
| Volume / consistency | `volume.py` | activities | Weekly distance with a 4-week rolling mean and change vs the previous 4 weeks; streaks (consecutive Monday-weeks), longest gap, days since last, runs per week; PR timeline per standard distance and per year; year in review. | M |
| Route matching | `route_match.py` | `routes` polylines | Decode polylines; 32 points evenly spaced by arc length in local metres; two runs match when starts are within 200 m, lengths within 15%, mean point gap <= 80 m, max <= 250 m, same direction; greedy grouping against each group's first run; pace/HR trend only with 8 or more runs spanning 90 or more days. | M |
| Poster | `poster.py`, `poster.html` | all of the above | A single payload; the SVG is drawn client-side in JS. Privacy-safe route glyphs (normalised to [-1, 1]). | L (see sections 7 and 8) |

### 5.4 Numerical and semantic pitfalls when moving from Python to Kotlin

These are the places a straightforward port silently diverges. Each needs a golden-test case.

1. **Rounding.** Python `round()` is banker's rounding (half to even); Kotlin `roundToInt()` rounds half up. Use `Math.rint` where the PC code uses `round()` on values that can be exactly `.5`: the `hr_hist` bucket, the `speed_hist` bucket (`round(v/0.1)`), the `time` rounding in `resample_1hz`, the polyline `round(lat*1e5)`, the zone floors `round(max_hr*f)`, and the estimated `max_hr` / `ftp`.
2. **Window functions.** `pandas.rolling(n)` with the default `min_periods = n` emits NaN for the first n-1 points; `center=True, min_periods=1` (the 5-sample smoothers) shrinks the window at the edges. `quantile` and `np.percentile` use linear interpolation between order statistics (type 7). `np.polyfit(..., 1)` is ordinary least squares. `statistics.pstdev` is the *population* SD. `np.interp` clamps outside the range; `np.searchsorted(side=...)` semantics matter for splits.
3. **Gap constants differ by module and are all intentional:** the `resample_1hz` fill limit is **5 s**; `hr_hist` and splits use **10 s**; `moving_time` uses **30 s**; the heatmap GPS split is **250 m**. The Android guide mentions only the 5 s rule.
4. **`interpolate(limit=5)` quirk.** For a gap of 8 missing seconds, pandas fills only the first 5 (interpolated between the two real end points) and leaves the rest NaN, which are then dropped. It does not skip a gap just because it is long. Replicate exactly.
5. **Dictionary keys as strings.** `activity_bests` is keyed by `"5000"`, `"1609.34"`, `"16093.4"`, `"21097.5"`; the volume code once silently found nothing with float keys. In Kotlin use typed keys (`Int` seconds; standard distances as an enum) and serialise explicitly.
6. **Dates.** Local date = `datetime.fromisoformat(iso).astimezone().date()`, using the machine's zone rules for that date; use `ZoneId.systemDefault()` with `java.time` (never a fixed offset). Weeks start on Monday. "Today" uses the local calendar.
7. **Order-dependent results.** Greedy route grouping and `max(...)` with ties depend on iteration order; sort explicitly (oldest first, then by hash) so results are reproducible and match the PC output.
8. **Float precision.** Use relative parity tolerances (about 1e-6 for pure arithmetic, looser for sums over long 1 Hz series); do not demand bit equality for exp/pow.
9. **Cost of query-time analytics.** `run_types.classify_all` is O(runs) and also rebuilds the critical-speed timeline; the PC app calls it on every request, even for a single activity page. That is fine at about 3,000 activities on a PC. On Android, cache it per `(version, settings hash)` in the repository and invalidate after `recompute_all`, or persist the run type per activity as a derived table (it depends only on stored metrics).

<!-- S6 -->
## 6. API surface to Android repository mapping

The PC app is a thin FastAPI layer over `app/analytics`. On Android there is **no HTTP boundary**: every endpoint becomes a repository/use-case function that returns a Kotlin data class, observed by a ViewModel. Keep the endpoint names as the vocabulary so a future sync or a second client has a 1:1 reference.

### 6.1 Endpoint to repository mapping

| PC endpoint | PC module | Android function (in `:data` repository, logic in `:core`) | Notes |
|---|---|---|---|
| `POST /import` | `importers.import_file` | `ImportRepository.import(uris, options): Flow<ImportProgress>` | Runs in WorkManager; the form field `terrain` becomes an option (F11, optional). |
| `GET /activities` (start, end, sport) | `main.list_activities` | `ActivityDao.list(startUtc, endUtc, sport)` then local-date filter in `:core` | Same trick as PC: query a day of slack in UTC text, then filter exact local dates in code. Paging for a 3,260-row history. |
| `GET /activities/sports` | | `ActivityDao.sportCounts()` | |
| `GET /activities/{id}` | `main.get_activity` | `ActivityRepository.detail(hash): ActivityDetail` | Aggregates activity, metrics, streams, laps, zones, pace zones, run type, cadence summary, elevation source. Split into lazily loaded pieces on a phone (streams are ~93 KB average, up to 1.3 MB as JSON). |
| `GET /activities/{id}/splits` | `splits.compute_splits` | `SplitsUseCase(hash, unit)` | Computed on demand, not stored. |
| `DELETE /activities/{id}`, `POST /activities/bulk-delete` | | `ActivityRepository.delete(hashes)` | One transaction in chunks of 500 (stays under SQLite's bound-variable limit, which is only 999 on older SQLite versions; check the limit on the minimum Android API you target). Keep the archived file. Write a tombstone to `changes` (sync-ready). |
| `GET /analytics/load` | `training_load.load_series` | `LoadRepository.series(): Flow<List<DailyLoad>>` + `effectiveSettings()` | Extend through today if the calendar moved. |
| `GET /analytics/load-safety` | `load_safety.analysis` | `LoadSafetyUseCase(days)` | Reads `daily_load` only. |
| `GET /analytics/zones` | `zones.weekly_zones` | `ZonesUseCase(sport, from, to)` | |
| `GET /analytics/efficiency` | `efficiency.series` | `EfficiencyUseCase(sport, from, to)` | |
| `GET /analytics/power-curve` | `power_curve.curves` | `CurvesUseCase(sport, from, to)` returns range and all-time | |
| `GET /analytics/predictions` | `predictions.predictions` | `PredictionsUseCase(days)` | |
| `GET /analytics/critical-speed` | `critical_speed.analysis` | `CriticalSpeedUseCase()` | Also exposes `Timeline` to run types and the activity page. |
| `GET /analytics/heatmap` | `heatmap.routes` | `RouteRepository.routes(sport, from, to)` | Hundreds to thousands of polylines; decode lazily (§10). |
| `GET /analytics/log`, `/log/calendar`, `/log/yearly` | `log.*` | `LogUseCase.totals / month / yearOverYear` | |
| `GET/POST/DELETE /goals` | `log.goal_progress` etc. | `GoalRepository` | New `goals` entity. |
| `GET /analytics/run-types` | `run_types.weekly_mix` | `RunTypesUseCase(weeks)` | Cache; see 5.4 item 9. |
| `GET /analytics/volume`, `/records-timeline`, `/year-review` | `volume.*` | `VolumeUseCase` | |
| `GET /analytics/routes` | `route_match.routes` | `RouteMatchUseCase(sport, minRuns, limit)` | CPU-heavy; run on `Dispatchers.Default`, cache until the route set changes. |
| `GET /analytics/poster` | `poster.poster_data` | `PosterUseCase` (optional) | |
| `GET/PUT /settings` | `training_load.effective_settings`, `save_settings` | `SettingsRepository` (+ triggers `LoadRepository.recompute()`) | `null` clears a value back to the estimate. Validation ranges: FTP 0-1000, HR 0-250. |
| `GET /terrain/providers` | `terrain` | n/a (optional) | |
| `/chat/*` | `llm.chat` | n/a | Out of scope. |

### 6.2 Recompute pipeline

PC: `recompute_all` runs `training_load.recompute` then `power_curve.recompute` then `heatmap.recompute` then `places.recompute`, at startup and after imports, deletes and settings edits.

Android design:
- `RecomputeWorker` (WorkManager, unique work, `ExistingWorkPolicy.APPEND_OR_REPLACE`) performs the same four steps, in the same order, each only for rows whose `version` is missing or old.
- Settings edits only need the cheap part (`LoadRepository.recompute()` over stored metrics), so run it inline, not through the heavy worker.
- Expose progress as `WorkInfo` flow so the UI shows "Analysing 120 / 3,260 activities". The first full run on 3,260 activities is the heaviest event the app will ever have (§10).
- Because Room emits `Flow` updates on table invalidation, screens refresh themselves when derived tables are rewritten; avoid manual refresh plumbing.

### 6.3 Response shapes worth keeping

Keep the JSON shapes of these as Kotlin data classes verbatim (field names in snake_case via `@SerialName`), because they are the contract used by the PC tests and the golden fixtures in the parity plan (§11): activity detail, `curves`, `load_series`, `weekly_zones`, `totals`, `goal_progress`, `compute_splits`, `fit_window`/`analysis`, `classify`, `metrics`/`warnings`, `weekly_volume`, `records_timeline`, `year_review`, `routes`, `predict`.

<!-- S7 -->
## 7. UI pages to Compose screens

Source: `app/static/*.html`, `common.js`, `style.css`. The web UI has seven pages in a top nav (Activities, Fitness, Log, Running, Poster, Curves, Heatmap) plus a chat panel injected on every page.

### 7.1 Navigation

A seven-item top bar does not fit a phone. Proposed (single activity, Navigation Compose):

| Bottom bar (4) | Contains |
|---|---|
| **Activities** | list, filters, import, multi-select delete, activity detail |
| **Fitness** | load chart, load safety, HR zones, efficiency, thresholds settings |
| **Log** | totals, calendar, year over year, goals |
| **Running** | year in review, volume, run types, PBs, routes |
| **More** (overflow or a fifth tab) | Curves, Heatmap, Poster (optional), Settings (units, thresholds, rebuild, data usage, about) |

The activity detail is a pushed destination, reachable from the list, calendar, heatmap line tap, PR rows, route runs and the curves "set by" links (the web app uses links to `activity.html?id=`).

### 7.2 Page to screen mapping

| Web page | Screen(s) | Web components | Compose equivalent | Notes |
|---|---|---|---|---|
| `index.html` | `ActivitiesScreen` | dropzone import, result list; date range + sport filter; table with checkboxes; select-all; bulk delete dialog; terrain dialog; busy dialog with progress bar | FAB "Import" opening the system picker; filter chips + `DateRangePicker`; `LazyColumn` with long-press selection; confirmation dialog requiring the typed word `DELETE` (keep the friction: deletes are not undoable from the UI, only by re-import); progress via WorkManager | Default filter is the current month so the full history is never rendered. Keep that. |
| `activity.html` | `ActivityDetailScreen` | stat tiles; route map with fit-to-bounds and moving marker; stacked time-series panels with unified hover; zones bar/table; pace zones; interval reps; splits (unit follows display units); laps | `LazyColumn` of cards; map card; chart card with a draggable scrubber that moves a map marker (the PC does this on `plotly_hover`) | The scrubber-to-marker link is the main interaction to reproduce. Downsample streams for drawing (e.g. LTTB to about 600 points) while keeping full data for calculations. |
| `fitness.html` | `FitnessScreen` | CTL/ATL/TSB chart with range buttons; load-safety card with warning list; weekly zone stacked bars; efficiency trend; threshold form (value + source "set/estimated/default") | Cards; `SettingsForm` with "reset to estimate" | Settings live here on the web; on Android also expose from More > Settings, same ViewModel. |
| `log.html` | `LogScreen` | period toggle (week/month/year), sport and metric selectors, totals chart + table, goals list and form, month calendar with prev/next, year-over-year chart | Segmented buttons, chart, list; custom calendar grid composable; goal bottom sheet | Show the `elevation_missing` caveat text, as the web app does. |
| `running.html` | `RunningScreen` | year picker + stats + notes; weekly volume chart + consistency; run-type stacked bars + legend + recent labels; PB timeline table; repeated routes list + route map + trend chart | Sections in one scrolling screen, or sub-tabs | Run-type colours are fixed constants in `common.js` (`RUN_TYPES`); copy them. |
| `curves.html` | `CurvesScreen` | sport selector, presets and custom dates, power curve (log x), pace curve, best-efforts table, predictions, critical-speed card | Same cards | Log x-axis with duration tick labels. |
| `heatmap.html` | `HeatmapScreen` | sport + date filters, summary, canvas of all routes at 40% opacity, tap selects and highlights a line, legend by sport | MapLibre `LineLayer` per sport using one GeoJSON source; tap via feature query | Never one Android view per polyline. |
| `poster.html` | `PosterScreen` (optional) | 11 modes x 7 colour schemes, city-name toggle, 3D spiral option, SVG/PNG export | See section 8 | |
| `chat.js` | none | floating chat panel | none | Out of scope. |

### 7.3 Charts: what the web app actually draws (Plotly) and the Android substitute

| Chart | Plotly features used | Compose/Vico requirement |
|---|---|---|
| Activity time series | up to 5 stacked panels sharing x, line, filled area (elevation), unified hover, `scattergl` for long series | Several synchronised line charts (or one with multiple layers) with a shared scrubber; pace axis is inverted/formatted `m:ss` |
| Fitness / fatigue / form | 3 lines + filled CTL, bars for load, range buttons via `relayout` | Line + column layers, selectable x-range |
| Load safety | line with banded threshold regions | Line plus horizontal bands (custom drawing) |
| Weekly zones, run types, weekly volume, log totals | stacked and plain bars | Stacked columns |
| Power / pace curve | **log x-axis** with custom ticks (1 s … 6 h), unified hover | Vico has no log axis to my knowledge (verify in a spike); fall back to plotting x = ln(seconds) with a custom axis formatter |
| Efficiency, critical-speed trend, route trend | scatter/line with a trend line, secondary y-axis (HR) | Line + point layer, optional second axis |
| Year over year | cumulative lines per year, current year truncated | Multi-series line |
| Calendar | HTML grid | Custom composable |

Recommendation: **spike Vico on three screens first** (activity time series with a scrubber, power curve with a log axis, stacked columns) before committing; the Android `CLAUDE.md` already names Vico but nothing has been built with it. If one of the three cannot be done acceptably, the contingency is a small custom `Canvas` chart component for that case only, not a different library for the whole app.

### 7.4 Cross-cutting UI behaviours to carry over

- Units: data is always metric; a global toggle changes display only (km/mi, m/ft, km/h or mph, min/km or min/mi). Pace shows for run/walk/hike, speed (km/h) for the rest. Formatters live in `:core` (the PC has them twice, in `common.js` and `llm/tools.py`: unify them).
- Dark/light theme following the system, with fixed semantic colours: run types (easy `#74c0fc`, steady `#51cf66`, tempo `#fcc419`, long `#9775fa`, intervals `#fa5252`, unknown `#adb5bd`) and HR zones (`#74c0fc #51cf66 #fcc419 #ff922b #fa5252`).
- "Load*" with an asterisk when the load was estimated from duration only (no power/HR).
- Elevation asterisk when the elevation was looked up rather than recorded.
- Empty states for every card (the web pages all have an `#empty` element).
- The map base-layer choice is remembered, and "None (routes only)" makes no network requests (privacy option).
- App icon: the web app already ships 192/512/maskable PNGs that can seed an adaptive icon.

<!-- S8 -->
## 8. Features that do not port directly

### 8.1 Ollama chat (F43): drop for now

PC: `llm/chat.py` (tool-calling loop, NDJSON stream, tool-call-as-text recovery, `NUM_CTX` 4096, temperature 0) and `llm/tools.py` (7 tools: `get_fitness_status`, `get_training_summary`, `compare_periods`, `get_weekly_summary`, `query_activities`, `get_activity_details`, `get_power_curve`). The tools were tuned for a 3B model on a 4 GB GPU. The PC chat tools do not yet know about the roadmap analytics either.
Android guide already says out of scope. When it is revisited, the analytics use-cases from section 6 are exactly the tool layer, so nothing built now blocks it. Options then: (a) call the PC's Ollama over Tailscale (needs the sync/network phase), (b) an on-device model. Neither is needed for feature parity of the tracker itself.

### 8.2 Desktop shell, uvicorn, PWA/Tailscale (F44, F45): replaced

`desktop.py` (pywebview + uvicorn) and the PWA manifest exist to put a web UI in a window or on a phone. A native app makes them irrelevant. Keep `manifest.webmanifest` colours (`theme_color #e8590c`) and icons as branding input.

### 8.3 Terrain elevation (F11): optional, later

Needed because the user's Amazfit Bip 6 files contain **no altitude** (verified on the PC against raw files). Without it, elevation for those activities is blank (the PC marks totals "incomplete" via `elevation_missing`). Options on Android:
- **Strava CSV fill (F10)**: cheapest and tuned (matches by file name; metres). Only helps for the already-exported history. Needs the `elevation_overrides` table and re-application after rebuilds.
- **Terrarium tiles** (default PC provider, 25 m sampling, no smoothing, 2 m threshold, 3.8% median error vs Strava): feasible with `BitmapFactory` and an on-disk tile cache; needs network and respects the provider's terms. `Open-Meteo` is a labelled fallback (about 30% over-count). `hgt` (offline SRTM) was only tested with synthetic files on the PC.
- Gate it behind an explicit option at import, as the PC does, and never let a provider failure fail the import.
Decision for the user (section 13, Q3): how much the Bip 6's missing elevation matters going forward.

### 8.4 Places / cities (F41): optional, largest dependency cost

PC: `reverse_geocoder` (GeoNames cities1000 with a KD-tree, pulls in scipy) samples 7 points per route, then folds places within 6.5 km into the most-visited one. Used only by the poster. On Android: bundle a compact city list as an asset (a few MB) with a simple spatial index (grid or KD-tree) and re-implement `areas()` clustering; no network. Check the GeoNames licence and attribution requirement before bundling. Low value unless the poster is ported.

### 8.5 Poster (F40): optional, a UI port rather than a logic port

The server side (`poster.py`) is just a payload from existing analytics; **all drawing is client-side JS (about 840 lines)**: 11 modes, 7 colour schemes, a 3D spiral, SVG and PNG export. Two ways to deliver it:
1. **WebView host** (cheapest): ship `poster.html` and its JS as app assets, inject the payload JSON from Kotlin, let it draw and export. This reuses code unchanged and does not contradict the "no cross-platform framework" decision (it is one screen). Need to verify the page's dependencies (it includes `common.js`) work offline with no external hosts.
2. **Compose `Canvas` rewrite**: each mode becomes a Kotlin draw function; PNG export via `Bitmap`. Larger, but fully native.
Recommendation: defer; if wanted, start with option 1. Privacy rule to preserve in either: route glyphs are normalised to a unit box (no coordinates in the payload), and the city line is optional.

### 8.6 Rarely-needed PC tooling

`python -m app.import_dir --dry-run/--match/--sport auto/--newest-first` flags: Android needs the *behaviour* (dry-run summary, similar-skip counts, auto-sport) in the bulk-import screen but not a CLI. `app.reparse`: keep as a Settings action (section 4.5). Test helpers (`tests/fit_builder.py`) are used on the PC side to generate golden fixtures (section 11), not ported.

<!-- S9 -->
## 9. Library mapping (Python to Kotlin)

PC dependencies (`requirements.txt`) and what replaces them. The Android guide's rule applies: keep the list short, add a library only when the platform cannot do the job.

| PC library | Used for | Android replacement | Notes |
|---|---|---|---|
| `fitparse` | FIT decoding | **Garmin FIT SDK for Java**, `com.garmin:fit` on Maven Central (published as version 21.176.0 or higher; latest seen in search: 21.202.00. Pin the exact version when adding) | Test on real Amazfit, Garmin and Strava FIT files; see 4.4 for the differences. [Maven Central](https://central.sonatype.com/artifact/com.garmin/fit), [GitHub](https://github.com/garmin/fit-java-sdk) |
| `gpxpy` | GPX | `XmlPullParser` (platform) | `kxml2` on the JVM for `:core` tests. Strip `<number>` tags as the PC does. |
| `xml.etree` | TCX | `XmlPullParser` | Match by local name; ignore namespaces. |
| `numpy` | rolling windows, cumsum, interp, searchsorted, polyfit, percentile | Plain `DoubleArray` code in `:core` | Small, testable helpers: `rollingMean`, `percentile` (type 7), `ols`, `interp`, `searchSorted`. Avoid a big numerics dependency. |
| `pandas` | 1 Hz resampling with `ffill(limit)`, `interpolate(limit)`, `rolling`, `quantile` | Hand-written `Resampler` + helpers | The single most important thing to get bit-exact (5.1, 5.4). |
| `scipy` | transitive of `reverse_geocoder` only | none | |
| `sqlite3` | DB | **Room** (SQLite) + KSP | Plain SQL queries via `@Query`; no ORM concept to unlearn. |
| `fastapi`, `uvicorn`, `pydantic` | HTTP API | none (repositories) | Pydantic validation rules move into the settings/goal form validators. |
| `pywebview` | desktop window | none | |
| `ollama` | chat | none (out of scope) | |
| `Pillow` | terrain tile decoding | `BitmapFactory` | Optional feature only. |
| `reverse_geocoder` | places | bundled asset + small index | Optional feature only. |
| Plotly | charts | **Vico** (Compose) | Spike first (7.3). Current Vico docs describe customizable axes; log scaling is unconfirmed, so plan an `ln(x)` transform with a custom label formatter. |
| Leaflet | maps | **MapLibre Native for Android** + OSM raster tiles | Follow the tile usage policy (real User-Agent, no prefetching). Keep the "None" base layer with zero network requests. Esri/OpenTopoMap/CyclOSM layers were each verified by eye on the PC; re-verify on Android rather than assuming (a provider once returned HTTP 200 with a "key required" image). |
| Python `datetime`/`zoneinfo` | local dates | `java.time` | Desugaring (core library desugaring) if the minimum SDK is below 26. |
| Python `json` | stream JSON | `kotlinx.serialization` | Already chosen. Stream arrays contain `null`s; use `Double?` or a NaN sentinel in memory. |
| `gzip`, `hashlib` | gz input, sha256 | `java.util.zip`, `MessageDigest` | |

### 9.1 Representing streams in memory

The PC keeps streams as Python lists with `None` for missing samples. For Kotlin use a `Streams` class holding `DoubleArray`s with `Double.NaN` for missing, aligned to `time`. It makes the resampler and window maths simple and fast, and converts to/from the stored JSON at the edges. Note for parity: `None` vs `0` is meaningful (an all-zero sensor stream is dropped by `keep_streams`, but a zero among real samples is data).

<!-- S10 -->
## 10. Performance, storage and background work

### 10.1 Real data volume (measured on the PC app's data on 2026-10-17)

| Measure | Value |
|---|---|
| Activities | 3,260 (2012-09 to 2026-10) |
| Sports | running 1,261; training 806; walking 797; swimming 134; cycling 83; weight training 66; workout 52; fitness equipment 25 (others smaller) |
| Sources (device field) | `strava 1159` 1,223; `garmin 2700` 780; Amazfit Bip 6 746; gpxpy-written GPX 207; `StravaGPX` 105 |
| `fitness.db` size | 315 MB, of which `streams` JSON is 305 MB (average 93 KB, max 1.29 MB per activity; about 1,390 samples on average) |
| Raw archive `data/imports/` | 119 MB (3,265 files, mostly `.fit.gz`) |
| Derived tables | `activity_bests` 1.2 MB, metrics histograms 2.6 MB, route polylines 0.5 MB, `daily_load` 5,139 rows |
| Laps | 9,782 rows |
| Stream JSON gzip ratio | 3.76x on a 300-activity random sample (28.4 MB to 7.6 MB) |

Implication for the phone: raw archive 119 MB + gzipped streams about 81 MB + derived under 5 MB, i.e. **roughly 200 MB** (about half the PC's 434 MB). That is comfortable. The streams are not synced (only raw files are, per the Android guide), so their on-disk format is a free choice: gzipped JSON is simplest and debuggable; a compact binary (float arrays) would be smaller still but gains little at this size. Recommend gzipped JSON first.

### 10.2 Android-specific constraints to design around

1. **CursorWindow 2 MB limit.** Android reads SQLite rows through a roughly 2 MB window; a single row larger than that fails to read. The PC's largest stream blob is 1.29 MB as JSON, close enough that a longer activity could break it. Store streams gzipped (a 1.3 MB blob becomes about 0.35 MB) **or** as files in `filesDir/streams/<hash>.json.gz` referenced from the DB. Load them off the main thread and only for the activity being viewed. (Verify the exact window size on the minimum API level during the Room spike.)
2. **Never load all streams at once.** `ensure_*` loops must stream rows one at a time (PC does `fetchall()` of every pending row, including streams, which would exhaust phone memory on a first run).
3. **First-run cost.** The PC needed about 12 minutes for 3,045 files (import plus analytics), dominated by `fitparse` at about 0.6 s per hour of 1 Hz data; a full reparse took 11 minutes. A phone will be slower per core. Plan for: a foreground, notification-backed job; resumable after process death (progress = rows already imported/versioned, which the per-row `version` columns already give); partial results shown as they land. Measure the Garmin SDK on-device before promising a time.
4. **Foreground service rules.** Long WorkManager jobs need a foreground service with a declared type; recent Android versions put time limits on some types (the data-sync type has a multi-hour cap on Android 15). Verify against the target API level; design the job to checkpoint and resume anyway.
5. **Battery/Doze.** Do bulk import while the screen is on or charging by default; offer "run now" in the foreground.
6. **Heatmap drawing.** 3,260 routes, 0.5 MB of encoded polylines: decode lazily and add as one GeoJSON `MultiLineString` source per sport; apply sport/date filters by rebuilding that source off the main thread. Keep the PC's behaviour of 40% line opacity and a highlighted selected line.
7. **Query-time analytics under UI.** Route matching, run-type classification and critical-speed timeline are O(runs) to O(runs x groups). The PC vectorised route grouping (3x faster; 365,000 comparisons for 1,000 runs before pre-filtering). Port the *pre-filtered* version (starts and lengths compared in bulk before the point-by-point check) and run on `Dispatchers.Default`. Cache results keyed by derived-data version.
8. **Time zones.** Day bucketing uses the device zone at query time, as on the PC. Travelling or DST changes can move a late-night activity to a different day; this is accepted PC behaviour, so keep it.
9. **Data safety (not in either guide).** App-private data is deleted on uninstall, and Auto Backup has a small size quota, so a 200 MB history is effectively unprotected. Add an **Export** action (zip of the raw archive + settings + goals JSON via the document picker) and an **Import backup** action. This is also the cheapest first form of PC/phone transfer (section 13, Q4).
10. **Signing and updates.** For sideloaded updates to install over an existing install, every build must be signed with the same key and package name. Keep the release keystore backed up; losing it means uninstalling (and losing app data) to upgrade.
11. **Android developer verification.** Google announced that apps on certified devices will need a verified developer to be installable outside the Play Store, with enforcement starting in Brazil, Indonesia, Singapore and Thailand in September 2026 and a global rollout from 2027, a lighter path for students and hobbyists, and ADB installs exempt ([9to5Google](https://9to5google.com/2025/08/25/android-apps-developer-verification/), [Bitdefender summary](https://www.bitdefender.com/en-us/blog/hotforsecurity/google-developer-verification-sideloaded-apps)). Registration details were only seen via news coverage; check Google's current developer documentation before release and plan to register as a hobbyist if the user's region is covered, or fall back to `adb install`.

<!-- S11 -->
## 11. Parity-testing strategy

The Android guide already requires parity tests ("golden JSON ... within a small tolerance"). This section makes that concrete. The PC suite has 33 test files (`tests/`), which are a second source of expected behaviour: each test documents a regression that was found on real data.

### 11.1 Golden-fixture generator (build on the PC side)

A script `tools/export_golden.py` in the PC repo (not yet written) that, for a chosen set of files, runs the PC code and writes JSON next to a copy of the input into `FitnessTrackerAndroid/core/src/test/resources/golden/`:

| Level | Fixture content | Compared against |
|---|---|---|
| Parse | sport, start_time, duration, distance, elevation_gain, device, stream keys, sample count, checksum and first/last 5 samples of each stream, laps | Kotlin parser output |
| Layer 1 | `stream_metrics` dict, `activity_best_efforts`, `route_segments` (exact string equality), per-activity `load` for fixed settings | `StreamMetrics`, `BestEfforts`, `RouteSimplifier`, `LoadScorer` |
| Layer 2 over a whole mini-history | outputs of `curves`, `weekly_zones`, `totals`, `fit_window`, `classify_all`, `metrics`+`warnings`, `weekly_volume`, `records_timeline`, `routes`, `predictions`, `load_series` computed on a fixed set of about 60 files | The same use-cases over a Room database filled by the Kotlin importer |
| Constants | JSON of every named constant (`ZONE_FLOORS`, `CS_DURATIONS`, `MAX_SPEED`, `DURATIONS`, run-type thresholds, ...) | A Kotlin test that asserts the Kotlin constants equal the exported ones, so drift is a failing test, not a surprise |

Fix "today" in generators and Kotlin tests (the PC functions already take an optional `today`).

### 11.2 Choosing input files (needs the user's OK to copy personal data)

Cover each parser path and each known quirk:
- Amazfit Bip 6 FIT (no altitude, 0 W power, per-leg cadence), Garmin FIT (laps, device name), Strava-recorded FIT.
- GPX: Strava-style with numeric type and UUID `<number>`; NRC export with no sport (`unknown`); a GPX with no timestamps (must fail cleanly).
- TCX: old file with leading whitespace; lap vs track distance disagreement; indoor TCX with no GPS.
- Pool swim (sparse distance; must keep the timer duration), indoor strength (no distance), a walk without auto-pause (moving time < timer).
- A cycling file with real power; a run with intervals; a run with long pauses; a "smart recording" file (samples every few seconds).
- Duplicate pairs: identical bytes, `.fit` vs `.fit.gz`, same run from two apps (moment), timezone-shifted copy (zone), two real same-day runs (must both import).
- Synthetic edge cases from `tests/fit_builder.py` (empty, one-sample, gaps over 30 s, GPS jump).

### 11.3 Tolerances

Integer results (counts, bucket keys, strings such as polylines, zone indices, run type labels, dates): exact. Floats: relative 1e-9 for plain arithmetic, 1e-6 for sums over long series, and an explicit looser, documented tolerance for anything involving `exp`/`pow` chains (TRIMP) or least-squares fits. Any deviation larger than that is a bug in the port or a deliberate change, which must update both apps ("When the PC formula changes, the Android port must match" per the Android guide's rule).

### 11.4 Layers of tests

1. `:core` JVM unit tests: helpers (`rint` rounding, percentile, rolling, interpolation), each module against golden fixtures, plus ports of the PC regression tests (moving-time with sparse distance, `_key` string keys, route grouping threshold cases, CS fit with `D' < 0`, ACWR floor).
2. `:data` instrumented tests: Room schema, DAO queries, the import → recompute → read pipeline, deletes with cascades, version bump rebuilds, CursorWindow-sized blobs.
3. End-to-end: import the user's folder of real files on a device or emulator and diff key endpoint outputs against the PC's for the same files (using the PC DB as oracle). This is the "test against real files before the next phase" rule from both guides.
4. Compose UI tests for the few interactive pieces (typed `DELETE` gate, filters, scrubber).
5. Performance check as a test (not just a note): import time, memory and recompute time for N files, logged so regressions show.

### 11.5 Cross-checking the PC app's own claims

The PC `CLAUDE.md` quotes validation numbers (moving time median +0.01% vs Strava over 3,039 activities; TCX distance vs Strava; elevation error 3.8%; CS backtest). Those are properties of the algorithms; the Android port should reproduce the **same numbers** on the same inputs, which makes them useful acceptance checks for the parser-level work.

<!-- S12 -->
## 12. Proposed phased roadmap

Order follows data dependency first (what the next phase needs), then the PC roadmap's own order, then the Android guide's priorities. Every phase ends with: parity tests green, and a check against the user's real files (both guides require this). Sizes are relative (S/M/L from section 5), not calendar estimates.

| Phase | Goal | Features (section 2 ids) | Exit criteria | Size |
|---|---|---|---|---|
| **0. Spikes and foundations** | Remove the biggest unknowns before building | Scaffold the 3 modules; Room with source/derived split; read a real Amazfit, Garmin and Strava FIT with the Garmin SDK; Vico scrubber + log-axis + stacked columns; MapLibre drawing 3,260 routes; golden-fixture exporter on the PC (11.1); update `FitnessTrackerAndroid/CLAUDE.md` for the drift in 3.4 | Three go/no-go answers (FIT SDK, Vico, MapLibre); golden files exist | M |
| **1. Import and browse** | Data in, list and detail | F1-F4, F6, F9, F12, F13, F14, F16 (route only), F17 laps, F42 | Parity on parse-level fixtures for FIT/GPX/TCX; imports 100 real files; duplicate rules match; list and detail show correct tiles, route and laps | L |
| **2. Core analytics** | Moving time, load, curves | `Resampler`, `moving_time`, `stream_metrics`, best efforts, `routes`; F15 charts, F20, F24, F34, F35; F18 zones for one activity; cadence; splits (F17); delete tombstones | Layer-1 parity (metrics, bests, polylines exact); `daily_load` parity on the mini-history; thresholds screen recomputes instantly | L |
| **3. Bulk import and rebuild** | Get all 14 years onto the phone | F7, F8, F5 similar-detection, F1-3 hardening, Export/Import backup (10.2 item 9) | The full 3,260-file archive imports unattended, resumes after being killed, and end-to-end diff vs the PC DB is within tolerance | M |
| **4. Heatmap** | Maps | F38, F39 | Smooth pan and zoom with all routes; base-map choice persists; "None" makes zero requests | M |
| **5. Log and training analytics** | Planning views | F25-F28 (totals, calendar, YoY, goals), F22 zones weekly + polarization, F21 load safety, F23 efficiency | Parity on the `log`, `zones`, `load_safety`, `efficiency` outputs | M |
| **6. Running analytics** | The runner-specific layer | F36 predictions, F37 critical speed, F19 run types / pace zones / reps, F29-F33 volume, PB timeline, year review, route matching | Parity on `classify_all`, `fit_window`, `routes`; same group counts as the PC on the full history | L |
| **7. Optional extras** | Only if wanted | F10 CSV elevation, F11 terrain, F40 poster (WebView first), F41 places | Per-feature, user decides | M-L each |
| **8. Later (already planned elsewhere)** | Sync with the PC; chat | Sync over Tailscale using `file_hash`, tombstones, settings; LLM chat | Out of scope for this analysis | |

Why this order: phase 2 produces every Layer-1 table, so phases 4-6 are pure reads and can be built, parallelised and tested independently; phase 6 is last because it depends on critical-speed history, which needs bests for every activity (so the full import in phase 3 first). The PC roadmap items 1-6 (zones, log, splits, cadence, efficiency, predictions) are spread across phases 2, 5 and 6 according to their data needs, not their PC order.

**Minimum useful app**: end of phase 3 (everything imported, load and curves, correct numbers). Phases 4-6 add the views.

<!-- S13 -->
## 13. Risks and open questions

### 13.1 Risks

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R1 | **Silent numeric divergence** from the PC (rounding mode, `pandas` gap semantics, window edges, local-date rules) gives plausible but wrong charts | High / High | Section 5.4 checklist, golden fixtures, constants exported from the PC (11.1) |
| R2 | **Drift**: the PC app keeps changing (it has had two roadmaps since the Android guide was written) and the port falls behind | High / Medium | Keep PC as reference; constants-parity test; a short "port status" table per feature in the Android repo; tell the user when a PC formula changes |
| R3 | **FIT SDK behaves differently from `fitparse`** on the user's real files (strictness, field names, device names) | Medium / High | Phase 0 spike on real files; decide the CRC/leniency policy early (4.4) |
| R4 | **Vico cannot do** the scrubber-linked stacked charts or the log-axis curves well | Medium / Medium | Phase 0 spike; contingency is a small custom Canvas chart for the failing case only |
| R5 | **First import too slow / killed by the OS** | Medium / High | Foreground job, resumable per row, progress UI; measure early (10.2) |
| R6 | **Large blobs break Room reads** (2 MB window) | Medium / High | Gzip or file-backed streams (10.2 item 1) |
| R7 | **Data loss on uninstall/key loss** for a 14-year history | Medium / High | Export/Import backup in phase 3; keystore backup (10.2 items 9-10) |
| R8 | **Sideloading policy changes** (developer verification) | Medium / Medium | Check current Google docs before release; hobbyist path or ADB (10.2 item 11) |
| R9 | **Tile provider terms** change or block (the PC saw a placeholder-image failure and an OSM block) | Medium / Low | "None" base layer always works; re-verify each provider on-device; real User-Agent; no prefetching |
| R10 | **Scope creep** from the poster, places and terrain | Medium / Medium | Phase 7 is optional and per-feature |
| R11 | Garmin FIT SDK licence/redistribution terms for an app | Low / Low | Personal use, not distributed; still read the SDK licence before sharing the APK |

### 13.2 Open questions for the user (each changes what is built)

**Decisions made 2026-10-17:** (Q2) all optional extras are wanted: poster, cities, terrain elevation, Strava CSV elevation, so phase 7 is in scope after phase 6. (Q4) the history is moved by the recommended route: the raw archive is staged in `FitnessTrackerAndroid/seed/` (3,265 files, `activities.csv`, `pc-side-data.json`) and bulk-imported on the phone. The drift in 3.4 has been fixed in `FitnessTrackerAndroid/CLAUDE.md`. (Q1) the phone is a Pixel 7 and the minimum is Android 17, so no desugaring is needed and the foreground-service and sideloading rules to check are those of Android 17. Still open: Q3, Q5-Q8.


1. **Phone and minimum Android version?** Decides core-library desugaring, foreground-service rules, Photo/document pickers, and which APIs are available. (Not in either guide.)
2. **Which optional features do you want on the phone?** Poster, cities, terrain elevation, Strava-CSV elevation (all in phase 7). Recommended default: none initially.
3. **Elevation for the Bip 6:** is blank elevation on new Amazfit activities acceptable, or do you want terrain lookup (needs network at import)?
4. **How does the 14-year history reach the phone?** Recommended: copy `data/imports/` (119 MB) to the phone and bulk-import it (one code path, doubles as the end-to-end parity test). Alternative: a PC-side exporter that writes the phone's Room database directly (faster for the user, but a second code path to keep in sync). Longer term this is the sync feature.
5. **Should future new activities keep flowing PC to phone** (or phone to PC) before the real sync phase? If yes, the Export/Import backup in phase 3 becomes the interim answer.
6. **Is the project documented in one place?** Proposed: this file stays as the analysis; decisions move into `FitnessTrackerAndroid/CLAUDE.md` once confirmed (including fixing the drift in 3.4).
7. **Units and language**: metric default, imperial toggle (as on the PC)? English only?
8. **Chat later**: PC Ollama over Tailscale, or an on-device model, or neither?

### 13.3 What was not verified

- No Android code exists yet (the Android folder holds only `CLAUDE.md`), so every Android statement here is a design claim. The three libraries (FIT SDK on real files, Vico, MapLibre) are unproven for this app until phase 0.
- Vico's log-axis support and the exact CursorWindow size on the minimum API level were not confirmed from documentation.
- Android developer-verification details come from news coverage, not Google's own pages.
- Effort sizes are relative judgments, not measurements.
- Facts about the PC app were read directly from source on 2026-10-17 (`app/**`, `static/*.html`, `CLAUDE.md`) and measured on its database; I did not run the PC test suite.
