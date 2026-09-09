from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MACRO = ROOT / "aNMJ-morph macro.txt"
DEFAULT_WORK = Path(__file__).resolve().parent / "_work" / "source-copy-failclosed"
ERROR = "Error: Invalid source-copy-id supplied by Java"


def macro_path(path: Path) -> str:
    return path.resolve().as_posix()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label}, found {count}")
    return text.replace(old, new, 1)


def instrument_parser(source: str, marker: str, wrong_title: bool) -> str:
    fallback = '    run("Duplicate...", "title=[" + sourceCopyTitle + "] duplicate");'
    source = replace_once(
        source,
        fallback,
        '    File.append("FALLBACK DUPLICATE RAN", testLog);\n' + fallback,
        "legacy source-copy fallback",
    )

    if wrong_title:
        target = f'''    if (getTitle() != sourceCopyTitle) {{
      selectImage(originalImageId);
      exit("{ERROR}");
    }}'''
        replacement = f'''    if (getTitle() != sourceCopyTitle) {{
      selectImage(originalImageId);
      File.append("{marker}: {ERROR}", testLog);
      exit("{ERROR}");
    }}'''
    else:
        target = f'''    if (isNaN(suppliedSourceCopyId) || sourceCopyIdText != "" + suppliedSourceCopyId || !isOpen(suppliedSourceCopyId)) {{
      exit("{ERROR}");
    }}'''
        replacement = f'''    if (isNaN(suppliedSourceCopyId) || sourceCopyIdText != "" + suppliedSourceCopyId || !isOpen(suppliedSourceCopyId)) {{
      File.append("{marker}: {ERROR}", testLog);
      exit("{ERROR}");
    }}'''
    return replace_once(source, target, replacement, "target fail-closed parser branch")


def make_probe(source: str, image_path: Path, log_path: Path, wrong_title: bool) -> str:
    marker = "FAIL-CLOSED WRONG-TITLE" if wrong_title else "FAIL-CLOSED UNRESOLVED"
    source = instrument_parser(source, marker, wrong_title)
    config = "decimalPlaces = 8;"
    source = replace_once(
        source,
        config,
        config + f'\n\ntestLog = "{macro_path(log_path)}";',
        "decimalPlaces marker",
    )

    dispatch_pattern = re.compile(
        r'list = getList\("image\.titles"\);.*?(?=function processOpenImage\(fileName\) \{)',
        re.DOTALL,
    )
    image = macro_path(image_path)
    fixture = f'''inputFile = "{image}";
newImage("source-copy-probe.tif", "8-bit black", 16, 12, 2);
run("Properties...", "channels=2 slices=1 frames=1");
saveAs("Tiff", inputFile);
close();
open(inputFile);
'''
    if wrong_title:
        dispatch = f'''{fixture}testFile = inputFile;
File.saveString("START\\n", testLog);
probeOriginalId = getImageID();
newImage("__aNMJ_wrong_source_copy", "8-bit black", 16, 12, 1);
badSourceCopyId = getImageID();
selectImage(probeOriginalId);
javaArgument = "muscle-channel=1;nerve-channel=2;channels-canonical=1;source-copy-id=" + badSourceCopyId;
File.append("SUPPLIED WRONG-TITLE ID", testLog);
processOpenImage(testFile);
File.append("UNEXPECTED RETURN", testLog);
run("Quit");

'''
    else:
        dispatch = f'''{fixture}testFile = inputFile;
File.saveString("START\\n", testLog);
javaArgument = "muscle-channel=1;nerve-channel=2;channels-canonical=1;source-copy-id=2147483647";
File.append("SUPPLIED UNRESOLVED ID", testLog);
processOpenImage(testFile);
File.append("UNEXPECTED RETURN", testLog);
run("Quit");

'''
    source, count = dispatch_pattern.subn(lambda _m: dispatch, source, count=1)
    if count != 1:
        raise RuntimeError("Could not replace production top-level dispatch")
    return source


def build(macro: Path, work: Path) -> tuple[Path, Path]:
    source = macro.read_text(encoding="utf-8")
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    unresolved_image = work / "source-copy-unresolved-input.tif"
    wrong_image = work / "source-copy-wrong-title-input.tif"
    unresolved = work / "source-copy-unresolved.ijm"
    wrong = work / "source-copy-wrong-title.ijm"
    unresolved.write_text(
        make_probe(source, unresolved_image, work / "unresolved.txt", wrong_title=False),
        encoding="utf-8",
        newline="\n",
    )
    wrong.write_text(
        make_probe(source, wrong_image, work / "wrong-title.txt", wrong_title=True),
        encoding="utf-8",
        newline="\n",
    )
    return unresolved, wrong


def check(macro: Path) -> None:
    source = macro.read_text(encoding="utf-8")
    for wrong_title, marker in (
        (False, "FAIL-CLOSED UNRESOLVED"),
        (True, "FAIL-CLOSED WRONG-TITLE"),
    ):
        fixture_name = "source-copy-wrong-title-input.tif" if wrong_title else "source-copy-unresolved-input.tif"
        placeholder = ROOT / "tests" / "runtime" / "_work" / "source-copy-failclosed" / fixture_name
        probe = make_probe(
            source,
            placeholder,
            ROOT / "tests" / "runtime" / "_work" / "source-copy-failclosed" / "trace.txt",
            wrong_title,
        )
        for required in (marker, ERROR, "FALLBACK DUPLICATE RAN", "UNEXPECTED RETURN"):
            if required not in probe:
                raise AssertionError(f"Generated fail-closed probe missing {required!r}")
        if probe.count(f'exit("{ERROR}");') != source.count(f'exit("{ERROR}");'):
            raise AssertionError("Fail-closed probes must preserve the production error exits exactly")
    print("Source-copy fail-closed probe generation check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build source-copy fail-closed Fiji probes")
    parser.add_argument("--macro", type=Path, default=DEFAULT_MACRO)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        check(args.macro)
        return
    unresolved, wrong = build(args.macro, args.work_dir)
    print(unresolved)
    print(wrong)


if __name__ == "__main__":
    main()
