from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MACRO = ROOT / "aNMJ-morph macro.txt"
DEFAULT_REFERENCE = ROOT / "Reference Images" / "NMJ_1.lsm"
DEFAULT_WORK = Path(__file__).resolve().parent / "_work"


def macro_path(path: Path) -> str:
    return path.resolve().as_posix()


def replace_once(text: str, pattern: re.Pattern[str], replacement: str, label: str) -> str:
    text, count = pattern.subn(lambda _match: replacement, text, count=1)
    if count != 1:
        raise RuntimeError(f"Could not replace {label}; the production macro structure changed")
    return text


def make_harness(
    source: str,
    image_path: Path,
    log_path: Path,
    rectangular: bool,
    supplied_channels: bool = False,
) -> str:
    if rectangular and supplied_channels:
        raise ValueError("supplied-channel bridge harness is only defined for the square reference case")
    marker = "decimalPlaces = 8;"
    if marker not in source:
        raise RuntimeError("Could not locate decimalPlaces configuration marker")
    text = source.replace(
        marker,
        marker + f'\n\ntestLog = "{macro_path(log_path)}";',
        1,
    )

    wait_pattern = re.compile(r"function safeWaitForUser\(message\) \{.*?\n\}", re.DOTALL)
    text = replace_once(
        text,
        wait_pattern,
        '''function safeWaitForUser(message) {
  File.append(message, testLog);
  if (indexOf(message, "Screen 4/7") >= 0) {
    run("Clear Results");
    makeLine(100, 100, 110, 100); run("Measure");
    makeLine(100, 110, 112, 110); run("Measure");
    makeLine(100, 120, 108, 120); run("Measure");
  }
}''',
        "safeWaitForUser",
    )

    dispatch_pattern = re.compile(
        r'list = getList\("image\.titles"\);.*?(?=function processOpenImage\(fileName\) \{)',
        re.DOTALL,
    )
    image = macro_path(image_path)
    if rectangular:
        rect_path = image_path.parent / "NMJ_1_rect_384x512.tif"
        dispatch = f'''testFile = "{image}";
rectFile = "{macro_path(rect_path)}";
File.saveString("START\\n", testLog);
    openImageFile(testFile);
File.append("STAGE 1 open image", testLog);
makeRectangle(0, 0, 384, 512);
run("Duplicate...", "title=[NMJ_1_rect_384x512.tif] duplicate");
saveAs("Tiff", rectFile);
run("Close All");
open(rectFile);
File.append("RECT fixture 384 x 512", testLog);
processOpenImage(rectFile);
File.append("DONE stage 7", testLog);
run("Close All");
run("Quit");

'''
    elif supplied_channels:
        dispatch = f'''testFile = "{image}";
File.saveString("START\\n", testLog);
    openImageFile(testFile);
File.append("STAGE 1 open image", testLog);
    javaArgument = "image-id=" + getImageID() + ";muscle-channel=1;nerve-channel=2;channels-canonical=1;early-split-java=1";
File.append("STAGE 1 Java channels supplied", testLog);
processOpenImage(testFile);
File.append("DONE stage 7", testLog);
run("Close All");
run("Quit");

'''
    else:
        dispatch = f'''testFile = "{image}";
File.saveString("START\\n", testLog);
    openImageFile(testFile);
File.append("STAGE 1 open image", testLog);
processOpenImage(testFile);
File.append("DONE stage 7", testLog);
run("Close All");
run("Quit");

'''
    text = replace_once(text, dispatch_pattern, dispatch, "top-level dispatch")

    if supplied_channels:
        java_argument_global = "javaArgument = getArgument();"
        if java_argument_global not in text:
            raise RuntimeError("Production macro does not expose the top-level Java argument")
        text = text.replace(java_argument_global, "var javaArgument = getArgument();", 1)
        source_copy_title = '  sourceCopyTitle = "__aNMJ_source_" + originalImageId;'
        if source_copy_title not in text:
            raise RuntimeError("Production macro does not expose the initial source-copy stage")
        text = text.replace(
            source_copy_title,
            source_copy_title + '''
  run("Duplicate...", "title=[" + sourceCopyTitle + "] duplicate");
  javaSuppliedSourceCopyId = getImageID();
  selectImage(originalImageId);
  javaArgument = javaArgument + ";source-copy-id=" + javaSuppliedSourceCopyId;
  if (indexOf(javaArgument, ";muscle-channel=1;nerve-channel=2;channels-canonical=1;early-split-java=1;source-copy-id=") < 0) {
    exit("Error: Java bridge argument scope lost before source-copy handoff");
  }
  File.append("STAGE 1 Java source copy argument preserved", testLog);
  File.append("STAGE 1 Java source copy supplied", testLog);''',
            1,
        )
        source_copy_complete = '  outputFilename = originalDirectory + "raw_data_table.csv";'
        if source_copy_complete not in text:
            raise RuntimeError("Production macro source-copy stage no longer reaches output setup")
        text = text.replace(
            source_copy_complete,
            '''  if (sourceCopyId != javaSuppliedSourceCopyId) {
    exit("Error: Java source-copy bridge was not adopted");
  }
  File.append("STAGE 1 Java source copy accepted", testLog);
''' + source_copy_complete,
            1,
        )
        template_copy_title = '  templateTitle = "__aNMJ_template_" + sourceCopyId;'
        if template_copy_title not in text:
            raise RuntimeError("Production macro does not expose the template-copy stage")
        text = text.replace(
            template_copy_title,
            template_copy_title + '''
  run("Duplicate...", "title=[" + templateTitle + "] duplicate");
  javaSuppliedTemplateCopyId = getImageID();
  selectImage(sourceCopyId);
  javaArgument = javaArgument + ";template-copy-id=" + javaSuppliedTemplateCopyId;
  if (indexOf(javaArgument, ";source-copy-id=" + javaSuppliedSourceCopyId) < 0 ||
      indexOf(javaArgument, ";template-copy-id=" + javaSuppliedTemplateCopyId) < 0) {
    exit("Error: Java bridge argument scope lost before template-copy handoff");
  }
  File.append("STAGE 2 Java template copy argument preserved", testLog);
  File.append("STAGE 2 Java template copy supplied", testLog);''',
            1,
        )
        template_fallback = '    run("Duplicate...", "title=[" + templateTitle + "] duplicate");'
        if text.count(template_fallback) != 1:
            raise RuntimeError("Production macro template-copy fallback changed")
        text = text.replace(
            template_fallback,
            '''    File.append("ERROR Java template bridge entered legacy fallback", testLog);
    exit("Error: Java template bridge entered legacy fallback");
''' + template_fallback,
            1,
        )
        template_copy_select = '''  selectImage(templateImageId);
  if (!channelsCanonical) {'''
        if text.count(template_copy_select) != 1:
            raise RuntimeError("Production macro template-copy selection boundary changed")
        text = text.replace(
            template_copy_select,
            '''  selectImage(templateImageId);
  if (templateImageId != javaSuppliedTemplateCopyId) {
    exit("Error: Java template-copy bridge was not adopted");
  }
  File.append("STAGE 2 Java template copy accepted", testLog);
  if (!channelsCanonical) {''',
            1,
        )
        split_call = '    splitStatus = call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent");'
        if text.count(split_call) != 2:
            raise RuntimeError("Production macro early Java Split Channels boundaries changed")
        split_block = '''  if (earlySplitJava) {
    Stack.setChannel(1);
    splitStatus = call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent");'''
        if text.count(split_block) != 2:
            raise RuntimeError("Production macro early Java Split Channels call shape changed")
        text = text.replace(
            split_block,
            '''  if (earlySplitJava) {
    Stack.setChannel(1);
    File.append("STAGE 2 before Java original split", testLog);
    splitStatus = call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent");
    File.append("STAGE 2 after Java original split status=" + splitStatus, testLog);''',
            1,
        )
        text = text.replace(
            split_block,
            '''  if (earlySplitJava) {
    Stack.setChannel(1);
    File.append("STAGE 2 before Java template split", testLog);
    splitStatus = call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent");
    File.append("STAGE 2 after Java template split status=" + splitStatus, testLog);''',
            1,
        )
        original_id_adoption = '''    selectImage(originalSplitC2Id);
    if (getTitle() != "C2-" + originalTitle) {
      exit("Error: Java early Split Channels returned invalid C2 image");
    }'''
        if text.count(original_id_adoption) != 1:
            raise RuntimeError("Production macro original split ID adoption boundary changed")
        text = text.replace(
            original_id_adoption,
            original_id_adoption + '''
    if (originalSplitC1Id == 0 || originalSplitC2Id == 0 || getImageID() != originalSplitC2Id || !isOpen(sourceCopyId)) {
      exit("Error: Java original split IDs were not adopted");
    }
    File.append("STAGE 2 Java original split accepted C2 current sourceCopy intact", testLog);
    File.append("STAGE 2 Java original split IDs adopted C1=" + originalSplitC1Id + " C2=" + originalSplitC2Id, testLog);''',
            1,
        )
        template_id_adoption = '''    selectImage(templateSplitC2Id);
    if (getTitle() != "C2-" + templateTitle) {
      exit("Error: Java early Split Channels returned invalid C2 image");
    }'''
        if text.count(template_id_adoption) != 1:
            raise RuntimeError("Production macro template split ID adoption boundary changed")
        text = text.replace(
            template_id_adoption,
            template_id_adoption + '''
    if (templateSplitC1Id == 0 || templateSplitC2Id == 0 || getImageID() != templateSplitC2Id || !isOpen(sourceCopyId)) {
      exit("Error: Java template split IDs were not adopted");
    }
    File.append("STAGE 2 Java template split accepted C2 current sourceCopy intact", testLog);
    File.append("STAGE 2 Java template split IDs adopted C1=" + templateSplitC1Id + " C2=" + templateSplitC2Id, testLog);''',
            1,
        )
        original_fallback = '''    selectImage(originalImageId);
    Stack.setChannel(1);
    run("Split Channels");'''
        if text.count(original_fallback) != 1:
            raise RuntimeError("Production macro original early Split Channels fallback changed")
        text = text.replace(
            original_fallback,
            '''    File.append("ERROR Java original split entered legacy fallback", testLog);
    exit("Error: Java original split entered legacy fallback");
''' + original_fallback,
            1,
        )
        template_fallback_split = '''    selectImage(templateImageId);
    Stack.setChannel(1);
    run("Split Channels");'''
        if text.count(template_fallback_split) != 1:
            raise RuntimeError("Production macro template early Split Channels fallback changed")
        text = text.replace(
            template_fallback_split,
            '''    File.append("ERROR Java template split entered legacy fallback", testLog);
    exit("Error: Java template split entered legacy fallback");
''' + template_fallback_split,
            1,
        )

    if 'suppliedMuscleChannel = getJavaArgumentValue("muscle-channel");' in text:
        welcome_pattern = re.compile(
            r'  suppliedMuscleChannel = getJavaArgumentValue\("muscle-channel"\);.*?'
            r'  if \(muscleEndplateChannel < 1 \|\| muscleEndplateChannel > numberOfChannels \|\|\s*'
            r'nerveTerminalChannel < 1 \|\| nerveTerminalChannel > numberOfChannels \|\|\s*'
            r'muscleEndplateChannel == nerveTerminalChannel\) \{\s*'
            r'exit\("Error: Invalid muscle endplate/nerve terminal channel selection"\);\s*\}\s*',
            re.DOTALL,
        )
    else:
        welcome_pattern = re.compile(
            r'  Dialog\.create\("Welcome"\);.*?'
            r'  if \(nerveTerminalChannel == -1\) \{\s*'
            r'exit\("Error: No nerve terminal channel was selected"\);\s*\}\s*',
            re.DOTALL,
        )
    if supplied_channels:
        if 'suppliedMuscleChannel = getJavaArgumentValue("muscle-channel");' not in text:
            raise RuntimeError("Production macro does not expose the Java channel-argument bridge")
    else:
        text = replace_once(
            text,
            welcome_pattern,
            '''  muscleEndplateChannel = 1;
  nerveTerminalChannel = 2;
  channelsCanonical = false;
  earlySplitJava = false;
  File.append("STAGE 1 channels selected", testLog);
''',
            "Welcome/channel-selection dialog",
        )

    threshold_command = 'run("Threshold...");'
    threshold_count = text.count(threshold_command)
    if threshold_count != 2:
        raise RuntimeError(f"Expected two Threshold commands, found {threshold_count}")
    text = text.replace(
        threshold_command,
        threshold_command + '\n  setAutoThreshold("Default dark");',
    )

    stage6_pattern = re.compile(
        r'  Dialog\.create\("Is this image OK\?"\);.*?'
        r'  imageAlright = Dialog\.getCheckbox\(\);',
        re.DOTALL,
    )
    text = replace_once(
        text,
        stage6_pattern,
        '''  imageAlright = true;
  File.append("STAGE 6 segmentation accepted", testLog);''',
        "stage-6 confirmation dialog",
    )

    return text


