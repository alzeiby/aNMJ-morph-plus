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

1. [Download the plugin JAR](https://github.com/alzeiby/aNMJ-morph-plus/raw/refs/tags/v0.1.0/dist/anmj-morph-plus-0.1.0.jar) from the [`v0.1.0` tag](https://github.com/alzeiby/aNMJ-morph-plus/tree/v0.1.0/dist).
2. Copy the JAR into Fiji's `plugins/` directory.
3. Restart Fiji.
4. Run **Analyze > Tools > aNMJ-morph+**.

To build from source, run `mvn package`.

## Usage

### Single image

Run **Analyze > Tools > aNMJ-morph+** with an image already open, or choose **Single image** when prompted.

Draw a rectangle in the crop step, then click Continue. Leave the image unselected to skip cropping. You can still use the image and Fiji tools while a review dialog is open.

If the image has no physical calibration, choose pixels or enter the X/Y pixel sizes in microns. The channel prompt remembers your last muscle and nerve selections; confirm or change them for each image.

During thresholding, the unedited reference image is on the left and the working image is on the right. Adjust the threshold and remove unwanted background from the working image only.

The brush starts at 12 pixels unless you've selected another size. Double-click the Paintbrush tool to change its width. The segmentation preview separates touching regions in the cleaned endplate mask; uncheck **Image looks OK** if the result is unsuitable.

For axon diameter, draw and measure three lines in order: maximum width, minimum width, then hillock width (where the axon meets the terminal). Press `M` after each line. The plugin averages those three measurements; it does not determine which is which from their lengths. If no axon is present, leave the Results table empty.

### Batch mode

Run the plugin with no image open, choose **Batch folder**, and select a directory. Supported images are found recursively and processed one at a time.

Batch mode uses the same steps for each image. If a file fails, processing continues with the next one. Cancel stops the batch.

Supported extensions:

`.tif`, `.tiff`, `.lsm`, `.nd2`, `.czi`, `.lif`, `.png`, `.jpg`, `.jpeg`, `.bmp`

## Input handling

| Input | Behavior |
| --- | --- |
| Multi-channel image | Prompts for muscle-endplate and nerve-terminal channels, defaulting to the last saved selection. |
| RGB image | Converted to separate channels before selection. |
| `C=1, Z=2, T=1` | Prompts to treat the planes as two channels or as a Z stack. |
| Z stack | Maximum-intensity projected before the 2D workflow. |
| Physical calibration | X/Y units are converted to microns for physical measurements. |
| Uncalibrated image (`pixel` units) | Measure in pixels or enter the X/Y microns per pixel. |
| Optional crop | Draw a rectangle and click Continue, or leave the image unselected to skip. |
| `T > 1` | Rejected. Reduce the image to one time point first. |

## Output

Each analysis writes:

- `raw_data_table.csv` with measurements in microns, or `raw_data_table_pixels.csv` for pixel measurements
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
