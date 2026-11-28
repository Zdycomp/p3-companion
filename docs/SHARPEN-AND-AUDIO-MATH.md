# Sharpen and audio math (spec for 4.1)

Theoretical Physicist's spec for two jobs on the P3 Companion:

1. **Multi-frame super-resolution (SR)**: get real detail by combining several pictures, not by interpolating one.
2. **The glasses-mic voice chain**: clean, level and gate the temple mic for voice chat.

A third part is a short note on what the hardware cannot do.

Everything here is pure Kotlin on `FloatArray`. It has no Android classes in the math and no libraries, so the JVM
tests run the same code as the app, as `ImageEnhance` does today. All values are defaults. Each one is named so it
can be tuned in a single place.

---

## 0. Facts this spec is built on (from the repo and logs)

| Fact | Value | Source |
|---|---|---|
| Bluetooth photo | 640×480 baseline JPEG, 4:2:2 chroma, 54-75 KB, so about **1.6-2.0 bit/pixel** | `FULL-RES-PLAN.md`, `LOG-2026-11-23.txt` |
| Photo cycle time | `57B0` request 05:32:44, shutter `57B1` 05:32:47, `7320` 05:32:48, saved 05:32:50: about **6 s per photo**. Of that, about 1.15 s is the transfer at 48-65 KB/s. The `7500` close then times out after 2 s. | `LOG-2026-11-23.txt` lines 72-90 |
| Live view | 320×240 H.264 at 29.5-30.2 fps, about 390 kbit/s, about 1.6 KB per frame, so about **0.17 bit/pixel** | `PROTOCOL-NOTES.md` §1.4 |
| Bigger sizes | None exist over Bluetooth. Wi-Fi transfer is not available. | `PROTOCOL-NOTES.md` "not feasible" table |
| Glasses IMU | None. Raw sensors (`A000/A001`) are not implemented in the firmware, so motion must come from the pictures. | same |
| Mic stream | Opus SILK-only wideband (TOC config 9), `[0x28][40 B]` packets: 20 ms each, so **16 kbit/s** hard CBR, mono. Decoded by MediaCodec at 48 kHz and resampled to **16 kHz** (`MicCapture.RATE`). | `VoiceRoutes.kt` `OpusFramer`, `GlassesMic.kt` `OpusPcm` |
| Mic plus video together | Refused as busy, so a live burst and the mic cannot run at the same time | `PROTOCOL-NOTES.md` |
| Replies | Played over A2DP to the glasses' speaker, about 2 cm from the mic. Two players: Gemini audio (PCM we hold, played by our own code) and Android `TextToSpeech` (no PCM in our hands). | `HARDWARE-MAP.md` §2, `Speech.kt` |
| Sensor | Unknown on the P3. A sibling's listing says 1 MP (1280×720), so 640×480 is likely a downscale of only about 2× (`HARDWARE-MAP.md`). | (listing, not measured) |
| Existing VAD | `Vad` in `VoiceRoutes.kt`: energy over a tracked floor, 120 ms start, 800-900 ms end | `VoiceRoutes.kt:233` |
| Target phone | moto g 2025: MediaTek Dimensity 6300 (2× Cortex-A76 at 2.4 GHz, 6× A55). We assume about 0.5-1 GFLOP/s per core for ART-JIT scalar Kotlin float loops. | (estimate) |

**What these numbers mean.**

- A burst of Bluetooth photos is **slow**: N = 4 takes about 24 s. Between shots the head drifts by tens of pixels,
  and the scene can change.
- A live burst is **instant**: 16 frames take 0.53 s, and the head is nearly still. But each frame carries about 10×
  fewer bits per pixel, and H.264 has already smeared fine detail.
- Both modes are worth having, and they share one engine.

---

## Part 1. Multi-frame super-resolution

### 1.1 Why it can add real detail (and how much)

A pixel integrates the scene over its area, then the image is sampled on a grid. When the optics and the ISP leave
any energy above the grid's Nyquist frequency, that energy **aliases**: it folds into false low frequencies. Several
frames shifted by different **sub-pixel** amounts sample the scene at different phases. Combined on a finer grid,
those samples unfold the aliasing and recover frequencies up to the finer grid's Nyquist limit. They also average down
noise and codec error, by about √N_eff, which lets a deconvolution undo more blur before noise takes over.

Three limits apply:

- **Sampling.** N frames with well-spread shifts give at most about √N times the sample density per axis. At s = 2
  that needs N ≥ 4; at s = 3, N ≥ 9.
- **Aliasing actually present.** The 640×480 photo is the ISP's downscale of a bigger sensor (the size is unknown).
  A good downscaler filters out most aliasing first. What it removed is gone, so the photo burst mostly gains noise
  and JPEG-error averaging plus a safer, stronger deconvolution. The live 320×240 stream is much more aliased and
  noisy, so it gains relatively more.
- **Optics cut-off.** No method passes the lens MTF cut-off. See Part 3.

**Honest expected gain**, as effective linear resolution (MTF50) against the 4.0 single-frame `ImageEnhance` at the
same output size:

| Mode | Input | Output | Real gain | Notes |
|---|---|---|---|---|
| Photo burst N = 4-6 | 640×480 JPEG | 1280×960 | **about 1.2-1.5×** linear, noise σ down about 2×, JPEG blocking mostly gone | Best on text, signs and fine edges in a still scene. Moving parts fall back to one frame. |
| Live burst 12-16 frames | 320×240 H.264 | 640×480 | **about 1.4-1.8×** over one live frame | Close to, or a little softer than, one Bluetooth photo. It is instant, with zero shutter lag. |
| Live burst, motion too large or too small | same | same | about 1.0× | It degrades to a denoised single frame and never comes out worse than bicubic (a test checks this). |

On synthetic aliased test patterns (§1.12) the target is **at least +1.5 dB PSNR over bicubic** at N = 8, s = 2. With
oracle shifts the target is **at least +2.5 dB**. On real photos, expect the gain to show in the finest legible text
size, which should drop by roughly one font step, not as "4K".

### 1.2 Pipeline overview

```
frames (ARGB IntArray, all W×H, same source)
  → YCbCr (BT.601 full range, floats 0..255)
  → deblock luma (JPEG 8×8 grid, or H.264 4×4/16×16)        §1.3
  → pick the reference frame (sharpest)                      §1.4
  → photometric match (gain/offset per frame)                §1.5
  → global registration: phase correlation → affine LK       §1.6
  → optional local tile refinement (photo burst only)        §1.6.5
  → per-tile robustness weights                              §1.7
  → shift-and-add gather onto the s× grid (luma)             §1.8
  → hole prior from bicubic reference (built into §1.8)
  → PSF estimate + RL-TV deconvolution (luma)                §1.9
  → chroma: weighted LR average, guided upsample by SR luma  §1.10
  → YCbCr → ARGB → ImageEnhance.tone() (no second sharpening)
```

