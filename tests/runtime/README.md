# Fresh Fiji runtime validation

This directory contains the pinned fresh-Fiji scientific runtime checks for the Java/SciJava aNMJ-morph+ plugin. The production runtime does not invoke IJM. The repository-root `aNMJ-morph macro.txt` is retained only as a historical/scientific parity reference and numerical-oracle provenance source.

## Direct Java scientific oracle

Build the plugin and run:

```powershell
.\tests\runtime\run_direct_java_analysis.ps1 `
  -FijiRoot C:\path\to\fresh\Fiji `
  -PluginJar .\target\anmj-morph-plus-0.1.0-SNAPSHOT.jar `
  -Runs 2
```

The runner uses the pinned Fiji/ImageJ stack and Morphology/BinaryConnectivity dependency. It creates a test-only copy of the plugin JAR with `legacy/aNMJ-morph macro.txt` physically absent, compiles the direct-Java harness against that JAR, launches the Fiji-bundled Java process hidden, and executes the production Java analysis workflow directly.

Automation is fixed to the historical regression choices:

- muscle endplate channel 1;
- nerve terminal channel 2;
- ImageJ `Default dark` thresholds for Screens 2/3;
- actual 10/12/8-pixel line measurements for Screen 4;
- no synthetic erase operation at Screen 5;
- Screen 6 segmentation accepted;
- Screen 7 acknowledged.

Each validation run analyzes `Reference Images/NMJ_1.lsm`, a calibrated 384 x 512 rectangular fixture, and an anisotropic scientific fixture made from the same NMJ_1 pixels with `pixelHeight = 2 * pixelWidth`. With `-Runs 2`, it verifies append behavior by processing each case twice. The validator checks the unchanged isotropic NMJ_1 numerical oracle, the pinned 2:1 anisotropic oracle, threshold-method text, 29-column data rows, spreadsheet formulas, row numbering, final-newline behavior, absence of NaN in the automated path, and all three cleaned TIFF outputs.

`nmj1_numeric_baseline.json` pins the reference-image SHA-256, historical macro provenance, deterministic automation choices, pinned Fiji/ImageJ/Bio-Formats environment, and unchanged isotropic scientific values with explicit tolerances. `nmj1_anisotropic_y2_baseline.json` pins the intentional final XY-method fixture and its expected calibrated measurements, including the orientation-weighted branch-length correction. The macro provenance remains useful for demonstrating that Java retirement preserved the previously accepted isotropic behavior; the macro itself is not needed to execute the direct Java test.

## Java plugin smoke

After building the Maven JAR:

```powershell
.\tests\runtime\run_java_plugin_smoke.ps1 `
  -FijiRoot C:\path\to\fresh\Fiji `
  -PluginJar .\target\anmj-morph-plus-0.1.0-SNAPSHOT.jar
```

This checks that the plugin is discoverable by SciJava at the exact **Analyze > Tools > aNMJ-morph+** menu path and that the built plugin does not contain the retired legacy macro resource or `LegacyMacroRunner` class.

## CI scope

The Windows fresh-Fiji workflow downloads and checksum-verifies the pinned Fiji archive, installs the pinned Morphology plugin, builds the Java plugin, runs the plugin smoke, then executes the direct-Java square/rectangular/anisotropic Runs=2 scientific oracle. The process is launched hidden; the automation does not use desktop control, mouse/keyboard automation, or screenshots.

Generated runtime files stay under ignored `tests/runtime/_work/` and are not committed.
