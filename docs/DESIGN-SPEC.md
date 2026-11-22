# P3 Companion — UI Design Spec (v2.1 "Polish")

Scope: visual system and component behaviour for the code-built UI (`Ui.kt`, `MainActivity.kt`, `*Screen.kt`).
Constraints: Android framework only (no androidx, no Material Components), minSdk 30, target 35, views built in Kotlin.
XML is used only for vector drawables, themes and the launcher icon.

Migration rule: `Palette` keeps its current field names as aliases of the new roles (section 1.6), so every screen
compiles unchanged on day one and is then moved screen by screen to the new components.

---

## 1. Color system

### 1.1 Tone scale
"Tone" = CIELAB L* (0 black … 100 white). The framework system palettes map to tones like this:

| suffix | 0 | 10 | 50 | 100 | 200 | 300 | 400 | 500 | 600 | 700 | 800 | 900 | 1000 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tone | 100 | 99 | 95 | 90 | 80 | 70 | 60 | 50 | 40 | 30 | 20 | 10 | 0 |

Five palettes: `A1`=accent1 (primary), `A2`=accent2 (secondary), `A3`=accent3 (tertiary), `N1`=neutral1, `N2`=neutral2.
On API 31+ with accent = Dynamic, read them with `context.getColor(android.R.color.system_accent1_600)` etc.
Everywhere else they come from the generator (1.3). The role mapping below is the same for both sources.
`mix(a, b, t)` = per-channel sRGB lerp, `t` = weight of `b`.

### 1.2 Roles (`data class Scheme`)
| role | Light | Dark | Dark + AMOLED |
|---|---|---|---|
| primary | A1_600 | A1_200 | A1_200 |
| onPrimary | A1_0 | A1_800 | A1_800 |
| primaryContainer | A1_100 | A1_700 | A1_800 |
| onPrimaryContainer | A1_900 | A1_100 | A1_100 |
| inversePrimary | A1_200 | A1_600 | A1_600 |
| secondary / onSecondary | A2_600 / A2_0 | A2_200 / A2_800 | same as dark |
| secondaryContainer / on | A2_100 / A2_900 | A2_700 / A2_100 | A2_800 / A2_100 |
| tertiary / onTertiary | A3_600 / A3_0 | A3_200 / A3_800 | same as dark |
| tertiaryContainer / on | A3_100 / A3_900 | A3_700 / A3_100 | A3_800 / A3_100 |
| surface (window bg) | N1_10 | mix(N1_900, N1_1000, 0.4) ≈ T6 | #000000 |
| surfaceContainerLowest | N1_0 | mix(N1_900, N1_1000, 0.6) | #000000 |
| surfaceContainerLow | N1_50 | N1_900 (T10) | mix(N1_900, N1_1000, 0.5) |
| surfaceContainer | mix(N1_50, N1_100, 0.4) | mix(N1_900, N1_800, 0.2) | N1_900 |
| surfaceContainerHigh | mix(N1_50, N1_100, 0.7) | mix(N1_900, N1_800, 0.5) | mix(N1_900, N1_800, 0.3) |
| surfaceContainerHighest | N1_100 | mix(N1_900, N1_800, 0.8) | mix(N1_900, N1_800, 0.6) |
| onSurface | N1_900 | N1_100 | N1_100 |
| onSurfaceVariant | N2_700 | N2_200 | N2_200 |
| outline | N2_500 | N2_400 | N2_400 |
| outlineVariant | N2_200 | N2_700 | N2_800 |
| inverseSurface / inverseOnSurface | N1_800 / N1_50 | N1_100 / N1_800 | same as dark |
| scrim | #000000 @ 32% | #000000 @ 60% | #000000 @ 70% |
| error / onError | #B3261E / #FFFFFF | #F2B8B5 / #601410 | same |
| errorContainer / on | #F9DEDC / #410E0B | #8C1D18 / #F9DEDC | same |
| success / onSuccess / successContainer / on | S_T40 / S_T100 / S_T90 / S_T10 | S_T80 / S_T20 / S_T30 / S_T90 | same |
| warning / onWarning / warningContainer / on | W_T40 / W_T100 / W_T90 / W_T10 | W_T80 / W_T20 / W_T30 / W_T90 | same |

Success seed `#2E9D5B` (hue ≈ 150°), warning seed `#E0A100` (hue ≈ 80°). Both are **harmonized** toward the
primary hue before generating: `d = shortestSignedHueDelta(semantic, primary)`; `h' = semantic + sign(d)·min(|d|·0.5, 15°)`.
Chroma for S/W palettes: 40. Error stays fixed (never harmonized) so "danger" reads the same in every accent.

**High contrast** option (Settings › Look › Contrast = High): onSurfaceVariant := onSurface; outline := N2_600 light /
N2_200 dark; outlineVariant := outline; primaryContainer/onPrimaryContainer swap to primary/onPrimary.