Proposed files: `BurstSR.kt` (pipeline and parameters), `Fft2.kt` (radix-2 complex FFT, 1-D and 2-D),
`Register.kt` (phase correlation and LK), and `Deconv.kt` (separable Gaussian, RL-TV, Wiener for tests). The tests
are `BurstSRTest.kt` and `SynthScene.kt` (a test-only scene renderer).

```kotlin
class Plane(val w: Int, val h: Int, val v: FloatArray)          // row-major, v[y*w+x]
enum class Source { JPEG, H264 }
class Affine(val a: DoubleArray)   // [a11 a12 tx a21 a22 ty], maps REFERENCE coords → FRAME coords
class SrResult(val px: IntArray, val w: Int, val h: Int, val used: Int, val report: String)

object BurstSR {
    fun superResolve(frames: List<IntArray>, w: Int, h: Int, source: Source,
                     scale: Int = 2, p: Params = Params.of(source, frames.size)): SrResult
}
```

Coordinates follow one convention everywhere: pixel (i, j) has its centre at (x, y) = (i, j). The centre of HR pixel
(u, v) maps to the LR reference at x = (u + 0.5)/s − 0.5, y = (v + 0.5)/s − 0.5.

### 1.3 Deblocking pre-filter (on each LR luma plane, before registration)

Block edges are false high-frequency texture. They bias registration toward the fixed block grid, which is the same in
every frame, so they would pull shifts toward zero. They would also be "super-resolved" as if they were real.

**Blockiness metric** (also used in a test):

    B = mean_{x ≡ 0 mod G} |Y(x) − Y(x−1)|  /  mean_{x ≢ 0 mod G} |Y(x) − Y(x−1)|

This is taken over both axes. G = 8 for JPEG luma and G = 4 for H.264. B ≈ 1 means no blocking. Filter only if
B > 1.10.

**Edge filter.** This is H.264-style, normal strength, and runs on every grid line (vertical edges first, then
horizontal). Across an edge the samples are p1 p0 | q0 q1:

    if |p0 − q0| < α  and  |p1 − p0| < β  and  |q1 − q0| < β:      # a small step between flat sides → artefact
        Δ  = clip( (4(q0 − p0) + (p1 − q1) + 4) / 8, −t_c, +t_c )
        p0 += Δ ;  q0 −= Δ                                           # only p0/q0 change: simple and safe

| Param | JPEG (G = 8) | H.264 (G = 4, plus the 16 MB edges at 1.5× α) |
|---|---|---|
| α | 3·σ_step, clamped to [6, 20] | 3·σ_step, clamped to [5, 16] |
| β | 4 | 3 |
| t_c | 4 | 3 |

Here σ_step = 1.4826·median(|p0 − q0|) over all grid edges, a robust step size.

The decoder has already applied H.264's in-loop filter. This pass only catches what is left at 0.17 bpp.

**Block confidence map.** Used later in §1.8:

    c(x, y) = 0.7 if (x mod G ∈ {0, G−1}) or (y mod G ∈ {0, G−1}),  else 1.0

Samples on block borders carry the most quantisation error.

Cost: about 4 ops/pixel per pass, under 5 ms per 640×480 frame.

### 1.4 Reference frame

Sharpness is the variance of the Laplacian: S_k = mean((∇²Y_k)²) over the centre 80 %, using the 3×3 kernel
[0 1 0; 1 −4 1; 0 1 0].

- The reference is r = argmax S_k.
- Drop frames with S_k < 0.6·S_r. These are motion-blurred: head motion during the exposure cannot be undone.
- Live burst: also drop frames whose LK result (§1.6) has |t| > 8 px from the reference (head turning). Keep the
  longest run of still frames, at most 16.

**Noise estimate** (Immerkær-style, robust to texture): σ_n = 1.4826·median(|∇²Y_r|) / √20. The norm of the kernel
is √20. Clamp σ_n to [0.5, 12].

### 1.5 Photometric match

Photos taken about 6 s apart can differ in auto-exposure.

1. Before registration, match the first two moments over the centre 80 %:
   Y_k ← (Y_k − μ_k)·(σ_r/σ_k) + μ_r.
2. After registration, refit the gain and offset by least squares on the warped overlap:
   [α, β] = argmin Σ (α·Ŷ_k + β − Y_r)². Apply it, then run one more LK pass (§1.6.4).

Reject the frame if α ∉ [0.7, 1.4]. That means a lighting change, not exposure.

### 1.6 Global sub-pixel registration (target ±0.1 px, aim 0.05 px)

The motion model is **affine** for each frame, x_k = A·x_r + t, about the image centre. A head-mounted camera on a
distant scene moves mostly by pan and tilt (translation) and roll (rotation). First-order rolling shutter (row-time
skew during a head turn, readout about 20-30 ms) is a shear, and a small forward step is a scale, so all of these fit
an affine model. Euclidean (tx, ty, θ) is the fallback for low-texture frames.

#### 1.6.1 Coarse: phase correlation (integer, plus a first sub-pixel guess)

1. Downsample Y_r and Y_k by 2 (2×2 mean) to get 320×240 for photos. The live frames are already 320×240, so use them
   as they are.
2. Take a centred 256×256 crop. Zero-pad the 240 rows to 256 after the window.
3. Apply a Hann window: w(n) = 0.5 − 0.5·cos(2πn/(M−1)), separable.
4. F_r = FFT2(w·Y_r), F_k = FFT2(w·Y_k). These are radix-2 complex 256×256 transforms.
5. Q = F_r·conj(F_k) / (|F_r·conj(F_k)| + ε), with ε = 1e-3·mean|F_r·F_k|.
6. Optional frequency weight exp(−|f|²/(2·0.3²)), with f in cycles/px, to discount aliased and noisy bins.
7. q = Re(IFFT2(Q)). The peak (p_x, p_y) is wrapped to ±128 px.

**Sub-pixel guess** (Foroosh et al. 2002, closed form for a sinc-shaped peak), per axis, with c = q(p), a = q(p+1),
b = q(p−1):

    δ = a/(a + c)   if a ≥ b
    δ = −b/(b + c)  otherwise

The translation is 2·(p + δ) at full resolution for photos, or (p + δ) for live frames.

**Quality.** Let ρ = q(peak) / (second-highest peak outside a 5×5 exclusion zone). If ρ < 1.5, the frame has no
reliable global match, so drop it.

Cost per frame: 3 FFTs of 64 K points, about 15 MFLOP, about 15 ms.

#### 1.6.2 Fine: inverse-compositional Lucas-Kanade (affine), 3-level pyramid

- **Template.** T = Y_r pre-blurred with a Gaussian of σ = 0.6 px. This cuts the aliasing bias in the gradients.
  The blur is only for registration; SR uses unblurred samples.
- **Image.** I = Y_k, blurred the same way.
- **Pyramid.** Levels L = 2, 1, 0 at scales 1/4, 1/2 and 1. Build them by 2×2 mean after a [1 4 6 4 1]/16 blur.
  Initialise from §1.6.1 with A = Identity.
