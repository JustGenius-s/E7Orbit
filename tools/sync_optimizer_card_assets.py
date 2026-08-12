#!/usr/bin/env python3
"""Import curated assets used by optimizer hero build cards."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil


CARD_ASSETS = {
    "e7_optimizer_hero_frame.png": "img/_hero_s_frame_ally.png",
    "e7_optimizer_gear_frame.png": "img/cm_item_slot.png",
    "e7_optimizer_artifact_frame.png": "img/cm_item_slot_arti.png",
    "e7_optimizer_exclusive_frame.png": "img/cm_item_slot_private.png",
    "e7_optimizer_gear_footer.png": "img/_box_equip_bottom.png",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--curated-root",
        type=Path,
        required=True,
        help="Root of the extracted E7Data_curated directory",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    return parser.parse_args()


def main() -> None:
    arguments = parse_args()
    curated_root = arguments.curated_root.resolve()
    resource_dir = arguments.project_root / "app/src/main/res-optimizer-assets/drawable-nodpi"
    resource_dir.mkdir(parents=True, exist_ok=True)

    for old_asset in resource_dir.glob("e7_optimizer_*.png"):
        old_asset.unlink()

    for destination_name, relative_source in CARD_ASSETS.items():
        source = curated_root / relative_source
        if not source.is_file():
            raise FileNotFoundError(source)
        shutil.copyfile(source, resource_dir / destination_name)

    print(f"Imported {len(CARD_ASSETS)} optimizer card UI assets")


if __name__ == "__main__":
    main()
