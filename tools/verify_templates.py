from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Replay E7 Orbit template matches.")
    parser.add_argument("--shop", type=Path, required=True)
    parser.add_argument("--refresh", type=Path, required=True)
    parser.add_argument("--covenant", type=Path, required=True)
    parser.add_argument("--mystic", type=Path, required=True)
    parser.add_argument("--purchase", type=Path, required=True)
    parser.add_argument("--mystic-purchase", type=Path, required=True)
    parser.add_argument(
        "--templates",
        type=Path,
        default=Path(
            "app/src/main/assets/vision/cn_1920x1080",
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sources = {
        "shop": args.shop,
        "refresh": args.refresh,
        "covenant": args.covenant,
        "mystic": args.mystic,
        "purchase": args.purchase,
        "mystic_purchase": args.mystic_purchase,
    }
    config = json.loads(
        (args.templates / "regions.json").read_text(encoding="utf-8"),
    )
    definitions = {item["id"]: item for item in config["templates"]}
    checks = [
        ("shop_anchor", "shop"),
        ("shop_anchor", "covenant"),
        ("shop_anchor", "mystic"),
        ("refresh_button", "shop"),
        ("refresh_button", "covenant"),
        ("refresh_button", "mystic"),
        ("purchase_button", "shop"),
        ("purchase_button", "covenant"),
        ("purchase_button", "mystic"),
        ("covenant_item", "covenant"),
        ("mystic_item", "mystic"),
        ("refresh_dialog", "refresh"),
        ("confirm_refresh", "refresh"),
        ("confirm_purchase", "purchase"),
        ("covenant_confirm", "purchase"),
        ("mystic_confirm", "mystic_purchase"),
    ]

    failed = False
    for template_id, source_id in checks:
        definition = definitions[template_id]
        region = definition["region"]
        source = cv2.imread(str(sources[source_id]), cv2.IMREAD_COLOR)
        template = cv2.imread(
            str(args.templates / definition["file"]),
            cv2.IMREAD_COLOR,
        )
        if source is None or template is None:
            raise RuntimeError(f"Unable to load {template_id} on {source_id}")
        roi = source[
            region["top"] : region["bottom"],
            region["left"] : region["right"],
        ]
        result = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
        score = float(cv2.minMaxLoc(result)[1])
        threshold = float(definition["threshold"])
        passed = score >= threshold
        failed = failed or not passed
        print(
            f"{template_id:22} on {source_id:9} "
            f"{score:.4f} / {threshold:.2f} "
            f"{'PASS' if passed else 'FAIL'}",
        )

    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