- **Parameters.** p = (a11−1, a12, tx, a21, a22−1, ty), with coordinates relative to the centre (c_x, c_y).

Precompute on T at each level:

    ∇T = (T_x, T_y)                 # central differences  (T(x+1) − T(x−1))/2
    J(x,y) = [ x' y' 1 0  0  0 ;  0  0 0 x' y' 1 ],   x' = x − c_x, y' = y − c_y
    SD(x,y) = ∇T · J  (1×6)
    H = Σ_x  w(x) SDᵀ SD   (6×6)    # recomputed when robust weights change

Iterate, at most 20 times per level:

    I_w(x) = I(W(x; p))            # bicubic (Keys a = −0.5) sampling; bilinear at levels 2 and 1
    e(x)   = I_w(x) − T(x)
    σ̂      = 1.4826 · median|e|                    # every 3rd iteration
    w(x)   = 1 if |e| ≤ c else c/|e|,   c = 1.345·σ̂   # Huber IRLS: moving objects lose their pull
    Δp     = H⁻¹ Σ w(x) SD(x)ᵀ e(x)                # solve 6×6 by Cholesky
    W(x;p) ← W(x;p) ∘ W(x;Δp)⁻¹                    # inverse compositional update (3×3 matrices)
    stop if |Δtx|,|Δty| < 0.005 px and the corner displacement of ΔA < 0.005 px

- Use pixels with |∇T| above the 30th percentile only. This halves the cost with no loss of accuracy.
- Exclude a 16 px border, plus any pixel whose warped position falls outside the frame.
- **Fallback.** If the last level's RMS(e) > 3·√2·σ_n, or H is ill-conditioned (λ_min/λ_max < 1e-6), rerun with the
  3-parameter Euclidean model, J = [1 0 −y'; 0 1 x']. If that also fails, drop the frame.

#### 1.6.3 Accuracy check (Cramér-Rao)

For two noisy images:

    σ_tx ≈ σ_n · √( 2 / Σ w·T_x² ),    σ_ty likewise

Flag the frame if max(σ_tx, σ_ty) > 0.1 px, and halve its weight in §1.8. On a textured 640×480 scene with σ_n ≈ 2,
the sum of T_x² is about 10⁷ to 10⁸, so σ_t ≈ 0.001-0.005 px. Model error (rolling shutter, parallax) and codec
error dominate the real accuracy. Expect 0.03-0.08 px.

#### 1.6.4 Photometric refit

After convergence: refit α and β (§1.5), apply them to Y_k, and run one more LK iteration at level 0.

#### 1.6.5 Local tile refinement (photo burst only; live burst skips it)

Photos taken about 6 s apart have parallax and small non-rigid errors that one affine model does not catch.

- **Grid.** 32×32 px tiles at level 0 with 50 % overlap.
- **Per tile.** Run translation-only LK (2 parameters) starting from the global warp, with Δ limited to ±1.5 px and
  at most 8 iterations. Accept a tile only if it has texture, meaning σ_t < 0.1 px by the formula above. Otherwise
  keep (0, 0).
- **Smoothing.** Median-filter the flow over 3×3 tiles, then interpolate it bilinearly between tile centres, giving
  the field d(x).
- The final warp is W_k(x) = A·x + t + d(x).

Cost per photo: 300 tiles × 8 iterations × 1 K px, about 10-20 ms.

### 1.7 Outlier and motion rejection, per tile

1. Warp every frame onto the reference grid: Ŷ_k(x) = Y_k(W_k(x)), bicubic.
2. Use tiles of 16×16 LR pixels. Per tile τ:

       r_τ = mean_{x∈τ} ( Ŷ_k − Y_r )²
       E_τ = 2σ_n² + (ε_reg)²·mean_{x∈τ} |∇Y_r|²  + σ_q²
       ρ_τ = r_τ / E_τ

   - ε_reg = 0.15 px is the tolerated registration error. It makes edges forgiving: a 0.1 px error on a strong edge
     must not count as motion.
   - σ_q² is the codec error budget: (0.5·σ_step)² for JPEG and (1.0·σ_step)² for H.264.

3. Weight:

       w_kτ = exp( −max(0, ρ_τ − 1) / κ ),   κ = 1.5;   w_kτ = 0 if ρ_τ > 5

4. **H.264 skip blocks.** Compare the raw decoded frame k with frame k−1. A 16×16 macroblock whose difference is
   identically 0 is a copy of the previous frame and carries no new information. Set w = 0 there (unless k = r).
5. Smooth the weight map with a 3×3 tile box filter, so tiles don't leave seams. Take the per-pixel weight
   w_k(x) by bilinear interpolation between tile centres. The reference weight w_r(x) = 1.
6. **Effective frame count.** N_eff(x) = Σ_k w_k(x). This is used in §1.8 and §1.9 and goes into the report.

A moving person or car gets w ≈ 0 in the other frames. Those regions come out as the single reference frame
(bicubic plus mild deconvolution), so there are no ghosts.

### 1.8 Shift-and-add (gather form) onto the s× grid

The gather form loops over output pixels, which avoids inverting each W_k and keeps a fixed cost per output pixel.

```
σ_k (in LR px) = clamp(0.35 + 0.6/√N̄_eff, 0.45, 0.95)      # N̄_eff = mean N_eff over the image
                  # N=1 → 0.95 (smooth), N=4 → 0.65, N=8 → 0.56, N=16 → 0.50
g(d) = exp(−d²/(2σ_k²))                                    # isotropic Gaussian, separable
λ_prior = 0.05                                             # weight of the bicubic hole prior
B = bicubic_upscale(Y_r, s)                                # prior; also the comparison baseline

for each HR pixel (u,v):
    x_r = ((u+0.5)/s − 0.5, (v+0.5)/s − 0.5)
    num = λ_prior · B(u,v) ;  den = λ_prior
    for each kept frame k:
        p = W_k(x_r)                                       # position in frame k's LR pixels
        wk = w_k(x_r) · (0.5 if frame k flagged in 1.6.3 else 1)
        for each LR pixel q in the 3×3 around round(p):    # 4×4 when σ_k > 0.7
            d² = |q − p|²
            ω  = wk · c(q) · g(d²)                         # c = block confidence (1.3)
            num += ω · α_k·Y_k(q) + β_k                    # photometrically matched sample
            den += ω
    Z(u,v) = num / den
    D(u,v) = den                                           # sample density, used by 1.9
```

- With λ_prior there are **no holes**. Where no samples land (den ≈ λ), Z falls back to bicubic. Where samples are
  dense, the prior has under 1 % influence.
- **Kernel choice.** A Gaussian is used because Lanczos's negative lobes are unstable on irregular samples (sparse
  areas ring). The sharpness the Gaussian costs is put back by §1.9, which deconvolves the Gaussian exactly.
