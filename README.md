# aNMJ-morph+

A maintained adaptation of **aNMJ-morph**, the Fiji/ImageJ macro for rapid quantitative analysis of neuromuscular junction (NMJ) morphology described by Minty et al. (2020).

This repository keeps the original aNMJ-morph workflow and reference material while adding targeted fixes and documentation. It is **not the official upstream aNMJ-morph distribution**.

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

For square images this is algebraically equivalent to the original calculation, so the change preserves the original result while allowing rectangular images to be analyzed without substituting one dimension for the other.

**Important:** the macro still warns when X and Y pixel calibration differ. Rectangular images with isotropic pixels are supported; anisotropic pixel calibration remains a separate limitation that should be validated before quantitative use.

See [`VALIDATION.md`](VALIDATION.md) for the regression argument and a suggested validation protocol.

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
3. Select the muscle-endplate and nerve-terminal channels when prompted.
4. Follow the seven on-screen steps for thresholding, axon measurement/cleanup, segmentation review, and output.

### Batch mode

Run the macro with no image open. It will prompt for an image directory and process images recursively using the original batch-processing behavior.

### Output

The macro creates:

- `raw_data_table.csv` — quantitative output and derived spreadsheet formulae.
- `cleaned_images/` — thresholded/intermediate TIFF images used by the workflow.

The repository also contains the original tutorial video, 20 reference NMJ images, and the reference spreadsheet distributed with the Edinburgh DataShare dataset [3].

## Scientific-use note

The **original** aNMJ-morph implementation was experimentally validated against NMJ-morph [1]. The rectangular-image change in this fork is a mathematical/code correction to the frame-size assumption; it has not, by itself, been independently validated on a new biological dataset.

For publication-grade use, validate the modified workflow on representative images from your acquisition pipeline and compare key outputs against either the original workflow or manually verified measurements. This follows the validation approach recommended by the aNMJ-morph authors when changing file types or imaging conditions [1].

## Attribution and citations

This repository is derived from the aNMJ-morph macro and supporting dataset created by Minty, Hoppen, Boehm, and colleagues at the University of Edinburgh. If you use this code or the included reference material, cite the original aNMJ-morph paper and dataset. If the NMJ-morph methodology is central to the work, cite the parent method as well.

1. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology. *Royal Society Open Science*. 2020;7:200128. https://doi.org/10.1098/rsos.200128
2. **Jones RA, Reich CD, Dissanayake KN, et al.** NMJ-morph reveals principal components of synaptic morphology influencing structure-function relationships at the neuromuscular junction. *Open Biology*. 2016;6:160240. https://doi.org/10.1098/rsob.160240
3. **Minty G, Hoppen A, Boehm I, et al.** aNMJ-morph macro [dataset]. Edinburgh DataShare, University of Edinburgh. 2019. https://doi.org/10.7488/ds/2625
4. **Landini G.** Advanced shape analysis with ImageJ. *Proceedings of the Second ImageJ User and Developer Conference*. 2008;116-121. Binary Connectivity is distributed with Landini's Morphological Operators for ImageJ: https://blog.bham.ac.uk/intellimic/g-landini-software/
5. **Schindelin J, Arganda-Carreras I, Frise E, et al.** Fiji: an open-source platform for biological-image analysis. *Nature Methods*. 2012;9:676-682. https://doi.org/10.1038/nmeth.2019

See [`CITATION.md`](CITATION.md) for copy-ready citations.

## License and provenance

The original Edinburgh DataShare distribution includes a Creative Commons Attribution 4.0 International license; the corresponding license text is retained in this repository as `license_text`. The original DataShare depositor agreement is retained as `license.txt`.

When redistributing modified versions, preserve attribution to the original creators, link to the source dataset, identify that changes were made, and retain the applicable license information.
