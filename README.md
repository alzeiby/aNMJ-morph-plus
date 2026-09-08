# aNMJ-morph+

A maintained adaptation of **aNMJ-morph** by **Abdullah Alzeiby**, based on the Fiji/ImageJ macro for rapid quantitative analysis of neuromuscular junction (NMJ) morphology described by Minty et al. (2020).

This repository keeps the original aNMJ-morph workflow and reference material while adding targeted fixes, safer image handling, automated validation, and documentation. It is **not the official upstream aNMJ-morph distribution**.

## What this macro does

aNMJ-morph guides the user through a seven-step Fiji/ImageJ workflow and writes a CSV containing the same 19 NMJ morphology variables described in the original method. The published aNMJ-morph workflow was designed to automate the earlier NMJ-morph protocol while retaining manual control of biologically sensitive steps such as threshold selection and axon processing [1,2].

The measured/derived variables include:

- **Pre-synaptic:** nerve terminal area, nerve terminal perimeter, number of terminal branches, number of branch points, total branch length, average branch length, and complexity.
- **Post-synaptic:** AChR area, AChR perimeter, endplate area, endplate perimeter, endplate diameter, number of AChR clusters, average AChR cluster area, fragmentation, compactness, overlap, and area of synaptic contact.
- **Associated nerve measurement:** axon diameter.

The original publication reports strong concordance with the parent NMJ-morph workflow and a substantial reduction in analysis time [1].

## Improvements in aNMJ-morph+

### Arbitrary image width and height

The original macro assumes a square image when calculating frame dimensions and total branch length. In particular, it stores one side length and later treats the image area as `side × side`.

This fork tracks `width` and `height` independently and generalizes total branch length to:

```text
(width × height - background skeleton pixels) × calibrated pixel size
```

For square images this is mathematically equivalent to the original calculation while allowing rectangular images to be analyzed without substituting one dimension for the other. The macro now writes pixel and calibrated frame dimensions as `width x height` strings in the first two CSV columns instead of the original scalar side-length values, so scripts that parse those two metadata columns should be updated accordingly.

**Important:** the branch-length calculation still assumes isotropic X/Y pixel calibration. The macro warns when X and Y pixel sizes differ; anisotropic calibration should be validated before quantitative use.

### Safer image dimensionality handling

The maintained macro also includes targeted image-I/O fixes used for lab workflows:

- RGB images can be converted into separate channels before channel selection.
- A one-channel, two-plane image is treated as **ambiguous** rather than being silently reinterpreted. The macro asks whether the planes are two channels (for example, a two-page Keyence export) or a real Z stack that should be maximum-projected.
- Images with `T > 1` are rejected rather than silently analyzing a single time point.
- Real Z stacks are maximum-intensity projected before the downstream 2D morphology analysis.
- The analysis image, threshold-reference copy, and segmentation copy all use the same user-selected channel ordering.

## Requirements

