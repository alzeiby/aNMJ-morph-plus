from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MACRO = ROOT / "aNMJ-morph macro.txt"
DEFAULT_WORK = Path(__file__).resolve().parent / "_work" / "fixtures"


def macro_path(path: Path) -> str:
    return path.resolve().as_posix()


def replace_once(text: str, pattern: re.Pattern[str], replacement: str, label: str) -> str:
    text, count = pattern.subn(lambda _match: replacement, text, count=1)
    if count != 1:
        raise RuntimeError(f"Could not replace {label}; the production macro structure changed")
    return text


def make_probe(source: str, work_dir: Path, native_only: bool = False) -> str:
    fixture_dir = work_dir / "generated"
    trace = work_dir / "fixture_probe.txt"
    marker = "decimalPlaces = 8;"
    if marker not in source:
        raise RuntimeError("Could not locate decimalPlaces configuration marker")

    helpers = f'''{marker}

testLog = "{macro_path(trace)}";
fixtureDirectory = "{macro_path(fixture_dir)}/";
twoPlaneTestChoice = "Z stack (maximum-project)";

function recordCase(caseName) {{
  getDimensions(w, h, c, z, t);
  getPixelSize(unit, pw, ph);
  basename = File.getName(getTitle());
  File.append("CASE|" + caseName + "|" + basename + "|" + w + "|" + h + "|" + c + "|" + z + "|" + t + "|" + bitDepth() + "|" + d2s(pw, 6) + "|" + d2s(ph, 6) + "|" + unit, testLog);
}}

function makeGrayFixture(name, channels, slices, frames) {{
  stackSize = channels * slices * frames;
  newImage("fixture", "8-bit black", 16, 12, stackSize);
  run("Properties...", "channels=" + channels + " slices=" + slices + " frames=" + frames + " unit=micron pixel_width=0.5 pixel_height=0.5 voxel_depth=1");
  saveAs("Tiff", fixtureDirectory + name);
  close();
}}

function probeFile(caseName, name, runNormalization) {{
  openImageFile(fixtureDirectory + name);
  recordCase(caseName + "_raw");
  if (runNormalization) {{
    normalizeChannels();
    recordCase(caseName + "_normalized");
  }}
  close();
}}
'''
    text = source.replace(marker, helpers, 1)

    ambiguous_pattern = re.compile(
        r'    Dialog\.create\("Two-plane image detected"\);.*?'
        r'    twoPlaneInterpretation = Dialog\.getChoice\(\);',
        re.DOTALL,
    )
    text = replace_once(
        text,
        ambiguous_pattern,
        "    twoPlaneInterpretation = twoPlaneTestChoice;",
        "two-plane interpretation dialog",
    )

    time_exit = (
        '    exit("Error: Time-series images (T > 1) are not supported. '
        'Reduce the image to a single time point before running aNMJ-morph.");'
    )
    if time_exit not in text:
        raise RuntimeError("Could not locate time-series rejection")
    text = text.replace(
        time_exit,
        '    File.append("REJECT|time_series|T>1", testLog);\n    return;',
        1,
    )

    dispatch_pattern = re.compile(
        r'list = getList\("image\.titles"\);.*?(?=function processOpenImage\(fileName\) \{)',
        re.DOTALL,
    )
    native_dispatch = '''File.makeDirectory(fixtureDirectory);
File.saveString("START\\n", testLog);

makeGrayFixture("fixture.tif", 2, 1, 1);
probeFile("tif", "fixture.tif", false);

makeGrayFixture("fixture.tiff", 2, 1, 1);
probeFile("tiff", "fixture.tiff", false);

makeGrayFixture("upper_tif_source.tif", 2, 1, 1);
File.rename(fixtureDirectory + "upper_tif_source.tif", fixtureDirectory + "UPPER.TIF");
probeFile("upper_tif", "UPPER.TIF", false);

makeGrayFixture("upper_tiff_source.tif", 2, 1, 1);
File.rename(fixtureDirectory + "upper_tiff_source.tif", fixtureDirectory + "UPPER.TIFF");
probeFile("upper_tiff", "UPPER.TIFF", false);

makeGrayFixture("spaces_source.tif", 2, 1, 1);
File.rename(fixtureDirectory + "spaces_source.tif", fixtureDirectory + "fixture with spaces.TIFF");
probeFile("spaces", "fixture with spaces.TIFF", false);
'''

    if native_only:
        dispatch = native_dispatch + '''
File.append("DONE native fixtures", testLog);
run("Close All");
run("Quit");

'''
    else:
        dispatch = native_dispatch + '''

newImage("rgb fixture", "RGB black", 16, 12, 1);
saveAs("PNG", fixtureDirectory + "rgb_source.png");
close();
File.rename(fixtureDirectory + "rgb_source.png", fixtureDirectory + "RGB Fixture.PNG");
probeFile("rgb", "RGB Fixture.PNG", true);

makeGrayFixture("real_z_stack.tif", 2, 3, 1);
probeFile("real_z_stack", "real_z_stack.tif", true);

makeGrayFixture("ambiguous_two_plane.tif", 1, 2, 1);
twoPlaneTestChoice = "Two channels (Keyence/two-page export)";
probeFile("ambiguous_channels", "ambiguous_two_plane.tif", true);
twoPlaneTestChoice = "Z stack (maximum-project)";
probeFile("ambiguous_z", "ambiguous_two_plane.tif", true);

makeGrayFixture("time_series.tif", 1, 1, 2);
probeFile("time_series", "time_series.tif", true);

File.append("DONE fixtures", testLog);
run("Close All");
run("Quit");

'''
    return replace_once(text, dispatch_pattern, dispatch, "top-level dispatch")


