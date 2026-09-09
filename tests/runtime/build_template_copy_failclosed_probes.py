from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MACRO = ROOT / "aNMJ-morph macro.txt"
DEFAULT_WORK = Path(__file__).resolve().parent / "_work" / "template-copy-failclosed"
ERROR = "Error: Invalid template-copy-id supplied by Java"


def macro_path(path: Path) -> str:
    return path.resolve().as_posix()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label}, found {count}")
    return text.replace(old, new, 1)


def instrument_parser(source: str, marker: str, wrong_title: bool) -> str:
    fallback = '    run("Duplicate...", "title=[" + templateTitle + "] duplicate");'
    source = replace_once(
        source,
        fallback,
        '    File.append("TEMPLATE FALLBACK DUPLICATE RAN", testLog);\n' + fallback,
        "legacy template-copy fallback",
    )

    if wrong_title:
        target = f'''    if (getTitle() != templateTitle) {{
      selectImage(sourceCopyId);
      exit("{ERROR}");
    }}'''
        replacement = f'''    if (getTitle() != templateTitle) {{
      selectImage(sourceCopyId);
      File.append("{marker}: {ERROR}", testLog);
      exit("{ERROR}");
    }}'''
    else:
        target = f'''    if (isNaN(suppliedTemplateCopyId) || templateCopyIdText != "" + suppliedTemplateCopyId || !isOpen(suppliedTemplateCopyId)) {{
      exit("{ERROR}");
    }}'''
        replacement = f'''    if (isNaN(suppliedTemplateCopyId) || templateCopyIdText != "" + suppliedTemplateCopyId || !isOpen(suppliedTemplateCopyId)) {{
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

    source = replace_once(
        source,
        '  run("Paintbrush Tool Options...", "brush=100");',
        '  File.append("HEADLESS PROBE BYPASSED PAINTBRUSH OPTIONS", testLog);',
        "paintbrush UI setup",
    )

    split_channels = '  run("Split Channels");'
    if source.count(split_channels) != 3:
        raise RuntimeError("Production macro Split Channels boundaries changed")
    source = source.replace(
        split_channels,
        '  File.append("HEADLESS PROBE BYPASSED FIRST SPLIT CHANNELS", testLog);',
        1,
    )

    threshold = '  run("Threshold...");'
    if source.count(threshold) != 2:
        raise RuntimeError("Production macro threshold boundary changed")
    source = source.replace(
        threshold,
        '  File.append("HEADLESS PROBE BYPASSED FIRST THRESHOLD UI", testLog);',
        1,
    )

    dispatch_pattern = re.compile(
        r'list = getList\("image\.titles"\);.*?(?=function processOpenImage\(fileName\) \{)',
        re.DOTALL,
    )
    image = macro_path(image_path)
    fixture = f'''inputFile = "{image}";
newImage("template-copy-probe.tif", "8-bit black", 16, 12, 2);
run("Properties...", "channels=2 slices=1 frames=1");
saveAs("Tiff", inputFile);
close();
open(inputFile);
testFile = inputFile;
File.saveString("START\\n", testLog);
probeOriginalId = getImageID();
newImage("__aNMJ_source_" + probeOriginalId, "8-bit black", 16, 12, 2);
run("Properties...", "channels=2 slices=1 frames=1");
goodSourceCopyId = getImageID();
selectImage(probeOriginalId);
'''
    if wrong_title:
        dispatch = f'''{fixture}newImage("__aNMJ_wrong_template_copy", "8-bit black", 16, 12, 2);
run("Properties...", "channels=2 slices=1 frames=1");
badTemplateCopyId = getImageID();
selectImage(probeOriginalId);
javaArgument = "muscle-channel=1;nerve-channel=2;channels-canonical=1;source-copy-id=" + goodSourceCopyId + ";template-copy-id=" + badTemplateCopyId;
File.append("SUPPLIED WRONG-TITLE TEMPLATE ID", testLog);
processOpenImage(testFile);
File.append("UNEXPECTED RETURN", testLog);
run("Quit");

'''
    else:
        dispatch = f'''{fixture}javaArgument = "muscle-channel=1;nerve-channel=2;channels-canonical=1;source-copy-id=" + goodSourceCopyId + ";template-copy-id=2147483647";
File.append("SUPPLIED UNRESOLVED TEMPLATE ID", testLog);
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
    unresolved_image = work / "template-copy-unresolved-input.tif"
    wrong_image = work / "template-copy-wrong-title-input.tif"
    unresolved = work / "template-copy-unresolved.ijm"
    wrong = work / "template-copy-wrong-title.ijm"
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
        fixture_name = "template-copy-wrong-title-input.tif" if wrong_title else "template-copy-unresolved-input.tif"
        placeholder = ROOT / "tests" / "runtime" / "_work" / "template-copy-failclosed" / fixture_name
        probe = make_probe(
            source,
            placeholder,
            ROOT / "tests" / "runtime" / "_work" / "template-copy-failclosed" / "trace.txt",
            wrong_title,
        )
        for required in (marker, ERROR, "TEMPLATE FALLBACK DUPLICATE RAN", "UNEXPECTED RETURN", "source-copy-id="):
            if required not in probe:
                raise AssertionError(f"Generated template fail-closed probe missing {required!r}")
        if probe.count(f'exit("{ERROR}");') != source.count(f'exit("{ERROR}");'):
            raise AssertionError("Template fail-closed probes must preserve the production error exits exactly")
    print("Template-copy fail-closed probe generation check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build template-copy fail-closed Fiji probes")
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
