# aNMJ-morph+

A maintained Fiji/ImageJ adaptation of **aNMJ-morph** by **Abdullah Alzeiby** for quantitative neuromuscular junction (NMJ) morphology analysis.

This fork preserves the original seven-step workflow as a Java/SciJava Fiji plugin while adding rectangular-image support, safer image dimensionality handling, broader batch input support, and automated repository checks. It is **not the official upstream aNMJ-morph distribution**.

## Highlights

- Supports rectangular images instead of assuming a square frame.
- Handles RGB images and real Z stacks more safely.
- Prompts before interpreting ambiguous one-channel/two-plane images as Keyence-style channel exports.
- Rejects time series (`T > 1`) instead of silently analyzing one frame.
- Supports interactive batch processing across common microscopy and image formats.
- Includes CI checks, release packaging, citation metadata, and retained reference material.

## Requirements

- [Fiji](https://fiji.sc/) / ImageJ
- Fiji's bundled **Analyze Skeleton** plugin

## Installation

The analysis runtime is implemented as a Java/SciJava Fiji plugin. The command is packaged as a normal Maven JAR and appears at **Analyze > Tools > aNMJ-morph+**. Image processing is delegated to the corresponding Fiji/ImageJ commands and APIs rather than reimplemented in project code.

1. Install Fiji.
2. Build the plugin with `mvn package`.
3. Copy `target/anmj-morph-plus-0.1.0-SNAPSHOT.jar` into Fiji's `plugins/` directory and restart Fiji or refresh menus.
4. Run **Analyze > Tools > aNMJ-morph+**.

Developers can also install a build directly with `mvn -Dscijava.app.directory=/path/to/Fiji.app/`.

The root `aNMJ-morph macro.txt` remains in the repository as a historical/scientific parity reference. It is not packaged into the plugin JAR and is not invoked at runtime.

Java owns single-image selection, batch traversal/session orchestration, supported-format routing, loading, structural normalization, interactive review, measurements, segmentation, cleaned-image output, and the 29-column CSV. RGB conversion, Z projection, channel arrangement/splitting, thresholding, morphology operations, particle analysis, and related processing use Fiji/ImageJ implementations directly. TIFF inputs use ImageJ directly; microscopy formats that require it use Bio-Formats.

## Usage

### Single image

Run **Analyze > Tools > aNMJ-morph+**. If no image is open, choose **Single image** and select the file. You can also open an NMJ image first and then run the command.

Then:

1. Resolve the dimensionality prompt if the input is ambiguous.
2. Select the muscle-endplate and nerve-terminal channels.
3. Follow the seven on-screen steps for thresholding, axon measurement/cleanup, segmentation review, and output.

### Batch mode

Run **Analyze > Tools > aNMJ-morph+** with **no image open**, choose **Batch folder**, and select the directory. Java searches recursively, skips generated `cleaned_images` directories, and processes supported images one at a time. A file error is logged and the batch continues; cancelling an interactive step stops the batch.

Batch mode remains interactive. Channel selection, ambiguous two-plane interpretation, threshold review, axon measurements/cleanup, and segmentation review are performed for each image.

Supported extensions:

`.tif`, `.tiff`, `.lsm`, `.nd2`, `.czi`, `.lif`, `.png`, `.jpg`, `.jpeg`, `.bmp`

TIFF files are opened directly with ImageJ; microscopy formats that require it are opened through Bio-Formats.

## Input handling

| Input | Behavior |
| --- | --- |
| Multi-channel image | User selects the muscle-endplate and nerve-terminal channels. |
| RGB image | Converted to separate channels before channel selection. |
| `C=1, Z=2, T=1` | Prompts for either a two-channel Keyence/two-page interpretation or a real Z stack. |
| Real Z stack | Maximum-intensity projected before the 2D workflow. |
| `T > 1` | Rejected; reduce to one time point before analysis. |

The analysis image, threshold-reference copy, and segmentation copy all use the same selected channel ordering.

## Outputs

The plugin writes:

- `raw_data_table.csv` — quantitative measurements and derived spreadsheet formulas.
- `cleaned_images/` — thresholded/intermediate TIFF images used by the workflow.

The repository also retains the original tutorial video, 20 reference NMJ images, and the reference spreadsheet distributed with the Edinburgh DataShare dataset [3].

### Branch analysis and rectangular-image compatibility

The original macro estimated total branch length from the number of foreground skeleton pixels and estimated branch points from a fixed correction applied to junction-pixel counts. aNMJ-morph+ now delegates those measurements to Fiji Analyze Skeleton: total branch length is the sum of calibrated graph-edge lengths, so horizontal, vertical, and diagonal steps use their actual X/Y physical calibration; terminal branches remain the original biological quantity of degree-1 terminal tips, with isolated one-pixel components excluded; and branch points are the grouped graph junction count rather than the historical `0.28` pixel heuristic.

The CSV remains 29 columns wide. The four former Binary Connectivity diagnostic columns are now `Skeleton Trees`, `Terminal Tips`, `Triple Junctions`, and `Quadruple Junctions`. Because the branch method is intentionally corrected, branch length and branch-point values are not numerically identical to historical aNMJ-morph output. On the pinned NMJ_1 reference, terminal branches remain 99, branch points change from 45.36 to 49, and total length changes from 292.70164895 µm to 331.89935592 µm.

The plugin still warns on unequal X/Y sampling because pixel-domain preprocessing remains dependent on acquisition sampling density even though Analyze Skeleton reports calibrated geometry. The first two CSV metadata columns contain `width x height` values rather than a single scalar side length, so downstream scripts that parse those columns may need to be updated.

## Validation and scientific use

The original aNMJ-morph implementation was experimentally validated against NMJ-morph [1,2]. The fork-specific changes—including rectangular-image support, RGB/channel normalization, Keyence two-page interpretation, revised Z handling, and broader batch I/O—have not independently been validated on a new biological dataset.

For publication-grade use, validate the modified workflow on representative images from your acquisition pipeline and compare key outputs against the original workflow or manually verified measurements.

## Citation

If you use **aNMJ-morph+**, cite this repository **in addition to** the original aNMJ-morph work. GitHub can generate citation formats from `CITATION.cff` using **Cite this repository**.

**aNMJ-morph+ software**

Abdullah Alzeiby. *aNMJ-morph+*. https://github.com/alzeiby/aNMJ-morph-plus

Original method and supporting references:

1. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology. *Royal Society Open Science*. 2020;7:200128. https://doi.org/10.1098/rsos.200128
2. **Jones RA, Reich CD, Dissanayake KN, et al.** NMJ-morph reveals principal components of synaptic morphology influencing structure-function relationships at the neuromuscular junction. *Open Biology*. 2016;6:160240. https://doi.org/10.1098/rsob.160240
3. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph macro [dataset]. Edinburgh DataShare, University of Edinburgh. 2019. https://doi.org/10.7488/ds/2625
4. **Landini G.** Advanced shape analysis with ImageJ. *Proceedings of the Second ImageJ User and Developer Conference*. 2008;116-121.
5. **Schindelin J, Arganda-Carreras I, Frise E, et al.** Fiji: an open-source platform for biological-image analysis. *Nature Methods*. 2012;9:676-682. https://doi.org/10.1038/nmeth.2019

## Development

GitHub Actions checks the repository on pushes and pull requests, including the rectangular-image formula, dimensionality guards, channel ordering, TIFF batch support, output naming, bundled reference images, licensing attribution, citation metadata, Java packaging, and the pinned fresh-Fiji numerical oracle.

Tags matching `v*` validate the repository and build a release archive containing the installable Java plugin JAR, README, `LICENSE`, `CITATION.cff`, reference spreadsheet, reference images, and a SHA-256 checksum. The historical IJM reference remains in the source repository rather than the runtime release package.

## License

The original aNMJ-morph material is distributed under **CC BY 4.0**. The aNMJ-morph+ adaptation, modifications, and additions are authored by **Abdullah Alzeiby** and are also made available under **CC BY 4.0** unless otherwise noted.

See [`LICENSE`](LICENSE) for attribution, provenance, and license terms.
