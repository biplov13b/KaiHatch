# KaiHatch — GitHub README Visual Media Pack

This directory contains the complete, self-contained visual media pack for the **KaiHatch** GitHub `README.md`. All animated WebP files loop seamlessly and all static SVG diagrams render natively on GitHub without external fonts, scripts, or remote dependencies.

## Asset Inventory & Actual File Sizes

| Asset Filename | Dimensions | Format & Specification | Actual File Size | Recommended Placement |
| :--- | :--- | :--- | :--- | :--- |
| `kaihatch-overview.webp` | `960 × 540` | Animated WebP (9.0s seamless loop, 180 frames) | **48.4 KB** (49,608 bytes) | Top of README, immediately below project introduction |
| `hatch-mode.webp` | `800 × 450` | Animated WebP (5.0s seamless loop, 100 frames) | **40.1 KB** (41,034 bytes) | Inside the *How Hatch Mode Works* section |
| `extraction-modes.svg` | `1000 × 500` | Static SVG (Comparison cards + data loss warning) | **7.3 KB** (7,511 bytes) | Inside the *Extraction Modes & Safety* section |
| `storage-comparison.svg` | `1000 × 420` | Static SVG (Conceptual disk usage bars) | **6.5 KB** (6,607 bytes) | Inside the *Why Progressive Extraction Matters* section |
| `kaihatch-header.svg` | `1200 × 280` | Static SVG (Optional project identity banner) | **3.0 KB** (3,112 bytes) | Very top of `README.md` (optional) |
| `features/progressive-extraction.svg` | `64 × 64` | Static SVG Icon | **1003 B** (1,003 bytes) | README Feature Grid |
| `features/integrity-check.svg` | `64 × 64` | Static SVG Icon | **384 B** (384 bytes) | README Feature Grid |
| `features/local-storage.svg` | `64 × 64` | Static SVG Icon | **548 B** (548 bytes) | README Feature Grid |
| `features/appearance.svg` | `64 × 64` | Static SVG Icon | **865 B** (865 bytes) | README Feature Grid |

---

## Asset Descriptions & Recommended Placement

### 1. Optional Header (`kaihatch-header.svg`)
- **Dimensions:** `1200 × 280`
- **Description:** Clean typographic wordmark (`KaiHatch` — *Extract more. Use less space.*) paired with a minimal archive tail-truncation motif.
- **Markdown Embed:**
```markdown
<p align="center">
  <img src="./kaihatch-github-assets/kaihatch-header.svg" alt="KaiHatch — Extract more. Use less space." width="100%" />
</p>
```

### 2. Main README Explainer (`kaihatch-overview.webp`)
- **Dimensions:** `960 × 540` (9.0s loop)
- **Description:** Side-by-side comparison titled *"Two ways to extract"*, contrasting **Standard Mode** (*"Original archive preserved"*) against **Hatch Mode** (*"Archive shrinks during extraction"*).
- **Markdown Embed:**
```markdown
<p align="center">
  <img src="./kaihatch-github-assets/kaihatch-overview.webp" alt="Two ways to extract: Standard Mode vs Hatch Mode" width="760" />
</p>
```

### 3. Progressive Shrinking Loop (`hatch-mode.webp`)
- **Dimensions:** `800 × 450` (5.0s loop)
- **Description:** Focused technical loop illustrating a 4-entry ZIP archive bar shrinking strictly from its tail as each entry cycles through `Extract → Verify → Reclaim`.
- **Markdown Embed:**
```markdown
<p align="center">
  <img src="./kaihatch-github-assets/hatch-mode.webp" alt="Hatch Mode: Extract, Verify, Reclaim" width="680" />
</p>
```

### 4. Standard Mode vs. Hatch Mode Comparison (`extraction-modes.svg`)
- **Dimensions:** `1000 × 500`
- **Description:** Two-card trade-off overview with a prominent warning banner: *"Hatch Mode modifies the original archive. Interruption during certain operations can cause unrecoverable data loss."*
- **Markdown Embed:**
```markdown
<p align="center">
  <img src="./kaihatch-github-assets/extraction-modes.svg" alt="Standard Mode versus Hatch Mode comparison and safety warning" width="780" />
</p>
```

### 5. Storage Usage Illustration (`storage-comparison.svg`)
- **Dimensions:** `1000 × 420`
- **Description:** Horizontal disk usage comparison showing how Hatch Mode reclaims archive storage as verified files are written. Includes the note: *"Illustrative storage behavior. Actual space requirements depend on archive contents."*
- **Markdown Embed:**
```markdown
<p align="center">
  <img src="./kaihatch-github-assets/storage-comparison.svg" alt="Storage usage comparison between Standard Extraction and Hatch Mode" width="780" />
</p>
```

### 6. Feature Icons (`features/*.svg`)
- **Dimensions:** `64 × 64` (`viewBox="0 0 64 64"`, designed to remain crisp down to `32px`)
- **Markdown Feature Table Example:**
```markdown
| Icon | Feature | Summary |
| :---: | :--- | :--- |
| <img src="./kaihatch-github-assets/features/progressive-extraction.svg" width="32" height="32" alt="Progressive extraction" /> | **Progressive Extraction** | Truncates the archive tail as entries are extracted to reclaim storage in-place. |
| <img src="./kaihatch-github-assets/features/integrity-check.svg" width="32" height="32" alt="Integrity check" /> | **Entry Verification** | Verifies extracted output before truncating the corresponding archive entry. |
| <img src="./kaihatch-github-assets/features/local-storage.svg" width="32" height="32" alt="Local storage" /> | **Local Storage Access** | Operates on writable Android local storage and supported archive structures. |
| <img src="./kaihatch-github-assets/features/appearance.svg" width="32" height="32" alt="Appearance" /> | **Light & Dark Themes** | Clean Material 3 interface supporting light and dark system appearances. |
```

---

## Export Notes & Reproducibility

- **No unfinished items or export limitations:** All 9 visual assets (2 multi-frame animated `.webp` files and 7 static `.svg` files) have been generated, verified, and packaged directly in `kaihatch-github-assets/`.
- **Re-generating assets:** Run `node scripts/generate-media-pack.mjs` (uses `@resvg/resvg-js` and `ffmpeg -c:v libwebp_anim -loop 0`).