- **Optional v2: an edge-steered anisotropic kernel** (Wronski et al. 2019). Per LR pixel of the reference, take the
  structure tensor (Gaussian σ = 1 LR px) with eigenvalues λ1 ≥ λ2 and coherence A = (λ1 − λ2)/(λ1 + λ2). Stretch
  g along the edge (k_∥ = σ_k·(1 + 2A)) and narrow it across the edge (k_⊥ = σ_k·(1 − 0.5A)). Then
  d² = (d·e1)²/k_⊥² + (d·e2)²/k_∥² replaces d²/σ_k². This adds about +0.3-0.6 dB on edges for about 1.5× the cost.
  Ship the isotropic kernel first.

Cost (s = 2, 1280×960 = 1.23 M HR pixels, N = 5, 9 taps): about 55 M multiply-adds plus 1.2 M divides, about
100 ms on one A76 core. Rows split across 4 threads, about 35 ms. For the live burst (640×480 HR, N = 16): about
45 M, about 80 ms.

### 1.9 PSF estimate and deconvolution (luma, HR grid)

#### 1.9.1 PSF model

The blur relating Z to the true scene is approximately Gaussian:

    σ_h² (HR px) = s²·( σ_sys² + σ_k² )

- **σ_sys** is the LR system blur (optics, pixel aperture of variance 1/12, ISP), in LR px, measured on Y_r:
  1. Take candidate pixels where |∇Y_r| is in the top 0.5 % and the structure-tensor coherence A > 0.8, meaning
     straight edges.
  2. At each, sample a profile along the gradient direction over ±4 px in 0.25 px steps (bilinear).
  3. Normalise it between the 10 % and 90 % plateau medians and measure the 10-90 % rise distance w.
  4. For a Gaussian-blurred step, w = 2·1.2816·σ, so σ = w/2.563.
  5. σ_sys = median over at least 50 edges. It already includes the pixel aperture, so nothing is subtracted.
     Clamp it to [0.35, 1.0] LR px.
  6. Default when there are fewer than 50 edges: 0.55 for JPEG and 0.70 for H.264.
- **Cap.** σ_h ≤ 1.0·s. Deconvolving more than that only rings.

#### 1.9.2 RL-TV (Richardson-Lucy with total-variation regularisation; Dey et al. 2006)

Work on u = (Z + 1)/256, which keeps the data positive. h is a separable Gaussian with σ_h and radius ⌈3σ_h⌉. It is
symmetric, so hᵀ = h. Use reflect padding of ⌈3σ_h⌉.

```
u0 = z
repeat n_RL times:
    ratio = z / max(h ⊛ u, 1e-4)
    corr  = h ⊛ ratio
    tv    = div( ∇u / sqrt(|∇u|² + 1e-6) )          # forward diffs for ∇, backward for div
    u     = u · corr / max(1 − λ_TV · tv, 0.5)
```

| Param | Photo burst | Live burst | Single frame (N_eff ≤ 1.5) |
|---|---|---|---|
| n_RL | 8 | 6 | 4 |
| λ_TV | 0.002 | 0.004 | 0.006 |

λ_TV is then scaled by the noise: λ_TV ← λ_TV·(σ_n/2)·√(1/N̄_eff), clamped to [0.001, 0.02].

**Spatially adaptive strength.** The result blends deconvolved and accumulated data by the local sample density:

    β(x) = clamp( (N_eff(x) − 1)/3, 0.3, 0.9 )
    Y_SR = Z + β·(256u − 1 − Z)

Single-frame areas get mild deconvolution. Dense areas get almost the full result.

**Halo clamp.** This keeps the existing `ImageEnhanceTest` "no halos" promise. Clamp Y_SR to
[min₃ₓ₃(Z) − 0.02·255, max₃ₓ₃(Z) + 0.02·255].

**Wiener.** Implement it in `Deconv.kt` only for tests on small images, as a cross-check:

    Û = conj(H)/( |H|² + NSR )·Ẑ,   NSR = σ_Z² / var(Z),   σ_Z² = σ_n²/N̄_eff

Cost (1.23 M pixels, σ_h ≈ 1.6, 11 taps): one iteration is 2 separable convolutions (about 54 M multiply-adds) plus
the TV term (about 15 M), about 70 M. Eight iterations come to about 0.55 G, about 0.6-0.9 s on one A76 core, or
about 0.25 s on 4 threads. The live burst at 0.3 M pixels takes about 0.1 s.

### 1.10 Chroma

Chroma resolution is poor in both sources (JPEG 4:2:2 and H.264 4:2:0), and the eye is insensitive to it. Do not
super-resolve chroma; denoise it, then let the SR luma place its edges.

1. **LR weighted average.** For C ∈ {Cb, Cr}, at LR reference resolution:
   C̄(x) = Σ_k w_k(x)·C_k(W_k(x)) / Σ_k w_k(x), with bilinear sampling and the same warps and weights as luma.
2. **Initial upsample.** p = bilinear_upscale(C̄, s).
3. **Guided filter** (He et al.) with guide I = Y_SR, radius r = 2s HR px, ε = (0.1·255)² = 650:

       mean_I, mean_p, corr_Ip, var_I via box filters (integral images, O(1)/px)
       a = (corr_Ip − mean_I·mean_p) / (var_I + ε);   b = mean_p − a·mean_I
       C_SR = box(a)·I + box(b)

4. **Chroma noise.** If σ_C (by the same Laplacian MAD as §1.4) is above 3, run one 3×3 bilateral on C̄ first. The
   existing `ImageEnhance.bilateral3` is fine.

Cost: 2 channels × about 8 box passes × 1.23 M pixels, about 25 M, about 40 ms.

### 1.11 Output, integration and total cost

- Convert YCbCr to ARGB. Run `ImageEnhance.tone(contrast = 1.04, saturation = 1.08)`. **Do not** run `unsharp` again,
  because the deconvolution already did that job, and doing both makes halos.
- Write the JPEG comment `"P3 Companion: Burst ×N_used, 1280×960 from 640×480"` (existing `Comment` helper).
- **Photo burst trigger.** Take N = 4 (setting: 3-6). Request the next photo as soon as the `7300` transfer
  completes, without waiting for the `7500` timeout, if the coder can confirm that is safe. Tell the user "Hold
  still, about 20 seconds."
- **Live burst trigger.** It has zero shutter lag. Keep a ring of the last 16 decoded live frames as luma and chroma
  `FloatArray`s (16 × 320×240 × 1.5 × 4 B, about 7 MB). A "Sharp snapshot" button runs SR on the ring right away.
- **Mic conflict.** Because video and the glasses mic can't run together, a voice command like "take a sharp picture"
  must stop the mic stream first.

**Total on a moto g 2025** (4 worker threads, ART JIT):