def build(macro: Path, work_dir: Path) -> Path:
    source = macro.read_text(encoding="utf-8")
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)
    probe = work_dir / "fixture_probe.ijm"
    probe.write_text(make_probe(source, work_dir), encoding="utf-8", newline="\n")
    return probe


def build_native(macro: Path, work_dir: Path) -> Path:
    source = macro.read_text(encoding="utf-8")
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)
    probe = work_dir / "fixture_probe_native.ijm"
    probe.write_text(make_probe(source, work_dir, native_only=True), encoding="utf-8", newline="\n")
    return probe


def check(macro: Path) -> None:
    source = macro.read_text(encoding="utf-8")
    probe = make_probe(source, DEFAULT_WORK)
    native_probe = make_probe(source, DEFAULT_WORK, native_only=True)
    required = [
        'makeGrayFixture("fixture.tif", 2, 1, 1)',
        'makeGrayFixture("fixture.tiff", 2, 1, 1)',
        'File.rename(fixtureDirectory + "upper_tif_source.tif", fixtureDirectory + "UPPER.TIF")',
        'File.rename(fixtureDirectory + "upper_tiff_source.tif", fixtureDirectory + "UPPER.TIFF")',
        'File.rename(fixtureDirectory + "spaces_source.tif", fixtureDirectory + "fixture with spaces.TIFF")',
        'File.rename(fixtureDirectory + "rgb_source.png", fixtureDirectory + "RGB Fixture.PNG")',
        'makeGrayFixture("real_z_stack.tif", 2, 3, 1)',
        'makeGrayFixture("ambiguous_two_plane.tif", 1, 2, 1)',
        'makeGrayFixture("time_series.tif", 1, 1, 2)',
        'REJECT|time_series|T>1',
        'DONE fixtures',
    ]
    for marker in required:
        if marker not in probe:
            raise AssertionError(f"Generated fixture probe is missing {marker!r}")
    if "DONE native fixtures" not in native_probe:
        raise AssertionError("Generated native-only fixture probe is missing its completion marker")
    if "RGB Fixture.PNG" in native_probe or "ambiguous_two_plane.tif" in native_probe:
        raise AssertionError("Native-only fixture probe unexpectedly includes GUI/Bio-Formats cases")
    print("Runtime fixture-probe generation check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build deterministic Fiji metadata/edge-case fixture probes")
    parser.add_argument("--macro", type=Path, default=DEFAULT_MACRO)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--native-only", action="store_true")
    args = parser.parse_args()
    if args.check:
        check(args.macro)
        return
    if args.native_only:
        print(build_native(args.macro, args.work_dir))
    else:
        print(build(args.macro, args.work_dir))


if __name__ == "__main__":
    main()
