# aNMJ-morph+

aNMJ-morph+ is a Fiji/ImageJ plugin for quantitative neuromuscular junction (NMJ) morphology analysis, based on the original **aNMJ-morph** workflow by Minty, Hoppen, Boehm, and colleagues.

This repository is an independent Java implementation maintained by Abdullah Alzeiby. It is not the official upstream aNMJ-morph distribution.

## What it changes

Compared with the original macro workflow, aNMJ-morph+ adds:

- rectangular-image support;
- explicit handling of RGB images, multi-channel images, and Z stacks;
- a prompt for ambiguous one-channel/two-plane inputs such as Keyence exports;
- rejection of time series (`T > 1`);
- recursive batch input;
- Bio-Formats loading for supported files;
- branch measurements from Fiji Analyze Skeleton rather than the historical pixel-count heuristic.

The interactive seven-step analysis workflow is otherwise preserved.

## Requirements

- [Fiji](https://fiji.sc/)
- Fiji's bundled **Analyze Skeleton** plugin

## Installation

1. Download the versioned `aNMJ-morph-plus-v*.jar` from GitHub Releases, or build the project with `mvn package`.
2. Copy the JAR into Fiji's `plugins/` directory.
3. Restart Fiji or refresh the menus.
4. Run **Analyze > Tools > aNMJ-morph+**.

For development builds, Maven can install directly into Fiji:

```bash
mvn -Dscijava.app.directory=/path/to/Fiji.app/
```

## Usage

### Single image

Run **Analyze > Tools > aNMJ-morph+**. With no image open, choose **Single image** and select a file. With an image already open, the plugin analyzes the current image.

The workflow then asks you to:

1. resolve ambiguous input dimensions when necessary;
2. choose the muscle-endplate and nerve-terminal channels;
3. review the two thresholds;
4. measure axon width;
5. erase the axon;
6. review the segmented endplate.

Thresholding starts from ImageJ's `Default` dark-background configuration for each image. The saved CSV records the selected channel numbers, threshold method, and accepted numeric bounds.

### Batch mode

With no image open, run the plugin and choose **Batch folder**. Files are processed recursively in a deterministic order. Generated `cleaned_images` directories are skipped.

Batch processing remains interactive. A file-level error is logged and processing continues; cancelling an interactive step stops the batch.

Supported extensions:

`.tif`, `.tiff`, `.lsm`, `.nd2`, `.czi`, `.lif`, `.png`, `.jpg`, `.jpeg`, `.bmp`

All supported files are opened through Bio-Formats.

## Input handling

| Input | Behavior |
| --- | --- |
| Multi-channel image | Choose the muscle-endplate and nerve-terminal channels. |
| RGB image | Convert to separate channels before channel selection. |
| `C=1, Z=2, T=1` | Choose between a two-channel interpretation and a real Z stack. |
| Real Z stack | Maximum-intensity projection before the 2D workflow. |
| Physical calibration | Convert X/Y length units to microns; reject pixel-only or unknown units. |
| `T > 1` | Reject the image. |

## Outputs

Each analysis writes:

- `raw_data_table.csv` — the 29-column measurement table used by aNMJ-morph;
- `cleaned_images/` — cleaned nerve-terminal and muscle-endplate TIFFs.

The first two metadata columns record image dimensions as `width x height`, so downstream code written for the original square-image assumption may need updating.

## Branch measurements

The original macro estimated branch length from skeleton foreground pixels and branch points from a fixed correction applied to junction pixels. aNMJ-morph+ uses Fiji Analyze Skeleton instead:

- total branch length is the sum of calibrated graph-edge lengths;
- terminal branches are degree-1 terminal tips, excluding isolated one-pixel components;
- branch points are grouped graph junctions.

The CSV is still 29 columns wide. Four former Binary Connectivity diagnostic columns are now `Skeleton Trees`, `Terminal Tips`, `Triple Junctions`, and `Quadruple Junctions`.

On the pinned `NMJ_1` reference image, the corrected branch analysis reports 99 terminal branches, 49 branch points, and 331.89935592 µm total branch length. The historical macro reports 99, 45.36, and 292.70164895 µm respectively.

Unequal X/Y sampling still triggers a warning because preprocessing is pixel-based even though branch geometry is calibrated.

## Validation

The repository includes a GitHub Actions regression run against a checksum-pinned Fiji build and the upstream `NMJ_1.lsm` reference image. It checks plugin discovery, input normalization, channel selection, calibration handling, threshold initialization, cancellation cleanup, output structure, and numerical results for square, rectangular, and anisotropic cases.

The original aNMJ-morph method was experimentally validated in the published work cited below. The changes in this repository have not been independently validated on a new biological dataset. For publication use, validate the workflow on representative images from your acquisition pipeline.

## Citation

If you use aNMJ-morph+, cite this repository and the original aNMJ-morph paper.

**Software**

Abdullah Alzeiby. *aNMJ-morph+*. https://github.com/alzeiby/aNMJ-morph-plus

**Original method**

1. Minty G, Hoppen A, Boehm I, et al. aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology. *Royal Society Open Science*. 2020;7:200128. https://doi.org/10.1098/rsos.200128
2. Jones RA, Reich CD, Dissanayake KN, et al. NMJ-morph reveals principal components of synaptic morphology influencing structure-function relationships at the neuromuscular junction. *Open Biology*. 2016;6:160240. https://doi.org/10.1098/rsob.160240
3. Minty G, Hoppen A, Boehm I, et al. aNMJ-morph macro [dataset]. Edinburgh DataShare, University of Edinburgh. 2019. https://doi.org/10.7488/ds/2625
4. Schindelin J, Arganda-Carreras I, Frise E, et al. Fiji: an open-source platform for biological-image analysis. *Nature Methods*. 2012;9:676-682. https://doi.org/10.1038/nmeth.2019

The original aNMJ-morph material is distributed through Edinburgh DataShare under CC BY 4.0. No original macro or reference image is distributed in this repository.

## Development

`mvn verify` builds the plugin. GitHub Actions also runs the pinned-Fiji regression and release-package checks. The regression downloads the upstream `NMJ_1` dataset at runtime and verifies its checksum before use.

Tags matching `v*` publish a versioned JAR, a ZIP containing the JAR and repository metadata, and a SHA-256 checksum.

## License

The code in this repository is licensed under the MIT License. See [`LICENSE`](LICENSE).