### 1.3 Fallback / preset generator (framework-only, unit-testable)
`object Tonal { fun palette(hue: Double, chroma: Double): (tone: Int) -> Int }` in a new `Color.kt` (pure Kotlin, no
android imports except `@ColorInt Int` packing, so JVM unit tests run it).

1. Seed sRGB → linear: `c ≤ 0.04045 ? c/12.92 : ((c+0.055)/1.055)^2.4`.
2. Linear → XYZ (D65):
   `X = 0.4124564R + 0.3575761G + 0.1804375B`, `Y = 0.2126729R + 0.7151522G + 0.0721750B`, `Z = 0.0193339R + 0.1191920G + 0.9503041B`.
3. XYZ → Lab with white `Xn=0.95047, Yn=1, Zn=1.08883`; `ε = 216/24389`, `κ = 24389/27`;
   `f(t) = t > ε ? cbrt(t) : (κt+16)/116`; `L = 116 f(Y) − 16`, `a = 500(f(X/Xn) − f(Y))`, `b = 200(f(Y) − f(Z/Zn))`.
4. LCh: `C = hypot(a,b)`, `h = atan2(b,a)` in degrees, normalised to [0,360).
5. **colorAt(T, h, Cwanted)**: binary-search chroma `c ∈ [0, Cwanted]`, 24 iterations: build Lab(T, c·cos h, c·sin h) →
   XYZ (`fy=(L+16)/116, fx=fy+a/500, fz=fy−b/200`, `finv(t)= t³>ε ? t³ : (116t−16)/κ`, multiply by white) → linear RGB
   (`R = 3.2404542X − 1.5371385Y − 0.4985314Z`, `G = −0.9692660X + 1.8760108Y + 0.0415560Z`, `B = 0.0556434X − 0.2040259Y + 1.0572252Z`)
   → in gamut if every channel ∈ [−0.0001, 1.0001]. Keep the largest in-gamut c. Clamp, gamma-encode
   (`c ≤ 0.0031308 ? 12.92c : 1.055c^(1/2.4) − 0.055`), round to 0..255. Tone 0 → #000000, tone 100 → #FFFFFF exactly.
6. Palette chromas from seed (h, C):
   - A1: hue h, chroma `C < 15 ? C : max(C, 48)` (keeps Graphite grey-blue, makes others vivid)
   - A2: hue h, chroma 16 · A3: hue h+60°, chroma 28 · N1: hue h, chroma 4 · N2: hue h, chroma 8
7. Tones generated: 0,10,50,100,…,900,1000 suffixes (tones in 1.1) plus S/W T10/20/30/40/80/90/100.
   Cache the 13×5 table per (seed, mode) in a `LruCache`-free `HashMap` (max 9 seeds).

Unit tests (`TonalTest`): (a) for 6 seeds × all tones, `abs(labL(color) − tone) ≤ 0.6`; (b) every role pair in 1.4 passes;
(c) `palette(h, 0)` returns neutral greys (|a|,|b| < 1); (d) hue wrap: h=359 and h=−1 equal.

### 1.4 Contrast (WCAG 2.x) — `fun contrast(fg: Int, bg: Int): Double`
`lum(c) = 0.2126·R + 0.7152·G + 0.0722·B` on linearised channels; `ratio = (max+0.05)/(min+0.05)`.
Asserted in tests for light, dark, AMOLED and high-contrast, for every preset seed and a fake "wallpaper" seed set:
- ≥ 4.5: onPrimary/primary, onPrimaryContainer/primaryContainer, onSecondaryContainer/secondaryContainer,
  onTertiaryContainer/tertiaryContainer, onSurface/every surface*, onSurfaceVariant/surface…surfaceContainerHigh,
  onError/error, onErrorContainer/errorContainer, success/surface, warning/surfaceContainer (text), inverseOnSurface/inverseSurface.
- ≥ 3.0: primary/surface, outline/surface (component boundaries), white/scrim-over-mid-grey (live overlay, see 3.15).
Runtime guard `ensureContrast(fg, bg, min)`: if it fails (odd wallpaper), step fg tone ±5 away from bg (up to 8 steps)
via the generator. Log once to the connection log as `UI: adjusted <role>`.

