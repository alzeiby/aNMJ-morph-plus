from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MACRO_PATH = ROOT / "aNMJ-morph macro.txt"
MACRO = MACRO_PATH.read_text(encoding="utf-8")
RUNTIME_VALIDATION_PATH = ROOT / "tests" / "runtime" / "run_validation.ps1"
RUNTIME_VALIDATION = RUNTIME_VALIDATION_PATH.read_text(encoding="utf-8")
FAILCLOSED_RUNNER_PATH = ROOT / "tests" / "runtime" / "run_source_copy_failclosed_probes.ps1"
FAILCLOSED_BUILDER_PATH = ROOT / "tests" / "runtime" / "build_source_copy_failclosed_probes.py"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def check_balanced_delimiters(text: str) -> None:
    pairs = {")": "(", "]": "[", "}": "{"}
    opening = set(pairs.values())
    stack = []
    in_string = False
    escaped = False
    in_line_comment = False
    i = 0

    while i < len(text):
        char = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""

        if in_line_comment:
            if char == "\n":
                in_line_comment = False
            i += 1
            continue

        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            i += 1
            continue

        if char == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if char == '"':
            in_string = True
            i += 1
            continue

        if char in opening:
            stack.append(char)
        elif char in pairs:
            require(stack and stack[-1] == pairs[char], f"Unbalanced delimiter near character {i}: {char}")
            stack.pop()
        i += 1

    require(not in_string, "Unterminated string literal")
    require(not stack, f"Unclosed delimiter(s): {stack}")


