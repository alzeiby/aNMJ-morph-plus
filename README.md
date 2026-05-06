# aNMJ-morph+

aNMJ-morph+ is a Fiji/ImageJ plugin for quantitative neuromuscular junction (NMJ) morphology analysis. It is based on the workflow described in the original [aNMJ-morph paper](https://doi.org/10.1098/rsos.200128), with changes for newer image formats and a Java-based Fiji workflow.

## What it changes

Compared with the original macro, aNMJ-morph+:

- is more accurate
- is faster
- supports N x M sized images
- supports RGB images and Z stacks
- supports interactive batch processing

## Requirements

- [Fiji](https://fiji.sc/)

## Installation

1. Build the plugin with `mvn package`.
2. Copy `target/anmj-morph-plus-0.1.0.jar` into Fiji's `plugins/` directory.
3. Restart Fiji or refresh the menus.
4. Run **Analyze > Tools > aNMJ-morph+**.

For development builds, Maven can install directly into Fiji:

```bash
mvn -Dscijava.app.directory=/path/to/Fiji.app/
```

## Usage

### Single image

Run **Analyze > Tools > aNMJ-morph+** with an image already open, or choose **Single image** when prompted.

The plugin will ask you to:

1. resolve ambiguous two-plane inputs when necessary
2. choose the muscle-endplate and nerve-terminal channels
3. review thresholding, axon cleanup, segmentation, and measurements

### Batch mode

Run the plugin with no image open, choose **Batch folder**, and select a directory. Supported images are found recursively and processed one at a time.

Batch mode is still interactive: channel assignment, threshold review, cleanup, and segmentation review happen for each image. If one file fails, the batch continues; cancelling an interactive step stops the batch.

Supported extensions:

`.tif`, `.tiff`, `.lsm`, `.nd2`, `.czi`, `.lif`, `.png`, `.jpg`, `.jpeg`, `.bmp`

## Input handling

| Input | Behavior |
| --- | --- |
| Multi-channel image | Prompts for muscle-endplate and nerve-terminal channels. |
| RGB image | Converted to separate channels before selection. |
| `C=1, Z=2, T=1` | Prompts to treat the planes as two channels or as a Z stack. |
| Z stack | Maximum-intensity projected before the 2D workflow. |
| Physical calibration | X/Y units are converted to microns; unknown or uncalibrated units are rejected. |
| `T > 1` | Rejected. Reduce the image to one time point first. |

## Output

Each analysis writes:

- `raw_data_table.csv` with measurements and derived values
- `cleaned_images/` with cleaned nerve-terminal and muscle-endplate TIFFs

The threshold field also stores the selected channel numbers, threshold method, and accepted bounds.

## Branch measurements

The original aNMJ-morph macro estimated total branch length from skeleton pixels and applied a fixed correction to junction-pixel counts.

aNMJ-morph+ instead uses skeletonization. Total branch length is the sum of calibrated graph-edge lengths, terminal branches are degree-1 tips excluding isolated one-pixel components, and branch points are grouped graph junctions.

## Citation

If you use aNMJ-morph+, cite this repository together with the aNMJ-morph paper. GitHub can generate software citation formats from `CITATION.cff`.

1. Abdullah Alzeiby. *aNMJ-morph+*. https://github.com/alzeiby/aNMJ-morph-plus
2. Minty G, Hoppen A, Boehm I, et al. aNMJ-morph: a simple macro for rapid analysis of neuromuscular junction morphology. *Royal Society Open Science*. 2020;7:200128. https://doi.org/10.1098/rsos.200128

## Development

Build with:

```bash
mvn package
```

## License

The code in this repository is licensed under the [MIT License](LICENSE). Third-party dependencies retain their own licenses.
