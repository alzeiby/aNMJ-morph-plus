from __future__ import annotations

import argparse
import csv
from pathlib import Path


def validate_case(case_dir: Path, expected_dimensions: str, expected_name: str, runs: int) -> None:
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
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=2)
    args = parser.parse_args()

    validate_case(args.work_dir / "square", "512 x 512", "NMJ_1.lsm", args.runs)
    validate_case(
        args.work_dir / "rectangular",
        "384 x 512",
        "NMJ_1_rect_384x512.tif",
        args.runs,
    )
    print("Runtime outputs passed CSV, append, formula, NaN, and cleaned-image checks")


if __name__ == "__main__":
    main()
