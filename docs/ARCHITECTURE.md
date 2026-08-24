# MyRun — Developer Guide

This document explains how the app is put together, why it is built the way it is, and what to watch out for when changing it. It assumes you know Kotlin and have used Jetpack Compose; Wear OS specifics are explained as they come up.

- [1. Overview](#1-overview)
- [2. Module map](#2-module-map)
- [3. `TimerEngine` — the state machine](#3-timerengine--the-state-machine)
- [4. `TimerService` — running in the background](#4-timerservice--running-in-the-background)
- [5. Vibration](#5-vibration)
- [6. `MainActivity` — UI, navigation, ambient mode](#6-mainactivity--ui-navigation-ambient-mode)
- [7. Persistence](#7-persistence)
- [8. Manifest, permissions, and build](#8-manifest-permissions-and-build)
- [9. Lifecycle walkthroughs](#9-lifecycle-walkthroughs)
- [10. History of problems and their fixes](#10-history-of-problems-and-their-fixes)
- [11. Extending the app](#11-extending-the-app)
- [12. Debugging on a real watch](#12-debugging-on-a-real-watch)

---

## 1. Overview

MyRun is a single-module Wear OS app (`app/`) with four source files. It alternates between a **RUN** and a **WALK** interval, vibrates on every switch, and keeps counting while the watch is on the watch face or in ambient mode.

The design boils down to three ideas:

1. **State lives in a process-wide singleton (`TimerEngine`), not in a ViewModel.** A ViewModel dies with its Activity; the timer must outlive the screen.
2. **A foreground service (`TimerService`) owns the tick loop, the wake lock, and the vibrator.** It is the only thing Wear OS will not freeze when the user leaves the app.
3. **Time is derived from a monotonic deadline, not counted down.** The engine stores *when the current phase ends* on `SystemClock.elapsedRealtime()` and recomputes `timeLeft` on every tick. This makes it immune to missed ticks, CPU freezes, and clock drift.

```
                ┌──────────────────────────────────────────────┐
                │            MainActivity (Compose)            │
                │  TimerScreen · SettingsScreen · AmbientFace  │
                └───────────────▲───────────────┬──────────────┘
                     observes   │               │ start/pause/reset/adjust
                     (snapshot  │               ▼
                      state)  ┌─┴──────────────────────────────┐
                              │        TimerEngine (object)     │
                              │ runSeconds  walkSeconds         │
                              │ isRunning   currentPhase        │
                              │ timeLeft    totalSeconds        │
                              │ phaseEndElapsed (deadline)      │
                              └─▲──────────────────┬───────────┘
                     tick() 5×/s│                  │ startForegroundService /
                                │                  │ stopService
                              ┌─┴──────────────────▼───────────┐
                              │          TimerService           │
                              │  foreground notification        │
                              │  partial wake lock              │
                              │  tick loop → vibrate on switch  │
                              └─────────────────────────────────┘
```

---

## 2. Module map

All code is in `app/src/main/java/com/sient/myrun/presentation/`.

| File | Responsibility | Depends on |
| --- | --- | --- |
| `TimerEngine.kt` | Timer state machine, interval settings, persistence. Pure logic; no UI, no Android services beyond `SharedPreferences` and `Context` for starting/stopping the service. | `TimerService` (starts/stops it) |
| `TimerService.kt` | Foreground service: wake lock, tick loop, vibration, ongoing-activity notification. | `TimerEngine`, `MainActivity` (for the notification's tap intent) |
| `MainActivity.kt` | Compose UI: timer screen, settings screen, ambient face, navigation, notification-permission request, ambient lifecycle. | `TimerEngine` |
| `theme/Theme.kt` | Wraps content in Wear `MaterialTheme`. Currently the default palette. | — |

Non-code files that matter:

| File | Notes |
| --- | --- |
| `AndroidManifest.xml` | Permissions, `singleTask` activity, `specialUse` foreground service declaration. |
| `res/values/styles.xml` | Splash screen theme (`MainActivityTheme.Starting`). |
| `res/drawable/ic_launcher_foreground.xml` | Reused as the notification/ongoing-activity icon. |
| `gradle/libs.versions.toml` | Version catalog. Notable: `compose-material3` is **`androidx.wear.compose`** (Wear Material 3), not the phone Material 3. |

---

## 3. `TimerEngine` — the state machine

`TimerEngine` is a Kotlin `object`, so there is exactly one instance for the process. Every public property is a Compose `mutableStateOf` / `mutableIntStateOf` with a `private set`, so composables that read them recompose automatically and nothing outside the engine can mutate them.

### Observable state

| Property | Meaning |
| --- | --- |
| `runSeconds`, `walkSeconds` | Configured interval lengths. Clamped to `MIN_INTERVAL = 30` … `MAX_INTERVAL = 1800`. |
| `isRunning` | Whether a workout is active (service running, wake lock held). |
| `currentPhase` | `Phase.RUN` or `Phase.WALK`. |
| `timeLeft` | Seconds left in the current phase (display value, rounded **up**). |
| `totalSeconds` | Workout time across phases and pauses. |

### Private timing state

| Field | Meaning |
| --- | --- |
| `phaseEndElapsed` | Absolute `elapsedRealtime()` millisecond at which the current phase ends. **This is the source of truth for the countdown.** |
| `accumulatedMs` | Total workout time from previous run stretches (before the latest pause). |
| `sessionStartElapsed` | `elapsedRealtime()` when the current run stretch started. |

`SystemClock.elapsedRealtime()` is used everywhere instead of `System.currentTimeMillis()` because it is monotonic — it keeps counting through deep sleep and is unaffected by the user changing the clock or NTP corrections.

### The `tick()` algorithm

```kotlin
fun tick(): Boolean {
    if (!isRunning) return false
    var switched = false
    val now = SystemClock.elapsedRealtime()
    while (phaseEndElapsed <= now) {          // catch up on ALL missed phases
        currentPhase = /* flip */
        phaseEndElapsed += nextPhaseSeconds * 1000L
        switched = true
    }
    timeLeft = ceil((phaseEndElapsed - now) / 1000)
    totalSeconds = (accumulatedMs + (now - sessionStartElapsed)) / 1000
    return switched
}
```

Points worth internalising:

- **It is idempotent and catch-up safe.** Calling `tick()` once after a 10-minute freeze produces exactly the same state as calling it 3000 times over those 10 minutes. The `while` loop advances through as many phases as were missed.
- **It returns `true` at most once per call even if several phases were skipped.** The caller (the service) buzzes once. A pile-up of vibrations after a freeze would be confusing.
- **`timeLeft` rounds up** (`+ 999` before integer division). With a 1-second display granularity this means the screen shows `1:00` at the very start of a phase and `0:01` right before the flip, never a lingering `0:00`.
- **The service ticks at 200 ms**, well under the 1-second display granularity, so the phase flip and buzz happen within ~200 ms of the true deadline and the displayed seconds never visibly skip.

### Start / pause / reset

- `start()` — sets `isRunning`, computes `phaseEndElapsed = now + timeLeft`, anchors `sessionStartElapsed`, and calls `startForegroundService(TimerService)`. Because the deadline is computed from the *current* `timeLeft`, resuming after a pause continues exactly where it left off.
- `pause()` — folds the current stretch into `accumulatedMs`, clears `isRunning`, and calls `stopService`. `timeLeft` is left as-is so the UI keeps showing the paused value.
- `reset()` — pause, then back to `RUN` / `runSeconds` / zero total.
- `adjustRunSeconds` / `adjustWalkSeconds` — clamp, persist, and if the timer is idle and currently showing that phase, update `timeLeft` so the change is visible immediately. Changing an interval **during** a workout does not affect the phase in progress; the new value applies from the next phase of that type.

---

## 4. `TimerService` — running in the background

### Why a foreground service is needed

Wear OS is aggressive about background work. When the user lowers their wrist or presses the crown, the app goes to the watch face and the system **freezes the app process** (cached-app freezer). A frozen process doesn't run coroutines, doesn't get `delay()` wake-ups, and — crucially — has its wake locks disabled. The app's timer simply stops until it is reopened.

A **foreground service** is exempt from freezing. It's the mechanism the platform provides for "the user started something and expects it to keep going". The cost is a persistent notification, which on Wear OS is actually a feature (see Ongoing Activity below).

### What the service does

`TimerService.onStartCommand()` performs four things, all idempotent so repeated `startForegroundService` calls are harmless:

1. **`startForeground(NOTIFICATION_ID, buildNotification())`** — must be called within a few seconds of `startForegroundService()` or the system kills the app. It's the first line for that reason.
2. **Acquire a partial wake lock** (`PowerManager.PARTIAL_WAKE_LOCK`). A partial wake lock keeps the *CPU* awake while letting the screen turn off. Without it, once the screen sleeps the CPU can be suspended between ticks and the loop stalls. The lock is:
   - `setReferenceCounted(false)` — one `release()` always fully releases it regardless of how many times `acquire()` ran. Guards against leaking a lock when `onStartCommand` is invoked more than once.
   - Acquired with a **90-minute timeout** (`MAX_WORKOUT_MS`). If the user forgets to stop the timer, the lock self-releases and battery drain is bounded. The number is "longest plausible run plus margin"; raise it if you run marathons.
3. **Launch the tick loop** on `Dispatchers.Main.immediate` in a `SupervisorJob` scope: every 200 ms call `TimerEngine.tick()`, and if it returns `true`, vibrate for the new phase. Main dispatcher is used because `TimerEngine` writes Compose snapshot state and the UI reads it; keeping writes on the main thread avoids snapshot-conflict subtleties.
4. Return **`START_NOT_STICKY`**. If the system ever kills the service, we do *not* want it recreated with a null intent — the engine's in-memory deadline would be gone and it would just tick a stale state. Let it stay dead; the user restarts the timer.

`onDestroy()` cancels the scope and releases the lock. The service's lifetime is exactly the workout's lifetime: `TimerEngine.start()` starts it, `TimerEngine.pause()` (and therefore `reset()`) stops it.

### The Ongoing Activity chip

`buildNotification()` wraps the notification in a `androidx.wear.ongoing.OngoingActivity`. On Wear OS this surfaces as a small chip on the watch face and in the recents/tile list while the service runs; tapping it opens `MainActivity`. It is the Wear-idiomatic way to get back into a running workout — without it the user has to go through the app launcher.

The notification channel uses `IMPORTANCE_LOW` so it never makes a sound or heads-up; the vibrator is driven directly and separately.

### Foreground service type

Android 14+ requires every foreground service to declare a type. None of the standard types (`health`, `mediaPlayback`, …) fit an interval timer, so the manifest uses `specialUse` with the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property (value `interval_timer`). `FOREGROUND_SERVICE_SPECIAL_USE` is the matching permission. If you publish to Play you will be asked to justify this in the console; the answer is "user-initiated workout timer that must vibrate on schedule while the screen is off".

`health` would be the more "correct" type but requires `BODY_SENSORS`/activity-recognition permissions the app doesn't need.

---

## 5. Vibration

Vibration lives entirely in `TimerService.vibrateForPhase()` and fires only from the tick loop when `tick()` reports a switch. Nothing in the UI vibrates.

### Getting the vibrator

```kotlin
val vibrator = if (Build.VERSION.SDK_INT >= 31)
    (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
else
    getSystemService(VIBRATOR_SERVICE) as Vibrator   // deprecated, still needed for API 30
```

`minSdk` is 30 (Wear OS 3) which predates `VibratorManager`, hence the branch. Any `Context` works — the service's own is used, so no Activity needs to be alive.

### The two patterns

| Switching to | Effect | Rationale |
| --- | --- | --- |
| **RUN** | `createWaveform([0, 300, 150, 300], -1)` — two 300 ms buzzes with a 150 ms gap, no repeat | "Go, go" — distinct double pulse tells you to speed up without looking. |
| **WALK** | `createOneShot(700, DEFAULT_AMPLITUDE)` — one long buzz | "Ease off" — a single sustained pulse. |

The waveform array alternates *off, on, off, on…* durations in milliseconds; the leading `0` means "start immediately". `-1` means don't repeat.

Because the patterns differ, the user never has to look at the watch to know which phase just started. If you change them, keep them distinguishable by feel.

### Why it's reliable with the screen off

The vibrator is triggered from inside the foreground service while the partial wake lock is held, so the call is made by a process that is neither frozen nor CPU-suspended. `VIBRATE` permission is declared in the manifest; it's a normal permission, no runtime prompt.

---

## 6. `MainActivity` — UI, navigation, ambient mode

### Setup in `onCreate`

1. `TimerEngine.load(this)` — loads saved intervals (no-op after the first call).
2. Registers the `AmbientLifecycleObserver` (see below).
3. On API 33+, requests `POST_NOTIFICATIONS` via `registerForActivityResult`. Without the grant, the foreground service still runs but its notification/ongoing chip is hidden. The result is ignored; the app works either way.
4. `setContent { MyRunApp(isAmbient, burnInProtection) }`.

`launchMode="singleTask"` in the manifest guarantees one Activity instance. Multiple instances caused a real bug (see §10) back when state lived in a ViewModel; it's kept as belt-and-braces now that state is a singleton.

### Screens

`MyRunApp` picks one of two trees:

- **Interactive** — `AppScaffold` + `SwipeDismissableNavHost` with two routes:
  - `"timer"` → `TimerScreen` — phase label and countdown in the phase colour (`primary` for RUN, `tertiary` for WALK), the configured intervals, total time, and Start/Pause · Reset · Intervals buttons. `Modifier.padding(top = 22.dp)` keeps content clear of the system time indicator at the top of the round display.
  - `"settings"` → `SettingsScreen` — two `IntervalStepper` rows (−/+ in 30 s steps). Swipe right to dismiss, as is standard on Wear.
- **Ambient** — `AmbientTimerScreen`, rendered instead of the nav host whenever `isAmbient` is true.

All screens read `TimerEngine` state directly. There is no ViewModel and no state hoisting beyond the engine; this is deliberate for an app this size.

### Ambient mode

On Wear OS, when the screen dims the system normally replaces the app with the watch face. Registering an `AmbientLifecycleObserver` (from `androidx.wear:wear`) opts the Activity into staying visible in a low-power "ambient" state instead.

The callback flips two `mutableStateOf` flags held in the Activity and passed into Compose:

- `isAmbient` — switches the whole tree to `AmbientTimerScreen`: pure black background, dim greys, larger countdown, no buttons, and a "paused" hint if the timer isn't running. Fewer lit pixels = less OLED power.
- `burnInProtection` — if the hardware asks for it, the text block shifts vertically by `((totalSeconds/60) % 3 − 1) × 6 dp`, i.e. it drifts −6 / 0 / +6 dp on a minute cycle so static pixels don't burn in. (`coerceAtLeast(0)` on the padding means the −6 case renders as 0; the visible effect is a 0/0/6 pattern. Harmless, but note it if you touch this.)

`onUpdateAmbient()` is intentionally empty: the system calls it roughly once a minute to let ambient apps refresh, but this app's state is already being updated 5×/s by the service and Compose recomposes on its own, so nothing extra is needed. Note the ambient screen does **not** keep the timer running — that's the service's job. The ambient face just makes it visible.

---

## 7. Persistence

Only the two interval lengths are persisted, in `SharedPreferences` (`myrun_prefs`, keys `run_seconds` / `walk_seconds`), written on every adjustment via `apply()` and read once in `TimerEngine.load()`.

Runtime state (`isRunning`, phase, deadline, total) is **not** persisted. If the process dies mid-workout — which the foreground service makes very unlikely — the timer is lost and the app starts idle. This is a conscious simplification; see §11 if you want to change it.

---

## 8. Manifest, permissions, and build

### Permissions

| Permission | Why |
| --- | --- |
| `WAKE_LOCK` | Partial wake lock in the service. |
| `VIBRATE` | Phase-change buzzes. |
| `FOREGROUND_SERVICE` | Required to call `startForeground` (API 28+). |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required for the `specialUse` service type (API 34+). |
| `POST_NOTIFICATIONS` | Runtime permission on API 33+ so the ongoing notification is shown. |

`<uses-feature android:name="android.hardware.type.watch" />` and the `com.google.android.wearable.standalone = true` meta-data mark this as a standalone watch app with no phone companion.

### Build notes

- `compileSdk`/`targetSdk` 37, `minSdk` 30, Kotlin 2.2, Compose BOM 2024.09, Wear Compose Material 3 1.5.x.
- `androidx.fragment:fragment` is pinned explicitly (1.8.5). A 1.2.x version came in transitively and tripped the `InvalidFragmentVersionForActivityResult` **lint-vital** check, which blocks release builds even though the app never uses fragments. Do not remove the pin.
- The `release` build type signs with the **debug keystore** (`signingConfigs.getByName("debug")`) so a release APK can be sideloaded over the debug one. Replace with a real signing config before publishing.
- Release has `optimization { enable = false }` — no R8. The APK is ~22 MB. Enabling R8 would need keep rules for the Wear ongoing-activity library; not yet done.

```sh
./gradlew assembleDebug            # or assembleRelease
adb -s <serial> install -r app/build/outputs/apk/<variant>/app-<variant>.apk
```

---

## 9. Lifecycle walkthroughs

### User taps Start

1. `TimerScreen` → `TimerEngine.toggle(context)` → `start()`.
2. Engine sets `isRunning = true`, computes `phaseEndElapsed`, calls `startForegroundService`.
3. `TimerService.onStartCommand`: `startForeground` with the ongoing-activity notification, acquire wake lock, start 200 ms tick loop.
4. Each tick updates `timeLeft`/`totalSeconds` → Compose recomposes the countdown.

### Phase ends while the watch is on the watch face

1. App process is *not* frozen because a foreground service is running; CPU is awake because of the wake lock.
2. A tick finds `phaseEndElapsed <= now`, flips phase, returns `true`.
3. Service vibrates with the pattern for the new phase.
4. User raises wrist, taps the ongoing chip → `MainActivity` (single instance) comes to front already showing the new phase.

### Screen dims while the app is open

1. `AmbientLifecycleObserver.onEnterAmbient` → `isAmbient = true` → `AmbientTimerScreen`.
2. Service keeps ticking; ambient face keeps recomposing from engine state.
3. Wrist raise → `onExitAmbient` → interactive tree returns, on the same route it left.

### User taps Pause, then later Start

1. `pause()` records elapsed time into `accumulatedMs`, `stopService` → `onDestroy` releases wake lock and cancels ticks. `timeLeft` freezes at, say, `0:42`.
2. `start()` computes a new deadline `now + 42 s` and restarts the service. Total time continues from where it was.

### Process death

Service is `START_NOT_STICKY`, engine state is in-memory only → next launch starts idle with saved intervals.

---

## 10. History of problems and their fixes

These are the bugs that shaped the architecture. Understanding them prevents reintroducing them.

| Symptom | Cause | Fix | Commit |
| --- | --- | --- | --- |
| Timer paused when screen dimmed; had to keep screen on. | App replaced by watch face on dim. | `AmbientLifecycleObserver` + dedicated ambient face; drop `keepScreenOn`. | `f05f1b0` |
| In ambient, countdown "ran fast" and buzzes came late. | CPU frozen between ticks; on wake the loop caught up in a burst. | Partial wake lock while running; deadline-based `tick()` that catches up any number of phases but buzzes once. | `865c1a9` |
| Wake lock leaked after Reset; battery drain. | Launching via adb created a second Activity/ViewModel instance holding its own lock. | `launchMode="singleTask"`; non-reference-counted wake lock. | `5546f8d` |
| Timer stopped once user returned to the watch face, even with wake lock. | System froze the app process; frozen apps' wake locks are disabled. | Move ticking, vibration and wake lock into a foreground service; replace ViewModel with `TimerEngine` singleton; ongoing-activity chip. | `4d54b52` |
| Forgotten timer drained battery for hours. | 4 h wake-lock timeout. | 90 min timeout. | `3ac390e` |
| Release build failed lint-vital. | Transitive `fragment:1.2.4` < 1.3.0 required by ActivityResult. | Pin `fragment:1.8.5`. | `56a9aa1` |

The recurring lesson: **on Wear OS, anything that must keep happening after the user drops their wrist has to run in a foreground service with a wake lock, and must compute its state from a monotonic clock rather than from counted ticks.**

---

## 11. Extending the app

Some likely changes and where they go:

- **Different step size / limits for intervals** — `MIN_INTERVAL`, `MAX_INTERVAL` in `TimerEngine`; the `±30` literals in `SettingsScreen`.
- **More than two phases (e.g. warm-up, cool-down)** — replace the `Phase` enum flip in `tick()` with a list of `(phase, seconds)` and an index. `phaseEndElapsed += nextSeconds` already generalises. Add a vibration pattern per phase in `vibrateForPhase`.
- **Survive process death mid-workout** — persist `phaseEndElapsed`, `currentPhase`, `accumulatedMs`, `sessionStartElapsed` and `isRunning` in `SharedPreferences` on every change, restore them in `load()`, and restart the service from `MainActivity.onCreate` if `isRunning` was saved as true. Because the deadline is absolute on `elapsedRealtime()`, this only works across process death, **not** across a reboot (elapsedRealtime resets). Store a `currentTimeMillis` fallback if reboot survival matters.
- **Sound as well as vibration** — add a `MediaPlayer`/`ToneGenerator` call next to `vibrateForPhase`. Keep it in the service.
- **A tile or complication showing the countdown** — read `TimerEngine` from a `TileService`. The service already keeps state fresh; the tile just needs a refresh trigger.
- **Custom colours** — `theme/Theme.kt` currently passes no `colorScheme`; supply one to `MaterialTheme`. The timer screen keys off `primary` (RUN) and `tertiary` (WALK).
- **Enable R8** — set `optimization { enable = true }` in the release block and add keep rules if the ongoing-activity notification stops appearing.

Things to avoid:

- Don't move ticking back into the Activity or a ViewModel — it will stop on the watch face (§10).
- Don't count down with `delay(1000); timeLeft--` — it drifts and breaks after any freeze. Always derive from `phaseEndElapsed`.
- Don't vibrate from the UI on recomposition — you'd buzz on every screen rotation/ambient toggle. Vibrate only from the tick loop's `switched` signal.
- Don't call `startForegroundService` without reaching `startForeground` within ~5 s — the system kills the app with a `ForegroundServiceDidNotStartInTimeException`. `onStartCommand` calls it first for that reason.

---

## 12. Debugging on a real watch

Connect over Wi-Fi debugging (Galaxy Watch: Settings → Developer options → Wireless debugging). The advertised port changes every time the watch sleeps; use mDNS to find the current one:

```sh
adb mdns services                 # shows 192.168.x.x:<port>
adb connect 192.168.x.x:<port>
```

Useful commands:

```sh
# Is the service running / holding a wake lock?
adb shell dumpsys activity services com.sient.myrun
adb shell dumpsys power | grep -A2 MyRun

# Watch the app's logcat only
adb logcat --pid=$(adb shell pidof com.sient.myrun)

# Drive the UI without touching the watch (480×480 display)
adb shell input tap 158 330       # Start / Pause
adb shell input tap 317 330       # Reset
adb shell input tap 240 440       # Intervals
adb shell input swipe 20 240 460 240 300   # swipe-to-dismiss

# Find button coordinates for a different watch
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml

# Screenshots / recording (used for the README)
adb exec-out screencap -p > shot.png
adb shell screenrecord --time-limit 60 /sdcard/demo.mp4
```

To test the "screen off" path realistically: start the timer, press the crown to go to the watch face, lower your wrist, and wait for a phase boundary. If it doesn't buzz, check `dumpsys activity services` first — a missing service means `startForegroundService` failed (look for a `ForegroundServiceStartNotAllowedException` in logcat).
