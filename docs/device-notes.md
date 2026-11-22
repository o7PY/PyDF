# Device notes — Galaxy Tab S6 Lite (SM-P615, Android 13 / API 33)

Measured 2026-11-22 with the M0 input debug screen (`adb logcat -s Margin`). Screen 1200×2000 px, 240 dpi (1 dp = 1.5 px).

## S Pen
- Tool type `TOOL_TYPE_STYLUS`, source `0x5002`. Never reported as `TOOL_TYPE_ERASER`.
- `touchMajor/Minor` = 0, pressure 0.03–0.66 for normal writing.
- **Side button held at pen-down:** Samsung does NOT send ACTION_DOWN/MOVE/UP. It sends custom actions
  **211 (down), 213 (move), 212 (up)** (214 = cancel by Samsung convention), with `buttonState = 0x20`
  (`BUTTON_STYLUS_PRIMARY`). These are delivered to `View.onTouchEvent`. → `InputRouter` normalises 211/212/213/214
  to DOWN/UP/MOVE/CANCEL and treats them as "button held".
- **Button pressed mid-stroke:** stroke stays a normal DOWN/MOVE/UP; `buttonState` flips to 0x20 in MOVE events.
  → sampling the button at pen-down (SPEC §6.1) works.
- No `ACTION_BUTTON_PRESS/RELEASE` events observed.

## Hover
- Reported reliably. `HOVER_ENTER` when the pen comes in range (~1 cm), `HOVER_EXIT` ~3 ms **before** pen-down,
  `HOVER_ENTER` ~2 ms after pen-up. Button bit (0x20) is visible during hover.
- → hover lockout (SPEC §6.3) usable; a hover-exit immediately followed by touch is just pen-down.

## Fingers
- `TOOL_TYPE_FINGER`, source `0x1002`. Pressure, `size`, `touchMajor` and `touchMinor` are one synthetic value:
  `touchMajor ≈ size × 133 dp`, `pressure == size`. They ramp from 10 dp at down to ~75 dp within ~100 ms for an
  ordinary fingertip. Values are quantised (10, 18.7, 26, … 75.3 dp).
- → **touchMajor is useless for palm detection on this device.** The SPEC's 45 dp limit would reject every
  normal two-finger gesture. Default `maxTouchMajorDp` is set to 0 = disabled (still tunable).
- Two-finger landing gap observed: 3–5 ms.

## Palm
- **The touchscreen firmware suppresses palms entirely**: a resting palm (alone, or while writing) produced no
  events at all, not even a CANCEL. One stray 10 ms FINGER down/up plus a FINGER hover enter/exit pair was seen
  when the palm landed. → the 1-finger-ignore + pairing window + stylus cooldown rules handle such strays.