def build(macro: Path, reference: Path, work: Path) -> tuple[Path, Path, Path]:
    source = macro.read_text(encoding="utf-8")
    if not reference.exists():
        raise FileNotFoundError(reference)

    square_dir = work / "square"
    rect_dir = work / "rectangular"
    bridge_dir = work / "java-bridge"
    for directory in (square_dir, rect_dir, bridge_dir):
        if directory.exists():
            shutil.rmtree(directory)
        (directory / "input").mkdir(parents=True)

    square_image = square_dir / "input" / "NMJ_1.lsm"
    rect_source = rect_dir / "input" / "NMJ_1.lsm"
    bridge_image = bridge_dir / "input" / "NMJ_1.lsm"
    shutil.copy2(reference, square_image)
    shutil.copy2(reference, rect_source)
    shutil.copy2(reference, bridge_image)

    square_harness = square_dir / "aNMJ-morph-plus-e2e.ijm"
    square_harness.write_text(
        make_harness(source, square_image, square_dir / "trace.txt", rectangular=False),
        encoding="utf-8",
        newline="\n",
    )

    rect_harness = rect_dir / "aNMJ-morph-plus-rect-e2e.ijm"
    rect_harness.write_text(
        make_harness(source, rect_source, rect_dir / "trace.txt", rectangular=True),
        encoding="utf-8",
        newline="\n",
    )
    bridge_harness = bridge_dir / "aNMJ-morph-plus-java-bridge-e2e.ijm"
    bridge_harness.write_text(
        make_harness(
            source,
            bridge_image,
            bridge_dir / "trace.txt",
            rectangular=False,
            supplied_channels=True,
        ),
        encoding="utf-8",
        newline="\n",
    )
    return square_harness, rect_harness, bridge_harness