def main() -> None:
    require(MACRO_PATH.exists(), "Macro file is missing")
    check_balanced_delimiters(MACRO)

    # fiji.bat is only a launcher wrapper; its exit must not end trace polling
    # before the Fiji child has reached the requested completion marker.
    poll_start = RUNTIME_VALIDATION.index("while ((Get-Date) -lt $deadline) {")
    post_poll_refresh = RUNTIME_VALIDATION.index("$process.Refresh()", poll_start)
    poll_region = RUNTIME_VALIDATION[poll_start:post_poll_refresh]
    require(
        "$process.HasExited" not in poll_region,
        "Runtime validation must not inspect wrapper HasExited while polling for Fiji completion",
    )
    require(
        RUNTIME_VALIDATION.count("$process.HasExited") == 1
        and "if (-not $process.HasExited)" in RUNTIME_VALIDATION[post_poll_refresh:],
        "Runtime validation must use wrapper HasExited only for post-poll cleanup",
    )
    require(
        "while ((Get-Date) -lt $deadline)" in RUNTIME_VALIDATION
        and 'if ($traceText -like "*$CompletionMarker*")' in RUNTIME_VALIDATION,
        "Runtime validation must remain completion-marker/deadline driven",
    )
    require(
        "Start-Process -FilePath $fijiLauncher" in RUNTIME_VALIDATION
        and "-PassThru -WindowStyle Hidden" in RUNTIME_VALIDATION,
        "Runtime validation must continue launching Fiji through the hidden wrapper",
    )
    require(FAILCLOSED_RUNNER_PATH.exists(), "Source-copy fail-closed fresh-Fiji runner is missing")
    require(FAILCLOSED_BUILDER_PATH.exists(), "Source-copy fail-closed probe builder is missing")
    failclosed_runner = FAILCLOSED_RUNNER_PATH.read_text(encoding="utf-8")
    require("-WindowStyle Hidden" in failclosed_runner, "Source-copy fail-closed probes must launch hidden")
    require("'--headless'" in failclosed_runner, "Source-copy fail-closed probes must use Fiji headless mode")
    require("FALLBACK DUPLICATE RAN" in failclosed_runner, "Source-copy fail-closed runner does not reject fallback execution")
    require("UNEXPECTED RETURN" in failclosed_runner, "Source-copy fail-closed runner does not reject parser return")
    require("Assert-NoPinnedFijiProcess" in failclosed_runner, "Source-copy fail-closed runner does not prove Fiji cleanup")

    # Rectangular-image calculation must use width * height, not the original square assumption.
    require(
        "totalLengthOfBranches = (imageWidth * imageHeight - counts0) * pixelSizeX;" in MACRO,
        "Rectangular branch-length calculation is missing",
    )
    require(
        "(A' + rowNumber + '*A' + rowNumber" not in MACRO,
        "Legacy square-only branch-length spreadsheet formula returned",
    )

    # Dimensionality handling must not silently reinterpret ambiguous data.
    require("if (frames > 1)" in MACRO, "Time-series inputs are not explicitly rejected")
    require("Two-plane image detected" in MACRO, "Ambiguous C=1/Z=2 inputs are not surfaced to the user")
    require("Two channels (Keyence/two-page export)" in MACRO, "Keyence two-page interpretation option is missing")
    require("Z stack (maximum-project)" in MACRO, "Z-stack interpretation option is missing")

    # The primary, threshold template, and segmentation copy must use the same channel ordering.
    arrange_call = 'run("Arrange Channels...", "new=" + muscleEndplateChannel + \'\' + nerveTerminalChannel);'
    require(MACRO.count(arrange_call) == 3, "Expected exactly three legacy channel-ordering calls")
    require(
        'channelsCanonical = indexOf(";" + javaArgument + ";", ";channels-canonical=1;") >= 0;' in MACRO,
        "Java canonical-channel bridge flag is not parsed",
    )
    require(
        MACRO.count("if (!channelsCanonical) {") == 3,
        "All three legacy channel-ordering calls must be skipped only for Java-canonical batch input",
    )

    # Batch processing must support TIFF rather than excluding it globally.
    require('endsWith(lowerName, ".tif")' in MACRO, "TIFF is not included in supported batch formats")
    require('endsWith(lowerName, ".tiff")' in MACRO, "TIFF extension variant is not supported")
    require('if (!endsWith(fileName, ".tif"))' not in MACRO, "Legacy TIFF-wide batch exclusion returned")
    require("cleaned_images" in MACRO, "Generated output directory is not excluded from recursive batch traversal")

    # Batch and CSV I/O should avoid unnecessary parsing and rewriting work.
    require("function hasSupportedImageExtension" not in MACRO, "ImageJ1 boolean extension helper must not be reintroduced")
    require("function openImageFile(fileName)" in MACRO, "Image opening is not centralized")
    require(
        'if (endsWith(lowerName, ".tif") || endsWith(lowerName, ".tiff"))' in MACRO,
        "TIFF batch inputs are not using the established native ImageJ open path",
    )
    require(MACRO.count('openImageFile(fileName);') >= 2, "Batch/single-image paths are not both routed through the common image opener")
    for extension in (".lsm", ".nd2", ".czi", ".lif", ".png", ".jpg", ".jpeg", ".bmp"):
        require(
            f'endsWith(lowerName, "{extension}")' in MACRO,
            f"Supported image extension is missing from inline ImageJ routing: {extension}",
        )
    require('Dialog.addChoice("Analyze", newArray("Single image", "Batch folder"));' in MACRO, "Single-image file selection mode is missing")
    require('fileName = File.openDialog("Select image to analyze");' in MACRO, "Single-image file picker is missing")
    require('originalTitle = File.getName(getTitle());' in MACRO, "Bio-Formats titles are not normalized to a basename")
    require('rename(originalTitle);' in MACRO, "Normalized Bio-Formats basename is not applied to the image window")
    require('inputTitle = File.getName(originalTitle);' in MACRO, "Stable input basename is not preserved for output naming")
    require('inputTitle + columnSeparator +' in MACRO, "CSV image name is not based on the stable input basename")
    require('makeTiffFilename("axon_terminal", inputTitle)' in MACRO, "Axon output filename is not based on the stable input basename")
    require('makeTiffFilename("muscle_endplate", inputTitle)' in MACRO, "Endplate output filename is not based on the stable input basename")
    require('rename(axonFilename);' in MACRO, "Reopened axon TIFF is not assigned its stable window title")
    require('rename(endplateFilename);' in MACRO, "Reopened endplate TIFF is not assigned its stable window title")
    require('rename(endplateIntermediateFilename);' in MACRO, "Reopened intermediate TIFF is not assigned its stable window title")
    require("canOpenDirectly" not in MACRO, "Non-TIFF native-open shortcut should not bypass Bio-Formats")
    require('File.append(output, outputFilename);' in MACRO, "CSV rows are not appended incrementally")
    require("File.saveString(fileContents + output, outputFilename);" not in MACRO, "CSV output still rewrites the entire existing file")
    require("fileLength = File.length(outputFilename);" in MACRO, "Existing CSV length is not recorded")
    require("if (fileLength > 0)" in MACRO, "Empty output files are not handled explicitly")
    require('File.openAsRawString(outputFilename, fileLength)' in MACRO, "CSV newline detection is not using physical file contents")
    require('File.openAsString(outputFilename)' not in MACRO, "Normalized text reads cannot detect a missing final newline")
    require('File.append("\\n", outputFilename);' not in MACRO, "CSV separator append would insert a blank row")
    require('File.append("", outputFilename);' in MACRO, "Legacy CSV rows without a newline are not terminated safely")
    require('File.saveString(line1 + "\\n" + line2 + "\\n", outputFilename);' in MACRO, "Missing/empty CSV header initialization changed")
    require("rowNumber = 3;" in MACRO, "First data row should remain spreadsheet row 3")

    # Multiple-open-image mode must explicitly select the image it passes to processOpenImage.
    require("safeSelectWindow(list[0]);" in MACRO, "The first listed image is not explicitly selected before analysis")

    # Avoid unnecessary UI and measurement work in the interactive path.
    require(
        'if (nImages > 0 && getInfo("window.title") == windowTitle && getTitle() == windowTitle)' in MACRO,
        "Redundant window selections are not short-circuited using the front-most window",
    )
    require(
        "if (getTitle() == windowTitle)" not in MACRO,
        "Window short-circuit must not use the current image title for non-image windows",
    )
    require("getLocationAndSize(x2, y2, width2, height2);" not in MACRO, "Unused template-window geometry query returned")
    require("resultsArray = newArray(resultsCount);" in MACRO, "Axon measurement array is not preallocated")
    require("Array.concat(resultsArray" not in MACRO, "Axon measurements still reallocate the array on each result")

    # Stateful ImageJ channel operations must target deterministic image IDs.
    require("originalImageId = getImageID();" in MACRO, "Original image ID is not captured before duplication")
    require("sourceCopyId = getImageID();" in MACRO, "Source-copy image ID is not captured")
    require('sourceCopyPrefix = ";source-copy-id=";' in MACRO, "Java source-copy bridge is missing")
    require("sourceCopyId = suppliedSourceCopyId;" in MACRO, "Supplied Java source-copy ID is not adopted")
    require(
        MACRO.count('exit("Error: Invalid source-copy-id supplied by Java");') >= 2,
        "Invalid supplied source-copy IDs are not rejected fail-closed",
    )
    require(
        MACRO.count('run("Duplicate...", "title=[" + sourceCopyTitle + "] duplicate");') == 1,
        "Legacy initial source-copy Duplicate fallback changed",
    )
    require("templateImageId = getImageID();" in MACRO, "Template image ID is not captured")
    require("segmentImageId = getImageID();" in MACRO, "Segmentation image ID is not captured")
    require(MACRO.count("selectImage(originalImageId);") >= 3, "Original analysis flow is not image-ID selected")
    require(MACRO.count("selectImage(templateImageId);") >= 2, "Template Arrange/Split flow is not image-ID selected")
    require(MACRO.count("selectImage(segmentImageId);") >= 2, "Stage-6 Arrange/Split flow is not image-ID selected")
    require(
        'imageDimensionsPixels = "" + imageWidth + " x " + imageHeight;' in MACRO,
        "Pixel dimensions do not force ImageJ string context",
    )
    require(
        'imageDimensionsMetric = "" + formatNumber(metricWidth) + " x " + formatNumber(metricHeight) + sizeUnit;' in MACRO,
        "Metric dimensions do not force ImageJ string context",
    )

    # Diagnostic mode must remain useful.
    diagnostic_markers = [
        "Axon diameter:",
        "Nerve terminal area:",
        "Nerve terminal perimeter:",
        "AChR area:",
        "AChR perimeter:",
        "Endplate diameter:",
        "Endplate area:",
        "Endplate perimeter:",
        "Unoccupied AChR Area:",
        "Number of clusters:",
    ]
    for marker in diagnostic_markers:
        require(marker in MACRO, f"Diagnostic output missing: {marker}")

    # Preserve historical cleaned-image filename prefixes.
    require('makeTiffFilename("axon_terminal", inputTitle)' in MACRO, "Axon output filename prefix changed")
    require('makeTiffFilename("muscle_endplate", inputTitle)' in MACRO, "Endplate output filename prefix changed")

    reference_images = sorted((ROOT / "Reference Images").glob("*.lsm"))
    require(len(reference_images) == 20, f"Expected 20 reference LSM images, found {len(reference_images)}")

    license_text = (ROOT / "LICENSE").read_text(encoding="utf-8", errors="replace")
    require("Creative Commons Attribution 4.0 International" in license_text, "CC BY 4.0 license notice is missing")
    require("https://doi.org/10.7488/ds/2625" in license_text, "Original dataset attribution is missing from LICENSE")
    require("Abdullah Alzeiby" in license_text, "aNMJ-morph+ author attribution is missing from LICENSE")

    citation_path = ROOT / "CITATION.cff"
    require(citation_path.exists(), "CITATION.cff is missing")
    citation_text = citation_path.read_text(encoding="utf-8", errors="replace")
    require('family-names: "Alzeiby"' in citation_text, "CITATION.cff is missing the aNMJ-morph+ author")
    require('given-names: "Abdullah"' in citation_text, "CITATION.cff is missing the aNMJ-morph+ author")
    require("https://github.com/alzeiby/aNMJ-morph-plus" in citation_text, "CITATION.cff repository URL is missing")

    print("Repository checks passed")


if __name__ == "__main__":
    main()
