# Fresh Fiji runtime validation

This directory contains reproducible local integration tests for the interactive aNMJ-morph+ ImageJ macro. The generated harnesses exercise the real Fiji/ImageJ commands while replacing only the steps that normally require manual interaction.

The automated choices are fixed to channel 1 = muscle endplate, channel 2 = nerve terminal, ImageJ `Default dark` thresholds, three synthetic axon-width measurements, and acceptance of the stage-6 segmentation. These tests validate runtime/control flow, image/window state, CSV output, append behavior, and arbitrary-width image handling. They do not replace biological validation of manually chosen thresholds or morphology measurements.

## Run the full validation

Use a fresh Fiji installation that contains the dependencies required by aNMJ-morph+ (including Bio-Formats and `BinaryConnectivity`):

```powershell
.\tests\runtime\run_validation.ps1 -FijiRoot C:\path\to\fresh\Fiji
```

By default the runner generates fresh harnesses from `aNMJ-morph macro.txt`, runs `NMJ_1.lsm` twice, creates and runs a calibrated 384 x 512 two-channel TIFF twice, and validates:

- completion through stage 7;
- 29-column data rows;
- row-specific spreadsheet formulas;
- append behavior without duplicate headers or blank separator rows;
- absence of `NaN`;
- square and rectangular dimension metadata;
- all three cleaned TIFF outputs.

To validate another macro build without copying it into the repository, pass `-MacroPath`:

```powershell
.\tests\runtime\run_validation.ps1 `
  -FijiRoot C:\path\to\fresh\Fiji `
  -MacroPath C:\path\to\aNMJ-morph-plus.ijm
```

Generated files live under `tests/runtime/_work/` and are intentionally ignored by Git.

## CI scope

GitHub Actions verifies that `build_harnesses.py` can still transform the current production macro. The full Fiji GUI/runtime test remains local because it requires a Fiji installation, Bio-Formats, the morphology plugin, and GUI-capable ImageJ execution.

## Focused probes

`probes/` contains smaller ImageJ macros for diagnosing metadata and image-selection behavior independently of the full seven-stage workflow.
