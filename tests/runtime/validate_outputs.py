from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = Path(__file__).resolve().parent / "nmj1_numeric_baseline.json"
HEADER_1 = "IMAGE DETAILS (frame size),,NMJ,THRESHOLD,PRE-SYNAPTIC,,,,Branch analysis,,,,,,,,,POST-SYNAPTIC"
HEADER_2 = '"Number of pixels (eg, 512 x 512)","Metric (eg, 67.48 x 67.48um)","Ref number","(nerve terminal/motor endplate)","Number of Axonal Inputs","Axon Diameter (um)","Nerve Terminal Perimeter (um)","Nerve Terminal Area (um2)","Value 0 (background white pixels)","Value 2 (terminal pixels)","Value 4 (three-point branch pixels)","Value 5 (four-point branch pixels)"," Number of Terminal Branches","Number of Branch Points","Total Length of Branches (um)","Average Length of Branches (um)","""Complexity""","AChR Perimeter (um)","AChR Area (um2)","Endplate Diameter (um)","Endplate Perimeter (um)","Endplate Area (um2)","""Compactness"" (%)","Unoccupied AChR Area (um2)","""Area of Synaptic Contact"" (um2)","""Overlap"" (%)","Number of AChR Clusters","Average Area of AChR Clusters (um2)","""Fragmentation"""'


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalized_macro_sha256(path: Path) -> str:
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
    return sha256_bytes(text.encode("utf-8"))


def load_baseline(path: Path) -> dict[str, object]:
    baseline = json.loads(path.read_text(encoding="utf-8"))
    if baseline.get("schema_version") != 1:
        raise AssertionError(f"Unsupported numerical baseline schema: {baseline.get('schema_version')!r}")
    return baseline


def validate_baseline_inputs(baseline: dict[str, object]) -> None:
    reference = baseline["reference"]
    macro = baseline["macro"]
    environment = baseline["validated_environment"]
    reference_path = ROOT / str(reference["path"])
    macro_path = ROOT / "aNMJ-morph macro.txt"
    actual_reference_hash = sha256_bytes(reference_path.read_bytes())
    if actual_reference_hash != reference["sha256"]:
        raise AssertionError(
            f"NMJ_1 reference hash changed: {actual_reference_hash}, expected {reference['sha256']}"
        )
    actual_macro_hash = normalized_macro_sha256(macro_path)
    if actual_macro_hash != macro["normalized_sha256"]:
        raise AssertionError(
            f"Macro changed relative to pinned numerical oracle: {actual_macro_hash}, "
            f"expected {macro['normalized_sha256']}. Update the oracle intentionally if scientific output changed."
        )
    runtime_workflow = (ROOT / ".github" / "workflows" / "fiji-runtime.yml").read_text(encoding="utf-8")
    for key in ("fiji_archive", "fiji_archive_sha256", "morphology_collection_sha256"):
        expected = str(environment[key])
        if expected not in runtime_workflow:
            raise AssertionError(f"Pinned numerical-oracle environment {key}={expected!r} is not used by Fiji runtime CI")


def validate_numeric_oracle(row: list[str], baseline: dict[str, object], row_number: int) -> None:
    for name, spec in baseline["exact_fields"].items():
        column = int(spec["column"])
        expected = str(spec["expected"])
        if row[column] != expected:
            raise AssertionError(
                f"Row {row_number} numerical-oracle field {name} is {row[column]!r}, expected {expected!r}"
            )

    for name, spec in baseline["numeric_fields"].items():
        column = int(spec["column"])
        expected = float(spec["expected"])
        tolerance = float(spec["abs_tolerance"])
        try:
            actual = float(row[column])
        except ValueError as exc:
            raise AssertionError(
                f"Row {row_number} numerical-oracle field {name} is not numeric: {row[column]!r}"
            ) from exc
        if not math.isclose(actual, expected, rel_tol=0.0, abs_tol=tolerance):
            raise AssertionError(
                f"Row {row_number} numerical-oracle field {name} is {actual}, expected {expected} ± {tolerance}"
            )