| Stage | Photo burst N = 5, 640×480 → 1280×960 | Live burst N = 16, 320×240 → 640×480 |
|---|---|---|
| Decode and YCbCr | 80 ms | 20 ms (already decoded) |
| Deblock | 25 ms | 20 ms |
| Phase correlation | 60 ms | 240 ms (15 pairs) |
| LK affine | 300-500 ms | 250-400 ms |
| Tile refinement | 80 ms | — |
| Weights and warps | 60 ms | 60 ms |
| Shift-and-add | 35 ms | 25 ms |
| RL-TV | 250 ms | 60 ms |
| Chroma | 40 ms | 15 ms |
| **Total** | **about 1.0-1.2 s** (about 2-3 s single-thread) | **about 0.7-0.9 s** |
| Peak heap | about 60 MB float buffers | about 25 MB |

Capture time dominates the photo burst: about 24 s for N = 4.

### 1.12 Unit tests (JVM, synthetic, seeded `java.util.Random`)

**`SynthScene` (test helper).** It renders a continuous scene f(x, y) analytically, so tests never depend on the
interpolation being tested. The scene contains:

- A zone plate, sin(π·k·r²), with chirp up to 0.7 cycles per LR pixel. This is deliberately past Nyquist, so it
  aliases.
- A Siemens star with 36 spokes.
- Anti-aliased black text-like bars of 1, 1.5 and 2 LR px width.
- A smooth gradient background in 30..220.

`renderLR(shift dx, dy, rot θ, scale s_LR, noise σ, seed)`: each LR pixel is the mean of f over its unit square, from
an 8×8 supersample (box aperture), plus Gaussian noise, rounded to 8 bit.

`renderHR(s)` is the same with pixels 1/s the size. It is the ground truth.

`jpegish(Q)`: 8×8 DCT-II, a uniform quantiser of step Q, then IDCT. This is a pure Kotlin blocking simulator.

| # | Test | Pass condition |
|---|---|---|
| 1 | `fftRoundTripAndKnownSpectrum` | IFFT(FFT(x)) = x within 1e-9. The FFT of a single cosine has two bins equal to N/2. |
| 2 | `phaseCorrelationIntegerShifts` | Shifts (5, −3), (−17, 11), (0, 0): error exactly 0 after rounding. ρ > 3. |
| 3 | `phaseCorrelationSubpixel` | 20 random shifts in [−4, 4]², σ = 0: \|error\| < 0.25 px. |
| 4 | `lkRefinesToTenthPixel` | 20 random shifts, plus θ ∈ [−1°, 1°], σ = 0: translation RMS error < 0.05 px, rotation error < 0.02°. At σ = 2: < 0.1 px. |
| 5 | `lkIgnoresAMovingObject` | A 64×64 square displaced by 10 px in frame k (about 10 % of the area): global error still < 0.1 px. |
| 6 | `affineCatchesRollingShutterShear` | Frame k with x-shift = 0.5 + 0.004·(y − c_y): the fitted a12 is within 5 % of 0.004. |
| 7 | `cramerRaoFlagsFlatFrames` | A flat image plus noise is flagged (σ_t > 0.1). The zone plate is not. |
| 8 | `tileWeightsRejectMotionKeepStatic` | With the moving square of #5: its tiles get w < 0.1, and static tiles get w > 0.8. |
| 9 | `h264SkipBlocksCarryNoWeight` | Frame k = copy of k−1 in one 16×16 MB: w = 0 there. |
| 10 | `srBeatsBicubic` | N = 8 random sub-pixel shifts, s = 2, σ = 2: PSNR(SR, HR) ≥ PSNR(bicubic(ref), HR) + 1.5 dB. Also ≥ PSNR(`ImageEnhance.enhance` at strength 0.6) + 1.0 dB. |
| 11 | `srWithOracleShifts` | Same as #10 with the true warps injected: ≥ bicubic + 2.5 dB. |
| 12 | `srNeverWorseThanBicubicForOneFrame` | N = 1: PSNR(SR) ≥ PSNR(bicubic) − 0.3 dB. |
| 13 | `srResolvesZonePlateBeyondLrNyquist` | In the zone-plate ring at 0.55-0.7 cycles/LR px, the normalised correlation of SR with the true HR pattern is ≥ 0.3. Bicubic gives ≤ 0.1, because there the detail is aliased into a false pattern. |
| 14 | `flatStaysFlat` | A flat image at 128 through the whole pipeline: every output pixel is 128 ± 1. |
| 15 | `deconvSharpensEdgeWithoutHalo` | A step blurred with σ = 1.2 HR px: the 10-90 % width drops by ≥ 25 %. Overshoot is ≤ 2 % of the step (halo clamp). |
| 16 | `psfEstimateFromEdges` | Slanted edges blurred with σ = 0.6 LR px plus σ_n = 1: the estimate is within ±0.1 px. |
| 17 | `deblockReducesBlockiness` | `jpegish(Q = 24)` on the scene: B drops by ≥ 30 %. PSNR against the unquantised image does not drop. |
| 18 | `chromaFollowsLumaEdges` | A colour edge coincident with a luma edge, chroma at half resolution: after the guided upsample, the chroma 10-90 % width is ≤ 2 HR px (bilinear gives about 4). No colour bleed > 3 levels at 4 px from the edge. |
| 19 | `exposureChangeIsMatched` | Frame k = 1.2·f + 10: α within 1.2 ± 0.02, and SR PSNR within 0.3 dB of test #10. |
| 20 | `benchmarkFiveFrames` | (informational, `@Ignore` by default) 5 × 640×480 finishes in under 3 s on a desktop JVM. Prints per-stage ms. |

PSNR = 10·log10(255²/MSE), over the centre, excluding a border of 4·s HR px.

### 1.13 When it fails, and what the code does then

| Cause | Symptom | Handling |
|---|---|---|
| Head motion during exposure | Directional blur | Laplacian-variance gate (§1.4) drops the frame. |
| Big head turn between frames | Little overlap | Phase correlation ρ < 1.5 or LK failure drops the frame. If fewer than 2 frames remain, return the 4.0 single-frame result and say so in the report. |
| Moving people or objects | Ghosts | Tile weights (§1.7) fall back to the reference there. |
| Rolling shutter during a fast turn | Skewed frames | The affine shear absorbs the first-order part. Higher-order wobble ends up rejected by tile weights. |
| Parallax (near objects, head translation) | Depth-dependent shift | Tile refinement (§1.6.5) for photos. Live frames 33 ms apart have negligible parallax. |
| Too little motion (head on a stand) | All shifts about 0 mod 1 px: no new phases | Cramér-Rao is fine but shifts cluster. Report "no new detail: frames too similar". The output is a denoise only, with β capped at 0.3. |
| H.264 blocking and skip blocks | Fake texture, duplicate data | Deblock (§1.3), skip-block weight 0 (§1.7). |
| JPEG grid | Grid-locked registration | Deblock plus block confidence c(x). |
| Low light | Noise dominates | Real gain here is mostly √N denoise. λ_TV scales with σ_n. |
| AE or white balance change | Brightness mismatch | Photometric fit (§1.5), with reject outside 0.7-1.4. |

---

