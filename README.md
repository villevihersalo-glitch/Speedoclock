# Speedoclock

Android home-screen clock widgets styled as vehicle gauges. Your day reads like a dashboard.

| Gauge | Meaning |
|---|---|
| Hours (outer ring / stepped bar) | 0–24 h, fills through the day |
| 60 LEDs / dots | Minutes in the current hour |
| Tachometer needle | Seconds, black → green → yellow → red |
| Gear | **N** outside working hours and weekends, **1–5** through the working day, 5 = TOP GEAR |
| Fuel | Full when your day starts, empty when it ends |
| Headlight lamp | On 18:00–06:00 |

Two faces to start:

- **Chrono** (2×2): chrome bezel, amber 24 h ring, LED minute ring, black tacho dial.
- **LCD** (4×2): 90s motorbike cluster with stepped hour bar, minute dots, big gear digit, work countdown and fuel bars.

Work hours and day start/end are set in the app.

## How it stays cheap on battery

The seconds needle is the system `AnalogClock` second hand and the LCD's `HH:mm:ss` is a `TextClock`, so the launcher animates them, not the app. The rest of each face is a bitmap redrawn once a minute by a non-waking alarm. Allowing "exact alarms" in the app makes the minute flip land exactly on the minute.

## Build

Open in Android Studio and run on a phone with Android 12 or newer. Every push runs a GitHub Actions build that also renders preview PNGs of both faces (artifact **previews**).

## Adding a face

A face is an `object` with `render(state, …)` drawing onto a fixed-size canvas with the hub at the centre, plus a needle vector whose rest rotation matches the face's second-zero angle. See `ChronoFace.kt` / `needle_chrono.xml`.