def validate_case(
    case_dir: Path,
    expected_dimensions: str,
    expected_name: str,
    runs: int,
    numerical_baseline: dict[str, object] | None = None,
) -> None:
    csv_path = case_dir / "input" / "raw_data_table.csv"
    if not csv_path.exists():
        raise AssertionError(f"Missing output CSV: {csv_path}")

    raw = csv_path.read_bytes()
    if not raw.endswith(b"\n"):
        raise AssertionError(f"CSV does not end with a newline: {csv_path}")
    text = raw.decode("utf-8", errors="replace")
    if "nan" in text.lower():
        raise AssertionError(f"CSV contains NaN: {csv_path}")

    rows = list(csv.reader(text.splitlines()))
    if len(rows) != 2 + runs:
        raise AssertionError(f"Expected {2 + runs} CSV rows, found {len(rows)} in {csv_path}")
    lines = text.splitlines()
    if lines[0] != HEADER_1 or lines[1] != HEADER_2:
        raise AssertionError(f"Historical CSV headers changed in {csv_path}")

    for offset, row in enumerate(rows[2:], start=3):
        if len(row) != 29:
            raise AssertionError(f"Row {offset} has {len(row)} columns, expected 29")
        if row[0] != expected_dimensions:
            raise AssertionError(f"Row {offset} dimensions are {row[0]!r}, expected {expected_dimensions!r}")
        if row[2] != expected_name:
            raise AssertionError(f"Row {offset} input name is {row[2]!r}, expected {expected_name!r}")
        if row[12] != f"=J{offset}":
            raise AssertionError(f"Row {offset} terminal-branch formula is wrong: {row[12]!r}")
        if row[13] != f"=(K{offset}+L{offset})*0.28":
            raise AssertionError(f"Row {offset} branch-point formula is wrong: {row[13]!r}")
        if row[15] != f"=O{offset}/J{offset}":
            raise AssertionError(f"Row {offset} average-branch-length formula is wrong: {row[15]!r}")
        if numerical_baseline is not None:
            validate_numeric_oracle(row, numerical_baseline, offset)

    cleaned = case_dir / "input" / "cleaned_images"
    stem = Path(expected_name).stem
    expected_outputs = [
        cleaned / f"axon_terminal{stem}.tif",
        cleaned / f"muscle_endplate{stem}.tif",
        cleaned / f"muscle_intermediate_endplate{stem}.tif",
    ]
    for output in expected_outputs:
        if not output.exists() or output.stat().st_size == 0:
            raise AssertionError(f"Missing or empty cleaned output: {output}")

    trace = case_dir / "trace.txt"
    if not trace.exists() or "DONE stage 7" not in trace.read_text(encoding="utf-8", errors="replace"):
        raise AssertionError(f"Harness did not reach stage 7: {trace}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate generated aNMJ-morph+ runtime outputs")
    parser.add_argument("--work-dir", type=Path)
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--check-baseline", action="store_true")
    args = parser.parse_args()

    baseline = load_baseline(args.baseline)
    validate_baseline_inputs(baseline)
    if args.check_baseline:
        print("NMJ_1 numerical baseline provenance check passed")
        return
    if args.work_dir is None:
        parser.error("--work-dir is required unless --check-baseline is used")

    validate_case(
        args.work_dir / "square",
        "512 x 512",
        "NMJ_1.lsm",
        args.runs,
        numerical_baseline=baseline,
    )
    validate_case(
        args.work_dir / "rectangular",
        "384 x 512",
        "NMJ_1_rect_384x512.tif",
        args.runs,
    )
    validate_case(
        args.work_dir / "java-bridge",
        "512 x 512",
        "NMJ_1.lsm",
        1,
        numerical_baseline=baseline,
    )
    print("Runtime outputs passed CSV, append, formula, numerical-oracle, Java-bridge, NaN, and cleaned-image checks")


if __name__ == "__main__":
    main()