def check(macro: Path) -> None:
    source = macro.read_text(encoding="utf-8")
    placeholder = ROOT / "Reference Images" / "NMJ_1.lsm"
    for rectangular in (False, True):
        harness = make_harness(
            source,
            placeholder,
            ROOT / "tests" / "runtime" / "_work" / "trace.txt",
            rectangular,
        )
        required = [
            "STAGE 1 channels selected",
            "channelsCanonical = false;",
            "earlySplitJava = false;",
            'setAutoThreshold("Default dark")',
            "STAGE 6 segmentation accepted",
            "DONE stage 7",
        ]
        for marker in required:
            if marker not in harness:
                raise AssertionError(f"Generated harness is missing {marker!r}")
    bridge = make_harness(
        source,
        placeholder,
        ROOT / "tests" / "runtime" / "_work" / "trace.txt",
        rectangular=False,
        supplied_channels=True,
    )
    for marker in (
        "var javaArgument = getArgument();",
        'javaArgument = "image-id=" + getImageID() + ";muscle-channel=1;nerve-channel=2;channels-canonical=1;early-split-java=1";',
        "STAGE 1 Java channels supplied",
        'javaArgument = javaArgument + ";source-copy-id=" + javaSuppliedSourceCopyId;',
        'if (indexOf(javaArgument, ";muscle-channel=1;nerve-channel=2;channels-canonical=1;early-split-java=1;source-copy-id=") < 0) {',
        "STAGE 1 Java source copy argument preserved",
        "STAGE 1 Java source copy supplied",
        "STAGE 1 Java source copy accepted",
        'javaArgument = javaArgument + ";template-copy-id=" + javaSuppliedTemplateCopyId;',
        "STAGE 2 Java template copy argument preserved",
        "STAGE 2 Java template copy supplied",
        "STAGE 2 Java template copy accepted",
        "ERROR Java template bridge entered legacy fallback",
        "STAGE 2 before Java original split",
        "STAGE 2 after Java original split status=",
        "STAGE 2 before Java template split",
        "STAGE 2 after Java template split status=",
        "STAGE 2 Java original split accepted C2 current sourceCopy intact",
        "STAGE 2 Java template split accepted C2 current sourceCopy intact",
        "STAGE 2 Java original split IDs adopted C1=",
        "STAGE 2 Java template split IDs adopted C1=",
        "ERROR Java original split entered legacy fallback",
        "ERROR Java template split entered legacy fallback",
        'suppliedMuscleChannel = getJavaArgumentValue("muscle-channel");',
        'setAutoThreshold("Default dark")',
        "STAGE 6 segmentation accepted",
        "DONE stage 7",
    ):
        if marker not in bridge:
            raise AssertionError(f"Generated Java-bridge harness is missing {marker!r}")
    if "0;source-copy-id=" in bridge:
        raise AssertionError("Generated Java-bridge harness regressed to local 0;source-copy-id argument scope")
    if "0;template-copy-id=" in bridge:
        raise AssertionError("Generated Java-bridge harness regressed to local 0;template-copy-id argument scope")
    print("Runtime harness generation check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build automated Fiji runtime harnesses from the production macro")
    parser.add_argument("--macro", type=Path, default=DEFAULT_MACRO)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--check", action="store_true", help="Only verify that the current macro can be transformed")
    args = parser.parse_args()

    if args.check:
        check(args.macro)
        return

    square, rectangular, bridge = build(args.macro, args.reference, args.work_dir)
    print(square)
    print(rectangular)
    print(bridge)


if __name__ == "__main__":
    main()