## Part 2. Glasses mic signal chain for voice chat

### 2.1 Signal and frames

**Scope.** This chain is for the `5750` Opus data-link mic. That route has no phone-side processing, and it lets
A2DP keep playing. The hands-free (SCO) route records with `VOICE_COMMUNICATION`, which already applies Android's
NS/AGC/AEC where the phone has them. On SCO, run only the VAD (§2.6) and the gate (§2.7), and never stack a second
suppressor.


- **Input.** 16 kHz mono PCM16 from `OpusPcm`. The Opus stream is 16 kbit/s, so expect an audio bandwidth of about
  7-8 kHz (SILK wideband), mild codec noise, and a far-field-ish temple mic. Typical speech is −35 to −20 dBFS. The
  noise floor is −65 to −45 dBFS.
- **Frame.** **20 ms = 320 samples**, one Opus packet. The resampler hands out chunks of any length, so a
  `FrameBuffer` re-blocks them into exact 320-sample frames.
- **Lost packets.** A gap in the `OpusFramer` sequence (or a missing 20 ms of arrival time over 60 ms) produces a
  frame flagged `lost` and filled with zeros. The VAD treats `lost` as unknown (§2.6).

```
ShortArray chunks → FrameBuffer(320) → float x∈[−1,1)
  → HPF 80 Hz (biquad)                                  2.2
  → STFT (640 window, 320 hop, FFT 1024)                2.3
      ├─ noise PSD: minimum statistics                  2.3.2
      ├─ VAD features (energy, ZCR, whitened SFM)       2.6
      └─ Wiener gains (decision-directed)               2.3.3
  → ISTFT (overlap-add)
  → echo gate / barge-in                                2.7
  → AGC to −20 dBFS RMS (speech-gated)                  2.4
  → look-ahead limiter −1 dBFS                          2.5
  → ShortArray out (to PcmPipe / Gemini)
```

Proposed file: `VoiceChain.kt`, with `class VoiceChain(params)`, `fun process(frame: FloatArray, lost: Boolean): Out`,
where `Out(pcm, vadState, flags)`. `VadPlus` keeps the existing `Vad.State` enum and `feed()` contract, so it can
replace `Vad` with no change to callers.

The added latency is 20 ms (STFT) plus 2 ms (limiter look-ahead). CPU is about 0.1 MFLOP per frame, about
5 MFLOP/s, well under 1 % of one A55 core.

### 2.2 High-pass, 80 Hz

This is a 2nd-order Butterworth (RBJ cookbook, Q = 1/√2, f0 = 80 Hz, fs = 16 kHz), direct form I in double precision:

    y[n] = b0 x[n] + b1 x[n−1] + b2 x[n−2] − a1 y[n−1] − a2 y[n−2]
    b0 =  0.978030479   b1 = −1.956060958   b2 = 0.978030479
    a1 = −1.955578240   a2 =  0.956543677

|H(f)|² = 1/(1 + (f0/f)⁴), which gives: 20 Hz −24.1 dB, 50 Hz −8.8 dB, 80 Hz −3.0 dB, 200 Hz −0.11 dB, 1 kHz 0.00 dB.
It removes DC, wind rumble, handling thumps and mains hum fundamentals. If a test shows wind is the main complaint,
cascade two stages (4th order, use Q = 0.5412 and 1.3066).

### 2.3 Noise suppressor (STFT Wiener, decision-directed, minimum statistics)

#### 2.3.1 STFT

- Analysis and synthesis window: √Hann of length M = 640, w(n) = sin(π(n + 0.5)/M). At a 50 % hop the squared
  window sums to 1, so with unity gains the output reproduces the input exactly.
- Hop 320. FFT size 1024: zero-pad the 640 windowed samples. That gives K = 513 bins at Δf = 15.625 Hz.
- After applying the gains, take the IFFT, keep the first 640 samples, multiply by w, and overlap-add.
- Periodogram: P(k, l) = |X(k, l)|².

