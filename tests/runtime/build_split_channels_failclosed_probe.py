from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MACRO = ROOT / "aNMJ-morph macro.txt"
DEFAULT_WORK = Path(__file__).resolve().parent / "_work" / "split-channels-failclosed"
ERROR_PREFIX = "Error: Java early Split Channels failed: "


def macro_path(path: Path) -> str:
    return path.resolve().as_posix()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label}, found {count}")
    return text.replace(old, new, 1)


def replace_occurrence(text: str, old: str, new: str, occurrence: int, label: str) -> str:
    start = 0
    position = -1
    for _ in range(occurrence + 1):
        position = text.find(old, start)
        if position < 0:
            raise RuntimeError(f"Could not find {label} occurrence {occurrence + 1}")
        start = position + len(old)
    return text[:position] + new + text[position + len(old):]


def make_probe(source: str, image_path: Path, log_path: Path, site: str) -> str:
    source = replace_once(
        source,
        "decimalPlaces = 8;",
        f'decimalPlaces = 8;\n\ntestLog = "{macro_path(log_path)}";',
        "decimalPlaces marker",
    )

    if site not in {"original", "template"}:
        raise ValueError(f"Unsupported split fail-closed site: {site}")

    # Keep the production Split bridge and parser exact. Only force the current image
    # to an invalid one-channel image immediately before the selected Java call.
    call_line = '    splitStatus = call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent");'
    if source.count(call_line) != 2:
        raise RuntimeError("Production macro Java Split Channels call boundaries changed")
    failure_index = 0 if site == "original" else 1
    source = replace_occurrence(
        source,
        call_line,
        '    newImage("forced-java-split-failure", "8-bit black", 2, 2, 1);\n' + call_line,
        failure_index,
        f"{site} Java Split Channels call",
    )

    failure_branch = f'''    if (splitParts.length != 3 || splitParts[0] != "OK") {{
      exit("{ERROR_PREFIX}" + splitStatus);
    }}'''
    if source.count(failure_branch) != 2:
        raise RuntimeError("Production macro Java Split Channels failure branches changed")
    failure_marker = "FAIL-CLOSED JAVA ORIGINAL SPLIT" if site == "original" else "FAIL-CLOSED JAVA TEMPLATE SPLIT"
    source = replace_occurrence(
        source,
        failure_branch,
        f'''    if (splitParts.length != 3 || splitParts[0] != "OK") {{
      File.append("{failure_marker}: " + splitStatus, testLog);
      exit("{ERROR_PREFIX}" + splitStatus);
    }}''',
        failure_index,
        f"{site} Java Split Channels failure branch",
    )

    if site == "original":
        fallback = '''    selectImage(originalImageId);
    Stack.setChannel(1);
    run("Split Channels");'''
        fallback_marker = "LEGACY ORIGINAL SPLIT FALLBACK RAN"
        fallback_label = "original early Split Channels fallback"
    else:
        fallback = '''    selectImage(templateImageId);
    Stack.setChannel(1);
    run("Split Channels");'''
        fallback_marker = "LEGACY TEMPLATE SPLIT FALLBACK RAN"
        fallback_label = "template early Split Channels fallback"
    source = replace_once(
        source,
        fallback,
        f'''    File.append("{fallback_marker}", testLog);
''' + fallback,
        fallback_label,
    )

    # This probe exits before thresholding; bypass only unrelated setup UI.
    source = replace_once(
        source,
        '  run("Paintbrush Tool Options...", "brush=100");',
        '  File.append("BYPASSED PAINTBRUSH OPTIONS", testLog);',
        "paintbrush setup",
    )

    dispatch_pattern = re.compile(
        r'list = getList\("image\.titles"\);.*?(?=function processOpenImage\(fileName\) \{)',
        re.DOTALL,
    )
    image = macro_path(image_path)
    canonical_flag = ";channels-canonical=1" if site == "original" else ""
    attempt_marker = "ATTEMPT JAVA ORIGINAL SPLIT" if site == "original" else "ATTEMPT JAVA TEMPLATE SPLIT"
    dispatch = f'''inputFile = "{image}";
newImage("split-fail-input.tif", "8-bit black", 16, 12, 2);
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
selectImage(goodSourceCopyId);
newImage("__aNMJ_template_" + goodSourceCopyId, "8-bit black", 16, 12, 2);
run("Properties...", "channels=2 slices=1 frames=1");
goodTemplateCopyId = getImageID();
selectImage(probeOriginalId);
javaArgument = "muscle-channel=1;nerve-channel=2{canonical_flag};early-split-java=1;source-copy-id=" + goodSourceCopyId + ";template-copy-id=" + goodTemplateCopyId;
File.append("{attempt_marker}", testLog);
processOpenImage(testFile);
File.append("UNEXPECTED RETURN", testLog);
run("Quit");

'''
    source, count = dispatch_pattern.subn(lambda _m: dispatch, source, count=1)
    if count != 1:
        raise RuntimeError("Could not replace production top-level dispatch")
    return source


def build(macro: Path, work: Path, site: str) -> Path:
    source = macro.read_text(encoding="utf-8")
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    probe = work / "split-channels-failclosed.ijm"
    probe.write_text(
        make_probe(source, work / "split-fail-input.tif", work / "trace.txt", site),
        encoding="utf-8",
        newline="\n",
    )
    return probe


def check(macro: Path) -> None:
    source = macro.read_text(encoding="utf-8")
    for site, attempt_marker, failure_marker, fallback_marker in (
        ("original", "ATTEMPT JAVA ORIGINAL SPLIT", "FAIL-CLOSED JAVA ORIGINAL SPLIT:", "LEGACY ORIGINAL SPLIT FALLBACK RAN"),
        ("template", "ATTEMPT JAVA TEMPLATE SPLIT", "FAIL-CLOSED JAVA TEMPLATE SPLIT:", "LEGACY TEMPLATE SPLIT FALLBACK RAN"),
    ):
        probe = make_probe(
            source,
            DEFAULT_WORK / site / "split-fail-input.tif",
            DEFAULT_WORK / site / "trace.txt",
            site,
        )
        for marker in (
            attempt_marker,
            failure_marker,
            fallback_marker,
            "UNEXPECTED RETURN",
            "EarlySplitChannelsBridge.splitCurrent",
            ERROR_PREFIX,
        ):
            if marker not in probe:
                raise AssertionError(f"Generated {site} Java Split fail-closed probe missing {marker!r}")
        if probe.count('call("io.github.alzeiby.anmjmorphplus.EarlySplitChannelsBridge.splitCurrent")') != 2:
            raise AssertionError(f"{site} fail-closed probe must retain both production Java Split call sites")
    print("Split Channels fail-closed probe generation check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build production Java Split Channels fail-closed probe")
    parser.add_argument("--macro", type=Path, default=DEFAULT_MACRO)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--site", choices=("original", "template"), default="original")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        check(args.macro)
        return
    print(build(args.macro, args.work_dir, args.site))


if __name__ == "__main__":
    main()
