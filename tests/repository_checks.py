from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def main() -> None:
    production_files = sorted((ROOT / "src" / "main" / "java").rglob("*.java"))
    production = "\n".join(path.read_text(encoding="utf-8") for path in production_files)
    pom = text("pom.xml")
    analysis = text("src/main/java/io/github/alzeiby/anmjmorphplus/AnalysisWorkflow.java")
    command = text("src/main/java/io/github/alzeiby/anmjmorphplus/ANMJMorphCommand.java")
    runtime = text("tests/runtime/run_direct_java_analysis.ps1")
    runtime_java = text("tests/runtime/DirectJavaAnalysisRuntime.java")
    fiji_ci = text(".github/workflows/fiji-runtime.yml")
    macro = text("aNMJ-morph macro.txt")

    # The IJM file remains a historical/scientific reference, never a runtime dependency.
    require("IJ.runMacro" not in production, "Production Java still invokes IJM")
    require("LegacyMacroRunner" not in production, "LegacyMacroRunner still exists in production")
    require("EarlySplitChannelsBridge" not in production, "Macro split bridge still exists in production")
    require("SourceCopyDuplicator" not in production, "Transition duplicate wrapper still exists in production")
    require("TemplateCopyDuplicator" not in production, "Transition duplicate wrapper still exists in production")
    require("aNMJ-morph macro.txt" not in pom, "Legacy macro is still packaged into the plugin JAR")

    # Single image, current image and batch mode now share one thin command shell.
    require("runBatch()" in command and "new AnalysisWorkflow().analyze" in command,
            "Command does not route interactive/batch inputs to AnalysisWorkflow")
    require("BF.openImagePlus" in command and "CompositeConverter.makeComposite" in command and
            'ZProjector.run(image, "max")' in command,
            "Input shell no longer delegates loading/normalization directly to Fiji APIs")
    for retired in ("BatchCheckpointStore", "BatchFileException", "BatchChoiceResolver", "BatchSessionRunner",
                    "ImageLoader", "InputWorkflowRunner", "SingleImageAnalysisRunner", "StructuralNormalizer",
                    "SupportedImageFormat", "InputNormalization", "TwoPlaneInterpretation"):
        require(retired not in production, f"Retired orchestration layer returned: {retired}")

    # Preserve the scientific method while delegating image operations to ImageJ/Fiji.
    for marker in (
        'IJ.run(nerve, "Threshold...", "")',
        'IJ.run(nerve, "Skeletonize", "")',
        'new RankFilters().rank(nerve.getProcessor(), 1.0, RankFilters.MEDIAN)',
        'new RankFilters().rank(muscle.getProcessor(), 1.0, RankFilters.MEDIAN)',
        'new BackgroundSubtracter().rollingBallBackground(',
        'new MaximumFinder().findMaxima(',
        'ThresholdToSelection.run(image)',
        'new ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE, 0, particles, 0, Double.POSITIVE_INFINITY).analyze(segmented)',
        'copyBits(intermediate.getProcessor(), 0, 0, Blitter.AND)',
    ):
        require(marker in analysis, f"Direct ImageJ/Fiji operation changed or disappeared: {marker}")

    require('IJ.run(segmentMuscle, "Find Maxima...' not in analysis and
            'ImageProcessor.NO_THRESHOLD' in analysis and 'MaximumFinder.SEGMENTED' in analysis,
            "Cluster segmentation returned to command/window state instead of explicit MaximumFinder semantics")
    require('"Create Selection"' not in analysis,
            "Automated mask measurements returned to command/global-window selection state")

    require("AnalyzeSkeleton_" in analysis and "edge.getLength()" in analysis,
            "Branch length is not measured from calibrated Analyze Skeleton graph edges")
    require("vertex.getBranches().size() == 1" in analysis and
            "getV1() != vertex.getBranches().get(0).getV2()" in analysis,
            "Terminal branches must count degree-1 graph tips while excluding isolated pixels and self-loops")
    require("sum(skeleton.getJunctions())" in analysis,
            "Branch points must use Analyze Skeleton's grouped junction count")
    require("BinaryConnectivity" not in production,
            "Production still depends on Binary Connectivity")
    require("AnisotropicSkeletonCalibration" not in production,
            "Custom anisotropic skeleton implementation returned")
    require("<artifactId>AnalyzeSkeleton_</artifactId>" in pom,
            "Analyze Skeleton dependency is missing")
    require("prompter.review(nerve, SCREEN5);\n        final double unoccupiedAchrArea = unoccupiedArea(muscle, nerve);" in analysis,
            "Overlap must use direct post-axon-erasure nerve-terminal occupancy")
    require("Concatenator" not in analysis and "openTiff" not in analysis,
            "Obsolete concatenate/TIFF-reopen analysis path returned")
    require("Fill Holes" not in analysis,
            "Known AChR-cluster undercounting Fill Holes step returned")
    require("source.getNChannels() <= 9" not in analysis,
            "Selected channels are still ignored for >9-channel images")
    require("new Duplicator().run(" in analysis and "source.getNSlices()" in analysis and
            'ZProjector.run(copy, "max")' in analysis and "projected.setDisplayRange(displayMin, displayMax)" in analysis and
            "ChannelArranger" not in analysis and "ChannelSplitter" not in analysis,
            "Selected channels must be extracted/projected directly while preserving threshold-review display scaling")
    require('image.getNChannels() <= 2' in command,
            "Many-channel Z stacks are again projecting unused channels before channel selection")
    require("source.deleteRoi();" in analysis,
            "Pre-existing area ROIs can crop Duplicator channel extraction and corrupt measurements")
    require('matches(".*\\\\.(tif|tiff|png|jpe?g|bmp)$")' in command and
            "if (image == null)" in command and "BF.openImagePlus" in command,
            "Fast native raster loading with Bio-Formats fallback disappeared")
    require("new BrushTool().run(\"\")" in analysis, "Paintbrush must use ImageJ BrushTool directly")
    require("Paintbrush Tool Options..." not in analysis, "Macro-only paintbrush command returned")
    require(analysis.count("closeThreshold();") == 1,
            "Threshold Adjuster is being closed/reopened instead of reused between the two manual threshold stages")

    # Keep the 29-column output shape while replacing obsolete connectivity diagnostics.
    require("CSV_HEADER_1" in analysis and "CSV_HEADER_2" in analysis, "CSV headers are not owned by Java")
    for marker in ("Skeleton Trees", "Terminal Tips", "Triple Junctions", "Quadruple Junctions"):
        require(marker in analysis, f"Analyze Skeleton CSV diagnostic missing: {marker}")
    require("*0.28" not in analysis, "Legacy branch-point heuristic is still active")
    for formula in ("=J", "=O", "/M", "=LOG10(M", "=IF(AA"):
        require(formula in analysis, f"CSV formula missing: {formula}")

    # Fresh-Fiji validation must execute Java directly with the legacy macro physically absent.
    require("legacy/aNMJ-morph macro.txt" in runtime, "Direct runtime no longer removes/asserts legacy resource absence")
    require("DirectJavaAnalysisRuntime" in runtime, "Direct Java runtime harness is not launched")
    require("LegacyMacroRunner" not in runtime_java and "IJ.runMacro" not in runtime_java,
            "Direct Java runtime harness depends on IJM")
    require("-WindowStyle Hidden" in runtime, "Direct Java runtime must launch hidden")
    require("-Runs 2" in fiji_ci and "run_direct_java_analysis.ps1" in fiji_ci,
            "Fresh-Fiji CI is not running the direct Java Runs=2 oracle")
    for retired in (
        "run_validation_with_plugin.ps1",
        "run_source_copy_failclosed_probes.ps1",
        "run_template_copy_failclosed_probes.ps1",
        "run_split_channels_jit_probe.ps1",
        "run_split_channels_failclosed_probe.ps1",
    ):
        require(retired not in fiji_ci, f"Fresh-Fiji CI still runs retired migration probe: {retired}")

    # The immutable IJM remains the historical pre-anisotropy scientific reference.
    require(
        "totalLengthOfBranches = (imageWidth * imageHeight - counts0) * pixelSizeX;" in macro,
        "Historical IJM reference formula changed unexpectedly",
    )

    print("Repository checks passed")


if __name__ == "__main__":
    main()
