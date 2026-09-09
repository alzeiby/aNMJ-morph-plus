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


def make_harness(source: str, image_path: Path, log_path: Path, rectangular: bool) -> str:
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

    welcome_pattern = re.compile(
        r'  Dialog\.create\("Welcome"\);.*?'
        r'  if \(nerveTerminalChannel == -1\) \{\s*'
        r'exit\("Error: No nerve terminal channel was selected"\);\s*\}\s*',
        re.DOTALL,
    )
    text = replace_once(
        text,
        welcome_pattern,
        '''  muscleEndplateChannel = 1;
  nerveTerminalChannel = 2;
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


def build(macro: Path, reference: Path, work: Path) -> tuple[Path, Path]:
    source = macro.read_text(encoding="utf-8")
    if not reference.exists():
        raise FileNotFoundError(reference)

    square_dir = work / "square"
    rect_dir = work / "rectangular"
    for directory in (square_dir, rect_dir):
        if directory.exists():
            shutil.rmtree(directory)
        (directory / "input").mkdir(parents=True)

    square_image = square_dir / "input" / "NMJ_1.lsm"
    rect_source = rect_dir / "input" / "NMJ_1.lsm"
    shutil.copy2(reference, square_image)
    shutil.copy2(reference, rect_source)

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
    return square_harness, rect_harness


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
            'setAutoThreshold("Default dark")',
            "STAGE 6 segmentation accepted",
            "DONE stage 7",
        ]
        for marker in required:
            if marker not in harness:
                raise AssertionError(f"Generated harness is missing {marker!r}")
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

    square, rectangular = build(args.macro, args.reference, args.work_dir)
    print(square)
    print(rectangular)


if __name__ == "__main__":
    main()
