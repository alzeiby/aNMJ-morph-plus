# Validation notes

## Rectangular-image correction

The original macro stores a single image dimension and calculates total branch length with the spreadsheet expression:

```text
(A × A - backgroundPixels) × B / A
```

where `A` is one pixel dimension and `B` is the physical length of one side of the image. This is correct only when width equals height.

For a square image with side length `n` pixels and calibration `p` physical units per pixel:

```text
B = p × n
(A × A - backgroundPixels) × B / A
= (n² - backgroundPixels) × (p × n) / n
= (n² - backgroundPixels) × p
```

The modified macro computes the equivalent expression directly for arbitrary width `w` and height `h`:

```text
(w × h - backgroundPixels) × p
```

Therefore, for square images (`w = h = n`), the new expression is algebraically identical to the original one. For rectangular images, the actual image area in pixels is used rather than implicitly replacing one dimension with the other.

## Recommended regression checks

Before using the modified macro for publication-grade quantitative analysis:

1. **Square-image regression** — run representative square images through the original and modified macro using the same thresholds and manual decisions. The 19 reported morphology variables should match apart from formatting of the first two frame-size fields.
2. **Rectangular synthetic check** — use a rectangular binary image with a known number of foreground skeleton pixels and isotropic calibration. Confirm that total branch length equals `foregroundPixels × pixelSize`.
3. **Rectangular biological images** — compare a representative sample against manually verified measurements or an independently validated workflow.
4. **Calibration check** — confirm X and Y pixel calibration are equal. The current implementation still warns on anisotropic calibration and does not claim validated quantitative support for it.
5. **Acquisition/file-type check** — the original aNMJ-morph paper validated `.lsm` and `.nd2` images. For other formats or materially different acquisition settings, follow the original authors' recommendation to validate against the parent NMJ-morph workflow.

## Example arithmetic

For a `640 × 480` image with isotropic calibration `0.20 µm/pixel` and `100` non-background skeleton pixels:

```text
Total branch length = 100 × 0.20 µm = 20 µm
```

Equivalently, if Binary Connectivity reports `307100` background pixels:

```text
(640 × 480 - 307100) × 0.20
= 100 × 0.20
= 20 µm
```

## References

- Minty G, Hoppen A, Boehm I, et al. *aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology.* Royal Society Open Science. 2020;7:200128. https://doi.org/10.1098/rsos.200128
- Jones RA, Reich CD, Dissanayake KN, et al. *NMJ-morph reveals principal components of synaptic morphology influencing structure-function relationships at the neuromuscular junction.* Open Biology. 2016;6:160240. https://doi.org/10.1098/rsob.160240