### 1.5 Accent choices (Settings › Look › Accent)
| name | seed | note |
|---|---|---|
| Dynamic | wallpaper | API 31+ only; hidden on API 30 (default there = Violet) |
| Violet | #8B5CF6 | the 2.0 brand color; default on API 30 |
| Ocean | #0EA5E9 | |
| Emerald | #10B981 | |
| Sunset | #F97316 | |
| Rose | #F43F5E | |
| Graphite | #64748B | low chroma path |
| Custom | hue slider 0–359, chroma 60 | new option |
Shown as a row of 48 dp swatches (circle split: top half primary T40/T80, bottom-left secondaryContainer, bottom-right
tertiaryContainer); selected swatch gets a 3 dp primary ring + check. **Pure black (AMOLED)** switch appears only when
the effective mode is dark. Theme change calls `recreate()` with a 150 ms crossfade (window animation `android.R.anim.fade_in/out`).
Wallpaper changes on 12+ recreate the activity by themselves (the app does not override `onConfigurationChanged`).

### 1.6 Palette alias (compatibility)
`Palette(bg=surface, card=surfaceContainerLow, cardAlt=surfaceContainerHigh, text=onSurface, muted=onSurfaceVariant,
accent=primary, onAccent=onPrimary, good=success, warn=warning, bad=error, line=outlineVariant)` plus `isDark`.
`MainActivity` must use `scheme.isDark` instead of `ui.p == Palette.LIGHT`. Status bar = transparent (edge to edge),
icons light/dark by `isDark`; nav bar = surfaceContainer (matches bottom nav).

Theme XML: `values/themes.xml` parent `@android:style/Theme.DeviceDefault.DayNight` + `windowNoTitle`, `windowActionBar=false`,
`windowBackground #FDFBFF`; `values-night/` `#0F0D13`; `values-v31` and `values-night-v31` set `windowBackground` to
`@android:color/system_neutral1_10` / `system_neutral1_900` and `windowSplashScreenAnimatedIcon=@drawable/ic_glasses`,
`windowSplashScreenBackground` the same as the window background, so there is no flash before the first frame.

---

## 2. Type, spacing, shape, elevation

### 2.1 Type scale (sp / line height sp / weight)
| token | size | line | weight | used for |
|---|---|---|---|---|
| displaySmall | 36 | 44 | 400 | battery % in hero (tabular) |
| headlineMedium | 28 | 36 | 400 | screen title (collapsed top bar large) |
| headlineSmall | 24 | 32 | 400 | dialog titles, empty-state titles, device name |
| titleLarge | 22 | 28 | 400 | top app bar title |
| titleMedium | 16 | 24 | 500 | card headings, list primary |
| titleSmall | 14 | 20 | 500 | section labels |
| bodyLarge | 16 | 24 | 400 | main body, chat text |
| bodyMedium | 14 | 20 | 400 | secondary text, hints |
| bodySmall | 12 | 16 | 400 | captions, timestamps |
| labelLarge | 14 | 20 | 500 | buttons, chips |
| labelMedium | 12 | 16 | 500 | nav labels, badges |
| labelSmall | 11 | 16 | 500 | overline, REC pill |
| mono | 12 | 18 | 400 | logs (`Typeface.MONOSPACE`) |
Weights via `Typeface.create(Typeface.DEFAULT, weight, false)` (API 28+) — picks One UI Sans on Samsung, Roboto elsewhere.
Line height via `TextView.setLineHeight(px)` computed from sp (so it scales). Numbers that change (battery, timers,
counts) use `fontFeatureSettings = "tnum"`. **Text size option**: Small 0.9 / Default 1.0 / Large 1.15 / Largest 1.3,
multiplied into every sp value (on top of the system font scale). Implement once in `Ui.text(token)`.

### 2.2 Spacing (4 dp grid)
`xs 4, s 8, m 12, l 16, xl 24, xxl 32, xxxl 48`. Screen gutter 16; card padding 16 (hero 20); gap between cards 12;
gap between stacked buttons 8; section label to content 8; bottom of scroll content 24 + nav height clearance.

### 2.3 Shape
`xs 4` (progress), `s 8` (chips, thumbnails in grid), `m 12` (inputs, stat tiles, snackbar), `l 16` (cards),
`xl 24` (hero card, live-view frame), `xxl 28` (dialogs, sheet top corners), `full` (buttons, nav pill, FAB-like).
Shape language: soft rectangles for containers, pills for actions, circles only for capture controls and avatars.
Gallery cells use 8 dp with 4 dp gaps for a dense, photo-first grid.

### 2.4 Elevation = tone, not shadow
L0 surface (screen) · L1 surfaceContainerLow (filled cards) · L2 surfaceContainer (bottom nav, inputs) ·
L3 surfaceContainerHigh (top bar when content is scrolled under it, menus, tonal tiles) · L4 surfaceContainerHighest
(dialogs, bottom sheets, snackbar is inverseSurface). Real shadows (`elevation` + oval `outlineProvider`) only on the
shutter (6 dp) and the live-view controls (no shadow on AMOLED).

---

## 3. Components (all in `Ui.kt`; each takes the `Scheme`)

