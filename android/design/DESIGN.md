# ProofPad — notepad design tokens

One-page reference for the restyle. Every value is taken from the inline styles in
`mockups/html/01_capture.html … 05_history.html` (renders in `mockups/png/`). The look is a
sheet of ruled notepaper: cream page, a cream header band, blue rule lines, a red margin, ink
handwriting, and small sticky notes for hints and verifier messages.

## Colors

| Token | Hex | Where it's used |
|---|---|---|
| `Paper` | `#FBF8F1` | page background |
| `HeaderBand` | `#F3ECDC` | top header band and the bottom action bar |
| `HeaderBorder` | `#E2D7C0` | 1px divider under the header / above the bottom bar |
| `RuleLine` | `#CFE0F0` | horizontal rule lines, every **36dp**, starting **below the 96dp header** |
| `RedMargin` | `#E3A3A3` | vertical margin line at **x = 48dp** (2dp wide) |
| `Ink` | `#2A2419` | primary text, filled buttons, outlines |
| `Muted` | `#7A6A4A` | captions, secondary labels, "step 2 of 3" |
| `Green` | `#1F7A3A` | verified check, "VERIFIED" stamp, confidence pill |
| `Red` | `#C0392B` | flagged step, "CHECK STEP 2" stamp, error text |
| `StickyYellow` | `#FFF3A3` | hint sticky notes, capture caption, selected chips |
| `StickyPink` | `#FFD6D1` | verifier "doesn't match" sticky note, flagged-token highlight |

Supporting colors also present in the mockups (not in the headline list, captured for fidelity):

| Token | Hex | Where |
|---|---|---|
| `StepIndex` | `#C0504D` | the big step numbers in the step column |
| `ChipBorder` | `#C9BDA4` | unselected filter/token chip borders |
| `NoteInkYellow` | `#4A3D1E` | text on yellow sticky notes |
| `NoteInkPink` | `#5A2420` | text on pink sticky notes |
| `Held` | `#A09480` | "held until fixed" answer + disabled Export button |
| `PaperCard` | `#ECE6D6` | thumbnail / scanned-page card fill |
| `CaptureScrim` | `rgba(0,0,0,0.45)` | dimming outside the capture guide box |

The capture screen (01) is the one dark screen: a radial vignette `#5C554A → #3A352D → #1B1915`
behind a tilted paper card, with the guide box outlined in `StickyYellow` and a `#E8DCC0` caption.

## Typography

| Family | Role | Files in `res/font/` |
|---|---|---|
| **Patrick Hand** | captions & labels — headers ("Check my reading"), step rule names, sticky notes, chips, "step 2 of 3" | `patrick_hand_regular` |
| **STIX Two Text** | math — problems, step expressions, the answer | `stix_two_text_regular`, `stix_two_text_italic` |
| **Inter** | buttons & body — the bottom-bar buttons (500/600) and default UI text | `inter_regular` (400), `inter_medium` (500), `inter_semibold` (600) |

The mockups also use **JetBrains Mono** for the LaTeX field and the token chips (`\int`, `\frac{}{}`,
…). It is **not** bundled in this change (the headline font set is the three families above); map it
to the system monospace family for now and add it later if the LaTeX field needs it.

Fonts were taken from the `google/fonts` repo (SIL OFL 1.1). Inter and STIX ship upstream as
variable fonts; the bundled files are static instances (Inter `opsz=14`, `wght` 400/500/600; STIX
`wght=400`) made with `fonttools varLib.instancer`. The combined license text is in
`app/src/main/assets/FONTS_LICENSE.txt` — it lives there rather than in `res/font/` because
`aapt2` only accepts font resources (`.ttf/.otf/.xml`) in a font resource directory.

## Spacing & sizing

| Token | Value | Notes |
|---|---|---|
| Header band height | **96dp** | status-bar padding ~52dp on top; rule lines start at this offset |
| Rule line pitch | **36dp** | `#CFE0F0`, 1dp line |
| Red margin x | **48dp** | content left-indents to ~60dp (just right of the margin) |
| Step row height | **72dp** | step index column is 48dp wide; check/✗ icon ~28dp on the right |
| Bottom action bar | **92dp** | `HeaderBand` fill, padding 16/20/28dp, top border `HeaderBorder` |
| Buttons | **48dp** tall, **10dp** corner radius | secondary = 1.5dp `Ink` outline on transparent; primary = `Ink` fill, `Paper` text |
| Pills / chips | fully rounded (`999dp`) | selected = `Ink` fill or `StickyYellow`; unselected = `ChipBorder` outline |
| Sticky notes | slight rotation (±1–3°), soft shadow | yellow for hints, pink for the verifier flag |
| Answer box | hand-drawn wobble border (~2dp `Ink`) | dashed `Held` when the answer is withheld |
| Phone canvas | 390 × 844 | mockup reference frame |