- [Fiji](https://fiji.sc/) / ImageJ
- **Binary Connectivity** from Gabriel Landini's Morphological Operators for ImageJ [1,4]

The original aNMJ-morph study used Fiji/ImageJ and the Binary Connectivity plugin and validated the workflow on Zeiss `.lsm` and Nikon `.nd2` confocal images [1]. The authors recommend validating other image formats and acquisition settings against the original NMJ-morph workflow before relying on them quantitatively [1,2].

## Installation

1. Install Fiji.
2. Install the Binary Connectivity plugin from Gabriel Landini's Morphological Operators for ImageJ.
3. Download or clone this repository.
4. Open `aNMJ-morph macro.txt` in Fiji's macro editor and run it.

The macro also expects Fiji's standard `StartupMacros.fiji.ijm` file in the Fiji macros directory because it reinstalls the startup macros when launched.

## Usage

### Single image

1. Open the NMJ image in Fiji.
2. Run `aNMJ-morph macro.txt`.
3. Resolve any dimensionality prompt if the input is ambiguous.
4. Select the muscle-endplate and nerve-terminal channels when prompted.
5. Follow the seven on-screen steps for thresholding, axon measurement/cleanup, segmentation review, and output.

### Batch mode

Run the macro with no image open. It will prompt for an image directory and process supported image files recursively while skipping generated `cleaned_images` folders.

Supported batch extensions are `.tif`, `.tiff`, `.lsm`, `.nd2`, `.czi`, `.lif`, `.png`, `.jpg`, `.jpeg`, and `.bmp`. TIFF files are opened with ImageJ directly; other supported microscopy formats are opened with Bio-Formats. Batch mode remains interactive because the workflow still requires channel selection, threshold review, and the same ambiguity prompt used in single-image mode.

### Output

The macro creates:

- `raw_data_table.csv` — quantitative output and derived spreadsheet formulae.
- `cleaned_images/` — thresholded/intermediate TIFF images used by the workflow.

The repository also contains the original tutorial video, 20 reference NMJ images, and the reference spreadsheet distributed with the Edinburgh DataShare dataset [3].

## Automated validation and releases

GitHub Actions runs repository checks on pushes and pull requests. The checks cover the rectangular-image formula, ambiguous C/Z handling, time-series rejection, consistent channel ordering, TIFF batch support, diagnostic output, historical output naming, the bundled reference-image count, licensing attribution, citation metadata, and basic macro delimiter/string integrity.

Tags matching `v*` run the same validation and then publish a GitHub release containing the macro, README, `LICENSE`, `CITATION.cff`, reference spreadsheet, reference images, and a SHA-256 checksum.

## Scientific-use note

The **original** aNMJ-morph implementation was experimentally validated against NMJ-morph [1]. The changes in this fork—including rectangular-image support, RGB/channel normalization, two-page Keyence interpretation, Bio-Formats batch opening, and revised Z handling—have not, by themselves, been independently validated on a new biological dataset.

For publication-grade use, validate the modified workflow on representative images from your acquisition pipeline and compare key outputs against either the original workflow or manually verified measurements. This follows the validation approach recommended by the aNMJ-morph authors when changing file types or imaging conditions [1].

## Attribution and citations

**aNMJ-morph+ is adapted and maintained by Abdullah Alzeiby.** The modifications and additions specific to this repository are attributed to Abdullah Alzeiby; the original aNMJ-morph material remains attributed to its original creators.

If you use **aNMJ-morph+** in research, please cite this repository **in addition to** the original aNMJ-morph work below. GitHub can generate citation formats from `CITATION.cff` via the repository's **Cite this repository** interface.

**aNMJ-morph+ software:** Abdullah Alzeiby. *aNMJ-morph+*. GitHub repository. https://github.com/alzeiby/aNMJ-morph-plus

This repository is derived from the aNMJ-morph macro and supporting dataset created by Minty, Hoppen, Boehm, and colleagues at the University of Edinburgh. Cite the original aNMJ-morph paper and dataset when using the derived workflow or included reference material. If the NMJ-morph methodology is central to the work, cite the parent method as well.

1. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology. *Royal Society Open Science*. 2020;7:200128. https://doi.org/10.1098/rsos.200128
2. **Jones RA, Reich CD, Dissanayake KN, et al.** NMJ-morph reveals principal components of synaptic morphology influencing structure-function relationships at the neuromuscular junction. *Open Biology*. 2016;6:160240. https://doi.org/10.1098/rsob.160240
3. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph macro [dataset]. Edinburgh DataShare, University of Edinburgh. 2019. https://doi.org/10.7488/ds/2625
4. **Landini G.** Advanced shape analysis with ImageJ. *Proceedings of the Second ImageJ User and Developer Conference*. 2008;116-121. Binary Connectivity is distributed with Landini's Morphological Operators for ImageJ: https://blog.bham.ac.uk/intellimic/g-landini-software/
5. **Schindelin J, Arganda-Carreras I, Frise E, et al.** Fiji: an open-source platform for biological-image analysis. *Nature Methods*. 2012;9:676-682. https://doi.org/10.1038/nmeth.2019

## License and provenance

The original Edinburgh DataShare distribution uses the Creative Commons Attribution 4.0 International license. This repository consolidates the applicable license, original-source attribution, modification notice, and links to the complete CC BY 4.0 terms in `LICENSE`.

The aNMJ-morph+ adaptation, modifications, and additions are authored by **Abdullah Alzeiby** and are also made available under **CC BY 4.0** unless otherwise noted.

When redistributing aNMJ-morph+, preserve attribution to both the original creators and Abdullah Alzeiby, link to the original source dataset and this repository, identify that changes were made, and retain the applicable license information.