**3.1 Top app bar** — 64 dp, surface; becomes surfaceContainerHigh (150 ms color animation) when the screen's
ScrollView `scrollY > 0` (`setOnScrollChangeListener`). Leading 48 dp back icon on Diagnostics/Live; title titleLarge,
`setAccessibilityHeading(true)`; up to 2 trailing icon buttons (3.5). Replaces `ui.title(...)` + the "Close" buttons.

**3.2 Bottom navigation** — 80 dp, surfaceContainer, 5 equal items (Import, Gallery, Glasses, Assistant, Settings).
Item: 64×32 indicator pill (radius 16) behind a 24 dp vector icon, 4 dp gap, labelMedium label (1 line, ellipsize).
Active: pill secondaryContainer, icon onSecondaryContainer (filled icon variant), label onSurface weight 600.
Inactive: no pill, icon + label onSurfaceVariant (outlined variant). Pill animates in: `scaleX 0.5→1, alpha 0→1`,
200 ms emphasized-decelerate; outgoing pill fades 100 ms. Badges: 6 dp error dot on Glasses when disconnected while
a saved pair exists; on Import a small pill with the count of new files while an import runs (labelSmall, error/onError).
Whole item is the touch target (≥ 48×48), ripple unbounded radius 32 dp, color onSurface @12%.

**3.3 Cards** — Filled: surfaceContainerLow, radius 16, no stroke (default). Outlined: surface + 1 dp outlineVariant
(tips, "How to connect"). Elevated-look: surfaceContainerHigh (stat tiles, assistant answer). Clickable cards get a
ripple mask + press scale 0.98. Card heading = titleMedium + optional 20 dp leading icon in primary.

**3.4 Buttons** — visual height 40 dp inside a 48 dp touch box (`InsetDrawable` 4 dp top/bottom), radius full,
labelLarge, horizontal padding 24 (16 with icon), leading icon 18 dp + 8 dp gap.
| kind | container | content |
|---|---|---|
| Filled | primary | onPrimary |
| Tonal | secondaryContainer | onSecondaryContainer |
| Outlined | transparent + 1 dp outline | primary |
| Text | transparent | primary (padding 12) |
| Danger (filled / tonal) | error / errorContainer | onError / onErrorContainer |
Disabled: container onSurface @12%, content onSurface @38% (replaces `alpha = .5f`). Ripple = content color @12%.
Width: only the main call to action of a card is full width; secondary actions sit in a wrapping row (`ui.buttonRow`,
a horizontal LinearLayout that falls back to vertical when the summed width exceeds the card).
Mapping from 2.0: `primaryButton`→Filled, `secondaryButton`→Tonal (in cards) or Text (minor links), `dangerButton`→Danger tonal.

**3.5 Icon buttons** — 48 dp touch, 40 dp visual circle, icon 24 dp. Standard (onSurfaceVariant, no container),
Filled, Tonal, Outlined. Toggle variant shows filled icon + container when on. Always set `contentDescription`.

**3.6 Chips** — 32 dp visual / 48 dp touch, radius 8, labelLarge. Filter chip unselected: 1 dp outlineVariant, text
onSurfaceVariant; selected: secondaryContainer fill, onSecondaryContainer text, 18 dp leading check that grows in
(width 0→18, 150 ms). Assist chip (suggested prompts in Assistant, quick fixes): surfaceContainerLow + outline,
leading icon in primary. `choiceRow` becomes a **segmented button** when ≤ 4 options fit (40 dp, outline, first/last
pill ends, selected segment secondaryContainer + check), otherwise a scrolling filter-chip row (current behaviour).

**3.7 Switch** — framework `Switch` with M3 tints: track checked primary / unchecked surfaceContainerHighest with
2 dp outline (custom `GradientDrawable` 52×32 radius 16 as `trackDrawable`); thumb checked onPrimary 24 dp,
unchecked outline 16 dp (two oval drawables in a `StateListDrawable` with size set via `setSize`). Whole row toggles.

**3.8 Slider** — `SeekBar`: progressTint primary, progressBackgroundTint secondaryContainer, 6 dp track
(`minHeight/maxHeight`), thumb 20 dp primary oval. Discrete sliders show tick dots (onSurfaceVariant @38%).
Hue slider (Custom accent): track = horizontal `GradientDrawable` of 7 stops (hue 0,60,…,360 at tone 60, chroma 48).

**3.9 Progress** — Linear: 4 dp, radius 2, indicator primary, track secondaryContainer, 4 dp gap at the indicator
end (draw as two layers in a `LayerDrawable`). Indeterminate: framework indeterminate with `indeterminateTintList`
primary. Import progress shows "12 of 48 · 380 MB" (bodyMedium, tnum) above the bar and the file name below (bodySmall, ellipsize middle).