#### 2.3.2 Noise PSD: minimum statistics (Martin 2001, simplified)

    S(k,l)   = α_s·S(k,l−1) + (1−α_s)·P(k,l),   α_s = 0.80        # ~90 ms smoothing
    window D = 75 frames (1.5 s) as U = 5 sub-windows of V = 15 frames:
        track S_sub_min(k) over the current sub-window; every V frames push it into a ring of U mins
        S_min(k,l) = min(ring of U, current sub-window min)
    λ_d(k,l) = B_min · S_min(k,l),       B_min = 1.66 (bias for α_s = 0.8, D = 75; verify in test 2.8-#4)

- Initialisation: for the first 10 frames, λ_d = mean P. Then switch to min-stats.
- Rising noise is tracked within D to 2D (1.5-3 s). Falling noise is tracked within V frames (0.3 s).
- **Freeze** the update while the echo gate is closed (§2.7), so reply audio is never learned as noise.

#### 2.3.3 Gain

    γ(k,l) = P / λ_d                                             # a-posteriori SNR
    ξ(k,l) = α_dd · G²(k,l−1)·γ(k,l−1) + (1−α_dd)·max(γ−1, 0)    # decision-directed a-priori SNR
             α_dd = 0.98,  ξ ≥ ξ_min = 10^(−25/10)
    G(k,l) = max( ξ/(1+ξ),  G_min )                             # Wiener
    G ← 3-tap frequency smoothing [0.25 0.5 0.25]                # fewer musical tones

| Path | G_min |
|---|---|
| Speech-to-text (Gemini or a phone recognizer) | −10 dB (0.316). Recognizers lose more to over-suppression than to noise. |
| Voice played to a person | −15 dB (0.178) |

Bins below 80 Hz are set to G_min. Bins above 7.5 kHz are set to min(G, 0.5), since Opus leaves little there.

### 2.4 AGC (target −20 dBFS RMS on speech)

    frame power  p_l = mean(x²) after NS
    speech level L  (dB, EMA over VAD-speech frames only, α_L = 0.95 → τ ≈ 0.4 s)
        L ← 10·log10( α_L·10^(L/10) + (1−α_L)·p_l )
    desired gain g_d = clamp( −20 − L,  −6 dB,  +30 dB )
    slew per 20 ms frame:  up ≤ +0.10 dB (5 dB/s),  down ≥ −0.60 dB (30 dB/s)
    first 500 ms of the first utterance: up ≤ +1.0 dB/frame (fast initial lock)
    non-speech frames (VAD off, after the 100 ms hangover): gain frozen    # no noise pumping
    gain applied with per-sample linear interpolation across the frame (no zipper steps)

The AGC level and gain are remembered between utterances in the same session, so the next one starts at the right
level.

### 2.5 Limiter (ceiling −1 dBFS = 0.891)

    look-ahead La = 32 samples (2 ms), delay the signal by La
    g_req[n] = min(1, 0.891/|x[n]|)
    g_tgt[n] = min_{m∈[n, n+La]} g_req[m]          # sliding min (monotone deque)
    g[n] = g_tgt[n] if g_tgt[n] < g[n−1]            # instant attack (look-ahead hides it)
           else g[n−1] + (1−e^{−1/(0.05·fs)})·(g_tgt[n] − g[n−1])   # 50 ms release
    y = x·g ; then hard clip to ±0.891 as a safety net; PCM16 = round(y·32767)

### 2.6 VAD (energy + zero-crossings + noise-whitened spectral flatness, with hangover)

The VAD runs on the HPF output and the STFT spectra, **before** AGC, so its absolute levels mean something. Per frame
l:

| Feature | Definition | Speech | Noise |
|---|---|---|---|
| E_abs | 10·log10(mean x² + 1e-12) dBFS | > −55 | — |
| ΔE | 10·log10( Σ_{k=7}^{448} P_k / Σ λ_d,k ), over 100-7000 Hz | > 4 dB | about 0-2 dB |
| ZCR | (1/(2·320))·Σ \|sgn x[n] − sgn x[n−1]\|, crossings per sample | voiced < 0.12, fricatives 0.12-0.45 | white noise about 0.5 |
| SFM_w | Flatness of γ_k = P_k/λ_d,k over k = 20..256 (300-4000 Hz): 10·log10( exp(mean ln(γ_k + 1e-6)) / mean γ_k ) | < −4 dB (harmonic and formant structure) | about −2.5 dB, the χ²₂ value e^{−γ_E}, for any coloured stationary noise including hum, after the floor adapts |
| R_hi | Σ P_k (2-7 kHz) / Σ P_k (100-7000 Hz) | fricatives > 0.5 | — |

Whitening by λ_d is what makes SFM robust: fans, road rumble and hum become flat once divided by their own PSD.

**Frame decision:**

    loud     = E_abs > −55 dBFS  and  ΔE > 4 dB
    voiced   = loud and ZCR < 0.12 and SFM_w < −4 dB
    unvoiced = loud and ZCR ≥ 0.12 and ΔE > 6 dB and R_hi > 0.5
    speech_l = voiced or unvoiced or (loud and ΔE > 15 dB)          # very loud close speech always counts

**State machine.** It keeps `Vad.State`. Its parameters are `startMs`, `endMs`, `noSpeechMs` and `maxMs`.

- **WAITING → SPEECH** when at least 6 of the last 7 frames are speech (**120 ms**) and at least 3 of them are
  voiced, so a hiss burst or a door click cannot start an utterance. `speechStartMs` is back-dated to the first frame
  of that window. The existing `Vad.trim` pre-roll of 300 ms still applies.
- **SPEECH → ENDED_AFTER_SPEECH** after `endMs` of consecutive non-speech frames. The default is **800 ms** (40
  frames). It can be set from 700 to 900; Gemini keeps 900. A single speech frame resets the run, which is the
  **hangover**: pauses between words never end the turn.
- **AGC and NS hangover.** Treat 5 frames (100 ms) after the last speech frame as speech, so trailing consonants are
  not cut or gain-frozen.
- **Lost frames.** These count as neither speech nor silence: both counters are frozen. After 10 lost frames
  (200 ms) in a row they count as silence, so a dead link still ends the turn.
- **WAITING → ENDED_NO_SPEECH** after `noSpeechMs`, and any state → **ENDED_MAX** after `maxMs`, as today.

### 2.7 Echo: the reply plays 2 cm from the mic

Full acoustic echo cancellation (AEC) needs a sample-accurate reference and a stable delay. Here the reply goes
phone → A2DP → glasses DAC (about 150-300 ms, and it varies), and the mic comes back glasses ADC → Opus → SPP →
decoder (about 60-150 ms). The two clocks drift (±100 ppm, about 0.1 ms/s), and for Android `TextToSpeech` we never
see the PCM at all. So the default is **half-duplex gating with a measured tail**, plus a **barge-in detector**.

#### 2.7.1 Gate

    gate closed from playback start until playback end + T_tail
    T_tail = max(300 ms, D̂ + 150 ms);  default 500 ms until D̂ is measured
    while closed: frames not sent on; VAD counters frozen; noise PSD and AGC frozen

"Playback end" is the player's completion callback (the `AudioTrack` marker or `onCompletion`) or the `UtteranceProgressListener.onDone`. Both fire when the
phone has pushed the last buffer, so the tail must also cover the A2DP latency. The D̂ term does that.

#### 2.7.2 Round-trip delay D̂ (measured on the first reply, saved per glasses)

This needs a reference r: the Gemini PCM. Use 20 ms frame log-energies R_l = 10·log10(mean r²) and M_l (mic) over
the first ≥ 2 s of a reply.

    NCC(τ) = Σ_l (R_{l−τ} − R̄)(M_l − M̄) / (‖·‖‖·‖),   τ = 0..40 frames (0-800 ms)
    τ* = argmax NCC; accept if NCC(τ*) > 0.5; parabolic interpolation → D̂ ≈ ±5 ms
    D̂ ← 0.8·D̂_old + 0.2·D̂_new (saved in Prefs)

Without a reference (Android TTS), keep the default T_tail of 500 ms.

#### 2.7.3 Echo level and barge-in

The **echo return loss** is the speaker-to-mic gain, ERL = median over playback frames of (M_l − R_{l−D̂}) in dB.
Update it on playback frames with no detected barge-in, with EMA α = 0.98.

Use 16 bands (Bark-like) spanning 300-3400 Hz. Predicted echo per band: Ê_b(l) = R_b(l − D̂) + ERL_b.

    excess_b(l) = M_b(l) − Ê_b(l)
    barge_frame = ( fraction of bands with excess_b > 10 dB ) ≥ 0.30   and   ZCR < 0.25   and   ΔE > 6 dB
    BARGE-IN when ≥ 8 of the last 10 frames are barge_frame (160 ms)

**Without a reference**, the predicted echo is the running 80th percentile of M_l over the playback so far. A barge
frame is then M_l > P80 + 10 dB with the same ZCR and ΔE tests, and the counter needs 10 of 12 frames.

**On barge-in:**

1. Stop or duck the reply (pause or flush our player, or call `tts.stop()`).
2. Open the gate at once.
3. Start the VAD in SPEECH with `speechStartMs` = the barge onset minus 160 ms, taken from the `ClipRing` pre-roll.

**Touch strip.** A `C101` touch on the glasses during a reply is the 100 % reliable barge-in. It does everything
above without the acoustic test. This is the hardware's own "interrupt" button, so use it.

If ERL > −3 dB (echo louder than typical speech, because the reply volume is high), barge-in by voice will rarely
fire. Show the hint "lower the reply volume or tap the glasses to interrupt".

#### 2.7.4 Optional stage 2: a real AEC

Leave this for later, and only if users ask for talking over the reply.

Use a partitioned-block frequency-domain NLMS (PBFDAF): 20 ms blocks, 8 partitions covering 160 ms of echo tail,
aligned by D̂, with μ = 0.3·(1 − double-talk). Add residual echo suppression folded into the §2.3 gain:
λ_d += β·|Ŷ_echo|², β = 2. Clock drift must be compensated by re-estimating D̂ every 2 s. Expect 15-25 dB of echo
reduction with stable timing, and much less when A2DP rebuffers. That fragility is why gating is the default.

### 2.8 Unit tests (JVM, synthetic, seeded)

**`SynthVoice` (test helper).** It makes a speech-like signal:

    f0(t) glides 110→160 Hz (speaker A) or 190→240 Hz (speaker B); φ(t) = 2π∫f0
    voiced(t) = Σ_{h=1}^{⌊7000/f0⌋} a_h(t)·sin(h·φ(t)),
        a_h = Σ_{F∈{500,1500,2500}} 1/(1 + ((h·f0 − F)/120)²)          # three formants
    syllable envelope: env(t) = [0.5 − 0.5·cos(2π·4t)]^0.5 (4 syllables/s)
    unvoiced bursts: 30 ms of white noise band-passed 3-6 kHz at each syllable start, at −6 dB re voiced
    scaled to the requested RMS dBFS

Noise sources: white noise, pink noise (Voss-McCartney), a 120 Hz hum plus harmonics, and 10 ms clicks every 300 ms.

| # | Test | Pass condition |
|---|---|---|
| 1 | `hpfResponse` | Sine RMS ratios: 1 kHz within ±0.1 dB, 200 Hz ≥ −0.3 dB, 50 Hz ≤ −8 dB, 20 Hz ≤ −20 dB. A DC step's output mean over the last 0.5 s is < 1e-4. |
| 2 | `stftIsTransparentAtUnityGain` | With G ≡ 1, output = input delayed by 320 samples, max error < 1e-5. |
| 3 | `framesReblockFromOddChunks` | Chunks of 7, 333 and 1000 samples give exact 320-sample frames, losing no sample. |
| 4 | `noiseFloorTracksAndIsUnbiased` | White noise at −40 dBFS: mean λ_d within ±1.5 dB of the true PSD after 3 s. A +10 dB step at t = 5 s is tracked within 3 dB by t = 8 s. A −10 dB step is tracked by t = 5.5 s. |
| 5 | `suppressorImprovesSnr` | SynthVoice at −26 dBFS plus pink noise at 5 dB SNR: segmental SNR improves by ≥ 6 dB. Noise-only parts drop by ≥ 8 dB (G_min −10 dB). Speech harmonics in voiced frames lose ≤ 2 dB. |
| 6 | `suppressorLeavesCleanSpeechAlone` | Clean SynthVoice plus noise at −70 dBFS: output-input RMS difference < 0.5 dB on speech frames. |
| 7 | `agcReachesTargetFromQuietAndLoud` | Speech at −40 dBFS: speech-frame RMS within −20 ± 2 dB by 2 s. At −8 dBFS: −20 ± 2 within 1 s. |
| 8 | `agcDoesNotPumpNoise` | 5 s of noise only at −50 dBFS after an utterance: the gain rises < 1 dB. |
| 9 | `limiterHoldsCeiling` | A 0 dBFS burst at +30 dB AGC gain: no output sample above 0.891. THD on a −10 dBFS sine (below the ceiling) is < 0.1 %. |
| 10 | `vadStartsAt120ms` | 1 s noise (−50 dBFS) + 1.5 s speech + 0.5 s pause + 1 s speech + 1.5 s noise: SPEECH entered 120-200 ms after onset. No end in the 0.5 s pause. ENDED_AFTER_SPEECH is 800 ± 40 ms after the last offset. |
| 11 | `vadEndIsConfigurable` | endMs = 700 and 900: the end is at 700 ± 40 and 900 ± 40 ms. |
| 12 | `vadIgnoresStationaryNoiseAndHum` | 10 s each of white noise at −30 dBFS, pink at −30, and hum at −25: never SPEECH after the first 2 s. |
| 13 | `vadIgnoresClicksAndHissBursts` | Clicks every 300 ms, and 80 ms hiss bursts (no voiced frames): never SPEECH. |
| 14 | `vadHearsFricativeOnsets` | An utterance starting with a 150 ms fricative and then voiced speech: SPEECH starts no later than the voiced part + 60 ms, and speechStartMs ≤ the fricative onset + 40 ms. |
| 15 | `lostPacketsNeitherEndNorStart` | 60 ms of lost frames mid-speech: no end. 60 ms lost in silence: no start. 400 ms lost mid-speech: the end comes at 200 ms + endMs. |
| 16 | `echoDelayIsMeasured` | Mic = 0.5·ref delayed by 280 ms, plus noise at −50: D̂ = 280 ± 10 ms and NCC > 0.5. |
| 17 | `gateHidesTheReply` | Echo-only mic during playback plus tail: no SPEECH and no frame sent on. The noise PSD is unchanged by the playback, within 1 dB. |
| 18 | `bargeInFiresOnRealSpeech` | Speaker B at +12 dB over the echo from t = 1 s of playback: BARGE-IN within 200 ms. |
| 19 | `echoAloneNeverBargesIn` | 10 s of echo only (speaker A reference, ERL −6 dB, with ±3 dB level wobble): no BARGE-IN, in both the reference and no-reference modes. |
| 20 | `drop-in compatibility` | VadPlus fed the old `TorchAndVadTest` signals gives the same `State` sequence as `Vad`, ±1 frame on timings. |

---

## Part 3. What is physically impossible (the honest expectation)

The glasses carry a tiny fixed-focus lens (aperture about 1 mm). Its diffraction limit and the sensor behind it set
a hard optical cut-off. The bigger problem is that the firmware hands us only a 640×480 JPEG (or a 320×240 live
stream at 0.17 bit/pixel). That is already a heavy downscale of the sensor, filtered and compressed inside the
glasses. Detail removed there never reaches the phone, and no Wi-Fi or "full-res" path exists to fetch it. Nothing
recreates it: no method, no cloud service, no AI upscaler. An AI upscaler only *invents* plausible texture, which
can make text look sharp while getting the letters wrong.

Multi-frame SR can only recover what aliasing folded in and what noise and compression hid. That comes to about
1.2-1.5× real linear resolution for a photo burst and about 1.5× for a live burst, and only when the scene and head
hold still. Motion blur within one exposure, objects closer than the fixed focus, and very dark scenes stay soft.

The audio side has the same kind of limit:

- There is one temple mic, so no beamforming: a second voice at the same level cannot be separated out.
- The 16 kbit/s Opus link caps the bandwidth near 8 kHz.
- Speech below the noise (SNR < 0 dB) cannot be restored, only made less tiring.
- With the speaker 2 cm from the mic and two unsynchronised clocks, reliable talk-over (full duplex) is not
  achievable without a firmware-side echo canceller. Hence the half-duplex gate, voice barge-in when you are clearly
  louder, and the touch strip as the sure way to interrupt.
