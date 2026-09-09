from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import tempfile


@dataclass(frozen=True)
class Record:
    name: str
    basename: str
    width: int
    height: int
    channels: int
    slices: int
    frames: int
    bit_depth: int
    pixel_width: float
    pixel_height: float
    unit: str


def parse_trace(path: Path) -> tuple[dict[str, Record], set[str], str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    records: dict[str, Record] = {}
    rejects: set[str] = set()
    for line in text.splitlines():
        parts = line.split("|")
        if parts[0] == "CASE" and len(parts) == 12:
            records[parts[1]] = Record(
                name=parts[1],
                basename=parts[2],
                width=int(float(parts[3])),
                height=int(float(parts[4])),
                channels=int(float(parts[5])),
                slices=int(float(parts[6])),
                frames=int(float(parts[7])),
                bit_depth=int(float(parts[8])),
                pixel_width=float(parts[9]),
                pixel_height=float(parts[10]),
                unit=parts[11],
            )
        elif parts[0] == "REJECT" and len(parts) >= 2:
            rejects.add(parts[1])
    return records, rejects, text


def require_record(records: dict[str, Record], name: str, **expected: object) -> Record:
    if name not in records:
        raise AssertionError(f"Missing fixture record: {name}")
    record = records[name]
    for field, value in expected.items():
        actual = getattr(record, field)
        if actual != value:
            raise AssertionError(f"{name}.{field} is {actual!r}, expected {value!r}")
    return record


def validate_native_records(records: dict[str, Record]) -> None:
    native_cases = {
        "tif_raw": "fixture.tif",
        "tiff_raw": "fixture.tiff",
        "upper_tif_raw": "UPPER.TIF",
        "upper_tiff_raw": "UPPER.TIFF",
        "spaces_raw": "fixture with spaces.TIFF",
    }
    for name, basename in native_cases.items():
        record = require_record(
            records,
            name,
            basename=basename,
            width=16,
            height=12,
            channels=2,
            slices=1,
            frames=1,
            bit_depth=8,
        )
        if abs(record.pixel_width - 0.5) > 1e-6 or abs(record.pixel_height - 0.5) > 1e-6:
            raise AssertionError(f"{name} lost TIFF calibration: {record.pixel_width} x {record.pixel_height}")


def validate(trace: Path, native_only: bool = False) -> None:
    records, rejects, text = parse_trace(trace)
    completion_marker = "DONE native fixtures" if native_only else "DONE fixtures"
    if completion_marker not in text:
        raise AssertionError(f"Fixture probe did not complete: missing {completion_marker!r}")

    validate_native_records(records)
    if native_only:
        print("Headless native fixture probe passed TIFF extension, naming, and calibration checks")
        return

    require_record(records, "rgb_raw", basename="RGB Fixture.PNG", width=16, height=12)
    require_record(
        records,
        "rgb_normalized",
        basename="RGB Fixture.PNG",
        width=16,
        height=12,
        channels=3,
        slices=1,
        frames=1,
        bit_depth=8,
    )

    require_record(
        records,
        "real_z_stack_raw",
        basename="real_z_stack.tif",
        channels=2,
        slices=3,
        frames=1,
    )
    require_record(
        records,
        "real_z_stack_normalized",
        basename="real_z_stack.tif",
        channels=2,
        slices=3,
        frames=1,
    )

    require_record(
        records,
        "ambiguous_channels_normalized",
        basename="ambiguous_two_plane.tif",
        channels=2,
        slices=1,
        frames=1,
    )
    require_record(
        records,
        "ambiguous_z_normalized",
        basename="ambiguous_two_plane.tif",
        channels=1,
        slices=2,
        frames=1,
    )

    if "time_series" not in rejects:
        raise AssertionError("T>1 fixture did not reach the explicit rejection branch")
    require_record(
        records,
        "time_series_normalized",
        basename="time_series.tif",
        channels=1,
        slices=1,
        frames=2,
    )
    print("Runtime fixture probe passed extension, naming, RGB, Z/C/T, and calibration checks")


def self_check() -> None:
    lines = [
        "START",
        "CASE|tif_raw|fixture.tif|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|tiff_raw|fixture.tiff|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|upper_tif_raw|UPPER.TIF|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|upper_tiff_raw|UPPER.TIFF|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|spaces_raw|fixture with spaces.TIFF|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|rgb_raw|RGB Fixture.PNG|16|12|3|1|1|8|1.000000|1.000000|pixel",
        "CASE|rgb_normalized|RGB Fixture.PNG|16|12|3|1|1|8|1.000000|1.000000|pixel",
        "CASE|real_z_stack_raw|real_z_stack.tif|16|12|2|3|1|8|0.500000|0.500000|microns",
        "CASE|real_z_stack_normalized|real_z_stack.tif|16|12|2|3|1|8|0.500000|0.500000|microns",
        "CASE|ambiguous_channels_normalized|ambiguous_two_plane.tif|16|12|2|1|1|8|0.500000|0.500000|microns",
        "CASE|ambiguous_z_normalized|ambiguous_two_plane.tif|16|12|1|2|1|8|0.500000|0.500000|microns",
        "REJECT|time_series|T>1",
        "CASE|time_series_normalized|time_series.tif|16|12|1|1|2|8|0.500000|0.500000|microns",
        "DONE fixtures",
    ]
    with tempfile.TemporaryDirectory() as tmp:
        trace = Path(tmp) / "fixture_probe.txt"
        trace.write_text("\n".join(lines) + "\n", encoding="utf-8")
        validate(trace)
    print("Runtime fixture validator self-check passed")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate deterministic Fiji runtime fixture metadata")
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--native-only", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        self_check()
        return
    if args.trace is None:
        parser.error("--trace is required unless --check is used")
    validate(args.trace, native_only=args.native_only)


if __name__ == "__main__":
    main()