**3.10 Snackbar** — replaces `Toast` (`a.toast` keeps its signature). Lives in a FrameLayout overlay above the nav bar
(16 dp side margins, 8 dp above nav). inverseSurface, radius 12, min 48 dp, bodyMedium inverseOnSurface, optional action
(labelLarge, inversePrimary). In: translationY 24 dp→0 + fade 250 ms decelerate; out: 150 ms accelerate. 4 s, 7 s with an
action; a new one replaces the old. Calls `announceForAccessibility(text)`; with TalkBack on, duration ×2.

**3.11 Dialogs and bottom sheets** — `android.app.Dialog` with a custom view, `window.setBackgroundDrawable(transparent)`,
`window.setDimAmount` matching scrim. Dialog: surfaceContainerHigh, radius 28, padding 24, optional 24 dp icon in
secondary centred, headlineSmall title, bodyMedium text onSurfaceVariant, text buttons right-aligned (destructive
confirm uses error text). Bottom sheet: gravity BOTTOM, width MATCH, top radius 28, 32×4 drag handle onSurfaceVariant
@40% centred 16 dp from top, slide-up 300 ms emphasized-decelerate (`translationY` on the content), dismiss by
swipe-down > 25% height or tap scrim. Used for: gallery item actions, language pickers, accent picker, "Pair new glasses" scan list.
`AlertDialog`/`ProgressDialog` in MainActivity are replaced by `ui.dialog(...)` and a sheet with indeterminate progress.

**3.12 Empty states** — centred column, 32 dp top padding: 96 dp circle surfaceContainerHigh with a 48 dp icon
onSurfaceVariant; headlineSmall title; bodyMedium onSurfaceVariant (max 2 lines, centred); one Filled button.

**3.13 Glasses hero card** (top of Glasses tab, compact version on Import) — radius 24, padding 20, background
vertical-diagonal `GradientDrawable(TL_BR, [primaryContainer, mix(primaryContainer, tertiaryContainer, 0.6)])`;
all content onPrimaryContainer. Layout: row [Battery ring 96 dp] [column: device name headlineSmall, firmware
bodySmall @70%, status chip, signal bars]. Below: 3 stat tiles (photos, videos, recordings): icon 20 dp + number titleMedium tnum + label bodySmall,
on onPrimaryContainer @8% fills, radius 12, combined for TalkBack ("128 photos").
- **Battery ring** (`RingView : View`, `onDraw` with `Canvas.drawArc`): stroke 8 dp, round caps, start −90°; track
  onPrimaryContainer @16%; arc color success ≥ 50%, warning 20–49%, error < 20% (use the T40/T80 role for the mode).
  Centre: `%` displaySmall-tnum, below it a 16 dp bolt icon when charging. Value change animates sweep 600 ms
  decelerate; charging adds a 2 s looping highlight arc (20° wide, alpha 0.5) — off under reduced motion. Unknown battery = dashed track + "—".
- **Signal bars** 4 bars 3×(4,7,10,13) dp, gap 2; lit = onPrimaryContainer, unlit @24%. Source: new `XkGlasses.rssi`
  from `BluetoothGatt.readRemoteRssi()` every 5 s while READY (≥ −60 → 4, ≥ −70 → 3, ≥ −80 → 2, else 1). Hidden when null.
- **Status chip**: IDLE "Not connected" (outline, onSurfaceVariant dot); CONNECTING "Connecting…" (warningContainer,
  dot pulses alpha 1↔0.3 every 800 ms); READY "Connected" (successContainer + check icon); BUSY "Working…" (secondaryContainer + 12 dp spinner).
  `accessibilityLiveRegion = POLITE`. Last error appears as a 1-line errorContainer banner under the hero with a "Details" text button.

**3.14 Capture controls** — a centred row: [Snapshot/Last-photo thumbnail 48 dp, radius 12] [Shutter] [Record].
- **Shutter**: 80 dp. Outer ring 4 dp onSurface (white on live view), 6 dp gap, inner disc 64 dp primary. Press:
  inner disc scale 0.88 in 100 ms; release: back with `OvershootInterpolator(2f)` 200 ms. On photo accepted: a 16 dp
  success check fades over the disc for 600 ms and the thumbnail updates with a 250 ms scale-in. Disabled (not ready):
  disc onSurface @12%, ring @38%, `stateDescription = "Unavailable, glasses not connected"`.
- **Record button**: 64 dp, ring 3 dp outline; inner = error-colored circle 24 dp. Recording: inner morphs to a
  22 dp rounded square radius 6 (animate `cornerRadius` + size 200 ms), ring turns error, and a timer pill
  "● 00:12" (labelLarge tnum, errorContainer/onErrorContainer, dot blinks 1 Hz) appears above the controls.
  Two record modes keep separate buttons: "Record to phone" (live view) and "Record on glasses" (Glasses tab, tonal
  toggle button with videocam icon) — never the same control, so the user always knows where the file lands.

