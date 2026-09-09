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
        'IJ.run(original.nerve, "Threshold...", "")',
        'IJ.run(original.nerve, "Despeckle", "")',
        'IJ.run(original.nerve, "Skeletonize", "")',
        'IJ.run(original.nerve, "BinaryConnectivity ", "white")',
        'IJ.run(original.muscle, "Subtract Background...", "rolling=50 create")',
        'IJ.run(segment.muscle, "Find Maxima...", "noise=10 output=[Segmented Particles]")',
        'IJ.run(finalAverage, "Analyze Particles...", "display summarize")',
        'ZProjector.run(overlapConcat, "avg")',
        'ZProjector.run(finalConcat, "avg")',
    ):
        require(marker in analysis, f"Direct ImageJ/Fiji operation changed or disappeared: {marker}")

    require(
        "AnisotropicSkeletonCalibration.effectivePixelSize(original.nerve)" in analysis,
        "Branch-length workflow does not apply anisotropic skeleton calibration",
    )
    require(
        "((double) width * height - counts0) * branchPixelSize" in analysis,
        "Branch-length workflow no longer preserves the legacy skeleton-pixel-count estimator",
    )
    anisotropic_calibration = text(
        "src/main/java/io/github/alzeiby/anmjmorphplus/AnisotropicSkeletonCalibration.java"
    )
    require(
        "Double.compare(pixelWidth, pixelHeight) == 0" in anisotropic_calibration
        and "return pixelWidth;" in anisotropic_calibration,
        "Isotropic branch-length calibration must preserve the legacy pixel scale exactly",
    )
    require(
        "horizontal * pixelWidth" in anisotropic_calibration
        and "vertical * pixelHeight" in anisotropic_calibration
        and "Math.hypot(pixelWidth, pixelHeight)" in anisotropic_calibration,
        "Anisotropic branch-length calibration must use X/Y/diagonal skeleton orientation scaling",
    )
    require("setIm5D(false)" in analysis, "Overlap concatenation must not open as 4D")
    require("setIm5D(true)" in analysis, "Stage-6 concatenation must retain legacy 4D option")
    require("new BrushTool().run(\"\")" in analysis, "Paintbrush must use ImageJ BrushTool directly")
    require("Paintbrush Tool Options..." not in analysis, "Macro-only paintbrush command returned")

    # The direct workflow owns the exact historical raw table shape/formulas.
    require("CSV_HEADER_1" in analysis and "CSV_HEADER_2" in analysis, "CSV headers are not owned by Java")
    for formula in ("=J", "=(K", "*0.28", "=LOG10(M", "=IF(AA"):
        require(formula in analysis, f"Historical CSV formula missing: {formula}")

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
