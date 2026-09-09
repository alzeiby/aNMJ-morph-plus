from __future__ import annotations

import argparse
from pathlib import Path

from validate_outputs import load_baseline, sha256_bytes, validate_case


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = Path(__file__).resolve().parent / "nmj1_numeric_baseline.json"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate direct-Java analysis outputs against the unchanged NMJ_1 oracle"
    )
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    args = parser.parse_args()

    baseline = load_baseline(args.baseline)
    reference = baseline["reference"]
    reference_path = ROOT / str(reference["path"])
    actual_reference_hash = sha256_bytes(reference_path.read_bytes())
    if actual_reference_hash != reference["sha256"]:
        raise AssertionError(
            f"NMJ_1 reference hash changed: {actual_reference_hash}, expected {reference['sha256']}"
        )

    automation = baseline["automation"]
    if automation["threshold_method"] != "Default/Default":
        raise AssertionError("Direct-Java runtime requires the pinned Default/Default threshold oracle")
    if automation["axon_width_lines_pixels"] != [10, 12, 8]:
        raise AssertionError("Direct-Java runtime requires pinned 10/12/8 axon-width automation")
    if automation["stage6_segmentation_accepted"] is not True:
        raise AssertionError("Direct-Java runtime requires pinned Screen 6 acceptance")

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
    print(
        "Direct-Java outputs passed unchanged NMJ_1 numerical oracle, CSV/formula, "
        "Runs=2 append, rectangular, NaN, and cleaned-image checks"
    )


if __name__ == "__main__":
    main()