**3.15 Live-view overlay** — TextureView inside a FrameLayout (16:9 by stream size), radius 24 via
`clipToOutline` + rounded `ViewOutlineProvider`, background #000. Overlays (always white text/icons, independent of theme):
top scrim `GradientDrawable(TOP_BOTTOM, [#99000000, #00000000])` 72 dp with: REC pill (top-left, error fill, labelSmall
"REC 00:12"), resolution/fps chip (top-right, #66000000, labelSmall). Bottom scrim 120 dp with the capture-controls row
(3.14) in white variants + a fullscreen icon button. Fullscreen: landscape, `WindowInsetsController.hide(systemBars())`,
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, controls auto-hide after 3 s (tap to show, 200 ms fade). Snapshot = white
flash view alpha 0→0.6→0 over 150 ms (no flash under reduced motion; a check toast-snackbar instead).
Options in an overlay "⋯" sheet: rule-of-thirds grid (1 dp white @35%), keep screen on, mirror off/on.
Status inside the frame when no frames yet: indeterminate circular + "Waiting for the camera…" (white bodyMedium).

---

## 4. Icons

Vector drawables `res/drawable/ic_*.xml`, viewport 24, `android:fillColor="#FF000000"`, tinted at runtime with
`drawable.mutate().setTint(role)`. Paths below are Material Icons (Apache 2.0) — copy verbatim.

| icon | use | pathData |
|---|---|---|
| ic_download | Import tab | `M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z` |
| ic_photo_library | Gallery tab | `M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4zM2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z` |
| ic_auto_awesome | Assistant tab | `M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z` |
| ic_settings | Settings tab | `M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z` |
| ic_glasses | Glasses tab (custom, stroke) | `M2,9.5h20 M3.5,9.5v2.5a3.5,3.5 0,0 0,7 0v-2.5 M13.5,9.5v2.5a3.5,3.5 0,0 0,7 0v-2.5` with `strokeWidth=2`, `strokeLineCap=round`, `strokeColor=#FF000000`, no fill; filled variant adds `fillColor` on the two lens sub-paths |
| ic_photo_camera | shutter labels, take photo | `M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z` + `M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0` |
| ic_videocam | record on glasses, live | `M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z` |
| ic_mic | voice chat, recorder | `M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z` |
| ic_translate | Assistant › Translate | `M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z` |
| ic_swap_horiz | swap languages | `M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z` |
| ic_bluetooth | connect, pair | `M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z` |
| ic_usb | Import drive status | `M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z` |
| ic_build | Diagnostics | `M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z` |
| ic_refresh | refresh | `M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z` |
| ic_share | share | `M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z` |
| ic_delete | delete | `M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z` |
| ic_arrow_back | top bar back | `M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z` |
| ic_close | close, clear | `M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z` |
| ic_check | chips, success | `M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z` |
| ic_play | video badge in grid | `M8,5v14l11,-7z` |
| ic_stop | stop | `M6,6h12v12H6z` |
| ic_warning | warnings | `M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z` |
| ic_error | errors | `M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z` |
| ic_info | tips | `M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z` |
| ic_bolt | charging (custom) | `M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z` |
Audio items in the gallery use ic_mic on a tertiaryContainer tile instead of the "♪" glyph. Unicode glyphs in the
current tab bar (⇩ ▦ ◉ ✦ ⚙) are removed.

**Launcher icon refinement**: background = vector 108×108 with a linear gradient (`aapt:attr` `<gradient>` 0→108
diagonal) `#2A1B4A → #0F0D13`; foreground = white frame (#F4F1FA), lenses filled with a gradient `#8B5CF6 → #0EA5E9`
plus a 6 dp white specular stroke on the upper-left of each lens (alpha 0.35), camera dot #34D399 with a 1.5 dp
white ring; keep everything inside the 66 dp centre circle (current art spans 24–84: shrink to 27–81). Add a
dedicated `ic_glasses_mono.xml` (single fill #FF000000, lens outlines + frame + dot) for the `<monochrome>` layer —
the current colored foreground renders as a blob in themed-icon mode.

---

## 5. Motion and haptics

Tokens: short2 100, short3 150, short4 200, medium2 300, medium4 400, long2 500 ms.
Easing (`PathInterpolator`): standard (0.2,0,0,1); emphasizedDecelerate (0.05,0.7,0.1,1); emphasizedAccelerate (0.3,0,0.8,0.15).
- **Tab switch (fade-through)**: old view alpha 1→0, 90 ms accelerate; new view alpha 0→1 + scale 0.94→1, 210 ms
  decelerate, starting after 90 ms. Screens are still rebuilt (no state change), only `content` animates.
- **Drill-in (Diagnostics, Live view)**: shared-axis X: new view translationX +32 dp→0, alpha 0→1, 300 ms
  emphasized-decelerate; back reverses with accelerate 200 ms.
- **`a.refresh()` in place** (same tab rebuild): no animation (avoids flicker during state updates).
- **Press**: every clickable gets `RippleDrawable` (content @12%) plus scale 0.96 (buttons/icon buttons) or 0.98
  (cards) on ACTION_DOWN 100 ms, back 150 ms standard, via one `ui.pressable(view)` OnTouchListener that returns false.
- Value changes: battery ring 600 ms; progress bar `setProgress(v, true)`; counts crossfade 150 ms.

Haptics (`view.performHapticFeedback`, gated by Settings › Haptics and the system setting):
| action | constant |
|---|---|
| tab select, chip/segment select | API 34+: `SEGMENT_TICK`; else `CLOCK_TICK` |
| switch on / off | API 34+: `TOGGLE_ON` / `TOGGLE_OFF`; else `CLOCK_TICK` |
| shutter press down | `VIRTUAL_KEY` |
| photo accepted, glasses connected, import finished | `CONFIRM` |
| record start / stop | `GESTURE_START` / `GESTURE_END` |
| connect failed, photo refused, delete denied | `REJECT` |
| gallery long-press | `LONG_PRESS` |
| slider step | API 34+: `SEGMENT_FREQUENT_TICK`; else `CLOCK_TICK` every step |
| voice chat push-to-talk start / end | `GESTURE_START` / `GESTURE_END` |

**Reduce motion** option: Match phone (default; on when `!ValueAnimator.areAnimatorsEnabled()`) / On / Off. When on:
all transitions become 100 ms crossfades, no scale/overshoot/pulse/loop/flash, ring and progress jump to value.

---

## 6. Accessibility

- Touch targets ≥ 48×48 dp everywhere (visual may be smaller; use `InsetDrawable` or `TouchDelegate`).
- `contentDescription` on every icon-only control; decorative icons `importantForAccessibility = NO`.
- `setStateDescription` (API 30): switches ("On"), battery ring ("72 percent, charging"), shutter ("Unavailable,
  glasses not connected"), record ("Recording, 12 seconds"), nav items ("Selected").
- Headings: top-bar title and card headings `setAccessibilityHeading(true)` so TalkBack can jump by heading.
- Live regions (POLITE): connection status chip, import progress line, assistant "listening/thinking" status, snackbar.
- TalkBack order = visual order: top bar → hero → capture controls → cards top to bottom → bottom nav (nav is the
  last sibling in the root; set `accessibilityTraversalAfter` on nav to the content container).
- Stat tiles and list rows are `screenReaderFocusable = true` groups with one combined description.
- Nav item description "Gallery, tab 2 of 5"; selected tab announced via state description.
- Fonts in sp only, multiplied by the Text size option; layouts must hold at 200% system scale: nav labels 1 line
  ellipsized, button rows wrap to vertical, hero switches to vertical stack when width < 360 dp or fontScale ≥ 1.5.
- Color is never the only signal: status chip has text + icon, battery ring has the number, errors have an icon.
- All color pairs pass 1.4 tests, including High contrast and AMOLED.

---

## 7. Per-screen layout

Every tab: top app bar (3.1) + vertically scrolling column, gutter 16, card gap 12, bottom nav (3.2).

**Import** — Bar actions: Settings shortcut to "Import" section (ic_settings). (1) Drive status card: 40 dp tonal
circle with ic_usb (success tint when plugged, onSurfaceVariant when not), title "Glasses plugged in" / "not plugged in",
drive label bodySmall, trailing Text button "Change". (2) Import card: three filter chips Photos/Videos/Recordings
(mirror the settings), big Filled "Import new files" with ic_download; while running → progress block (3.9) +
Tonal "Stop"; when done → success banner "Imported 24 files · 1.2 GB" + Filled "Open gallery". (3) "How to connect" outlined
card collapsed to one line with an expand chevron (state remembered). New option surfaced here: "Delete from glasses after import" toggle mirrored from Settings.

**Gallery** — Bar actions: select (enters multi-select), sort (sheet: newest/oldest, by type). Filter as segmented
button All · Photos · Videos · Audio with counts. Grid: 3 columns (4 in landscape / ≥ 600 dp), 4 dp gaps, radius 8,
square crop; video cells: bottom-left duration pill (#99000000, white labelSmall tnum) + ic_play; audio cells:
tertiaryContainer tile with ic_mic and duration. Date headers ("Today", "Yesterday", "Nov 18") as titleSmall rows
spanning the grid (switch `GridView` to a `ListView` of row-chunks or keep GridView and insert header items with full-span emulation).
Long-press → enters selection (check circle overlay top-right, primary) with a contextual top bar "3 selected" + share/delete.
Tap single item → opens the system viewer (unchanged). Empty state (3.12) with ic_photo_library and "Go to Import".

**Glasses** — Bar actions: refresh (ic_refresh), diagnostics (ic_build). (1) Hero card (3.13). (2) Capture card:
capture-controls row (3.14) — Shutter = take photo, centre-left thumbnail = last photo, Record = toggle record on
glasses; below a Filled full-width "Open live view" (ic_videocam). (3) Voice recorder card: mic icon button 56 dp tonal,
live clock (headlineSmall tnum) while recording, Tonal "Stop". (4) "Key saved — sign in" banner card (primaryContainer)
when needed. (5) Not connected / pairing: empty-state style with Filled "Connect" or "Pair new glasses"; scan results in a bottom sheet
list (name, address bodySmall, "Paired" assist chip). (6) "Good to know" outlined card, collapsible. Disconnect moves to the bar overflow "⋯" sheet.

**Live view** — Bar: back + title "Live view" + overflow (grid, keep screen on, fullscreen). Body: frame (3.15) at top,
then controls row on surface below the frame in portrait (inside the frame in fullscreen). Info line bodySmall
onSurfaceVariant: "Video only — saved to Movies › P3". When the mic blocks streaming: warningContainer banner + Filled
"Stop the mic and open live view".

**Assistant** — Bar actions: AI setup (ic_settings → sheet with provider/model). Segmented button Voice · Translate · Photo with icons.
Voice: conversation as bubbles — user right-aligned primaryContainer radius 20 (4 on bottom-right corner), assistant
left-aligned surfaceContainerHigh (4 on bottom-left), notes centred bodySmall onSurfaceVariant; a large 72 dp circular
push-to-talk mic button (Filled; while listening it shows 3 animated level bars around it, error ring when talking through
glasses) docked at the bottom of the card; partial transcript in bodyLarge italic onSurfaceVariant. Translate: two
language buttons (Outlined, flag-free text) with ic_swap_horiz icon button between; translations as paired rows
(source bodyMedium muted, target bodyLarge). Photo: preview 16:9 radius 16, assist chips for presets, input field
(filled style, surfaceContainerHighest, radius 12 top / 0 bottom with 2 dp primary underline when focused), Filled "Ask".
Answer card elevated-look with a copy icon button.

**Diagnostics** — Bar: back + share (ic_share) + copy. (1) Run card: Filled "Run the checks" with inline circular
progress while running, Outlined "Test without key". (2) Results as rows: 24 dp status icon (ic_check success /
ic_warning warning / ic_error error) + text bodyMedium — parsed from the PASS/WARN/FAIL prefixes. (3) Quick fixes as
assist chips. (4) Connection log: mono on surfaceContainerLowest, radius 12, max height 320 dp with its own scroll, Refresh/Share text buttons.

**Settings** — Grouped list instead of buttons-in-cards: section label titleSmall primary, then rows (56–72 dp)
with leading 24 dp icon, title bodyLarge, supporting bodyMedium, trailing control. Sections and NEW options:
- *Look*: Theme mode (segmented Light · Dark · Match phone), **Accent** (swatch row 1.5), **Pure black** (switch, dark only),
  **Contrast** (Standard · High), **Text size** (4-step slider with live "Aa" preview), **Reduce motion** (Match phone · On · Off),
  **Haptics** (switch), Open app on (segmented).
- *Import*, *Glasses*, *Assistant*, *About*: existing options as rows; destructive rows ("Forget these glasses",
  "Forget imported history") at the bottom of their section in error text with confirm dialog.
- About: app icon 48 dp, version, and "Not made by or connected to NovaSight, Shenju or LensMoo."
New Prefs keys: `accent` (dynamic|violet|ocean|emerald|sunset|rose|graphite|custom), `accent_hue` (Int), `amoled` (Bool),
`contrast` (standard|high), `text_scale` (Float), `reduce_motion` (system|on|off), `haptics` (Bool, default true).
`theme` default for new installs becomes SYSTEM; existing saved values are kept.

---

## 8. Build order (for the code specialist)
1. `Color.kt` (Tonal, contrast, Scheme builder) + `TonalTest`/`SchemeContrastTest`. 2. `Palette` alias + themes XML + edge-to-edge bars.
3. `Ui.kt` components (text tokens, buttons, chips, switch, cards, top bar, nav, snackbar, dialog/sheet, pressable, haptics).
4. Icons. 5. Bottom nav + transitions in MainActivity. 6. Glasses hero + capture controls + RingView. 7. Live view overlay.
8. Gallery grid + selection. 9. Assistant bubbles. 10. Settings rows + new options. 11. Launcher icon. 12. TalkBack pass at 200% font.
