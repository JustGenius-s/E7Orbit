#!/usr/bin/env python3
"""Upload curated Epic Seven exclusive-equipment icons to Supabase Storage."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


DEFAULT_SUPABASE_URL = "https://biayslzufpixsyuitjus.supabase.co"
DEFAULT_BUCKET = "Epic7"
FILE_PATTERN = re.compile(r"^icon_eq_exclusive_(c\d+)\.png$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path(
            os.environ.get(
                "E7_CURATED_ITEM_DIR",
                "~/Temp/E7Data_curated/item",
            )
        ).expanduser(),
        help="Directory containing icon_eq_exclusive_c*.png files",
    )
    parser.add_argument(
        "--supabase-url",
        default=os.environ.get("SUPABASE_URL", DEFAULT_SUPABASE_URL),
        help="Supabase project URL",
    )
    parser.add_argument(
        "--bucket",
        default=os.environ.get("SUPABASE_STORAGE_BUCKET", DEFAULT_BUCKET),
        help="Supabase Storage bucket",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=6,
        help="Number of concurrent uploads (default: 6)",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=3,
        help="Attempts per file after the first failure (default: 3)",
    )
    parser.add_argument(
        "--verify",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Verify each canonical public URL and SHA-256 (default: enabled)",
    )
    parser.add_argument(
        "--purge-only",
        action="store_true",
        help="Skip upload; purge CDN cache and verify the existing source objects",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List the files and target paths without uploading",
    )
    return parser.parse_args()


def clean_url(value: str) -> str:
    return value.rstrip("/")


def storage_path(hero_code: str, bucket: str) -> str:
    return f"{bucket.strip('/')}/exclusive-equipment/{hero_code}/icon.png"


def public_url(base_url: str, path: str) -> str:
    return f"{clean_url(base_url)}/storage/v1/object/public/{path}"


def upload_url(base_url: str, path: str) -> str:
    return f"{clean_url(base_url)}/storage/v1/object/{path}"


def purge_url(base_url: str, path: str) -> str:
    return f"{clean_url(base_url)}/storage/v1/cdn/{path}"


def legacy_service_role(key: str) -> bool:
    try:
        payload = key.split(".")[1]
        padding = "=" * (-len(payload) % 4)
        decoded = base64.urlsafe_b64decode(payload + padding)
        return json.loads(decoded).get("role") == "service_role"
    except (IndexError, ValueError, json.JSONDecodeError):
        return False


def validate_admin_key(secret_key: str) -> str | None:
    if secret_key.startswith("sb_secret_"):
        return None
    if secret_key.count(".") == 2 and legacy_service_role(secret_key):
        return None
    if re.fullmatch(r"[0-9a-fA-F-]{36}", secret_key):
        return (
            "The supplied value is a UUID, not a Supabase secret key. Use an "
            "sb_secret_ key or the legacy service-role JWT from Project Settings > API."
        )
    return (
        "Unsupported Supabase key. Expected an sb_secret_ key or a legacy JWT "
        "whose role is service_role."
    )


def curl_config(secret_key: str) -> bytes:
    lines = [f'header = "apikey: {secret_key}"']
    if secret_key.count(".") == 2:
        lines.append(f'header = "Authorization: Bearer {secret_key}"')
    return ("\n".join(lines) + "\n").encode("utf-8")


def run_curl(
    arguments: list[str],
    *,
    secret_key: str | None = None,
    timeout: int,
) -> subprocess.CompletedProcess[bytes]:
    command = ["curl", "--silent", "--show-error", "--fail-with-body", "--location"]
    input_data = None
    if secret_key:
        command.extend(["--config", "-"])
        input_data = curl_config(secret_key)
    command.extend(arguments)
    return subprocess.run(
        command,
        input=input_data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=timeout,
    )


def curl_error(result: subprocess.CompletedProcess[bytes]) -> str:
    stderr = result.stderr.decode("utf-8", errors="replace").strip()
    stdout = result.stdout.decode("utf-8", errors="replace").strip()
    detail = stderr or stdout or f"curl exited with status {result.returncode}"
    return detail[:320]


def validate_admin_access(
    base_url: str,
    bucket: str,
    secret_key: str,
) -> tuple[bool, str]:
    try:
        result = run_curl(
            [f"{base_url}/storage/v1/bucket/{bucket.strip('/')}"],
            secret_key=secret_key,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, str(error)
    return (True, "authorized") if result.returncode == 0 else (False, curl_error(result))


def upload_one(
    source: Path,
    target_path: str,
    base_url: str,
    secret_key: str,
    retries: int,
) -> tuple[bool, str]:
    last_error = "unknown error"
    for attempt in range(retries + 1):
        try:
            result = run_curl(
                [
                    "--request",
                    "POST",
                    "--header",
                    "Content-Type: image/png",
                    "--header",
                    "Cache-Control: public, max-age=31536000, immutable",
                    "--header",
                    "x-upsert: true",
                    "--data-binary",
                    f"@{source}",
                    upload_url(base_url, target_path),
                ],
                secret_key=secret_key,
                timeout=60,
            )
            if result.returncode == 0:
                return True, "uploaded"
            last_error = curl_error(result)
        except (OSError, subprocess.TimeoutExpired) as error:
            last_error = str(error)
        if attempt < retries:
            time.sleep(2**attempt)
    return False, last_error


def purge_one(
    target_path: str,
    base_url: str,
    secret_key: str,
    retries: int,
) -> tuple[bool, str]:
    last_error = "unknown error"
    for attempt in range(retries + 1):
        try:
            result = run_curl(
                ["--request", "DELETE", purge_url(base_url, target_path)],
                secret_key=secret_key,
                timeout=30,
            )
            if result.returncode == 0:
                return True, "CDN purge queued"
            last_error = curl_error(result)
        except (OSError, subprocess.TimeoutExpired) as error:
            last_error = str(error)
        if attempt < retries:
            time.sleep(2**attempt)
    return False, last_error


def verify_one(
    url: str,
    source: Path,
    attempts: int = 13,
    delay_seconds: int = 5,
) -> tuple[bool, str]:
    expected = source.read_bytes()
    expected_digest = hashlib.sha256(expected).hexdigest()
    last_detail = "unknown error"
    for attempt in range(attempts):
        try:
            result = run_curl([url], timeout=30)
        except (OSError, subprocess.TimeoutExpired) as error:
            last_detail = str(error)
        else:
            if result.returncode != 0:
                last_detail = curl_error(result)
            else:
                actual = result.stdout
                actual_digest = hashlib.sha256(actual).hexdigest()
                if actual_digest == expected_digest:
                    return True, f"HTTP 200, {len(actual)} bytes, SHA-256 matched"
                last_detail = (
                    f"SHA-256 mismatch: local {expected_digest[:12]}, "
                    f"remote {actual_digest[:12]}"
                )
        if attempt + 1 < attempts:
            time.sleep(delay_seconds)
    return False, last_detail


def collect_sources(source_dir: Path) -> list[tuple[str, Path]]:
    if not source_dir.is_dir():
        raise FileNotFoundError(f"Source directory does not exist: {source_dir}")
    sources = []
    for source in sorted(source_dir.iterdir()):
        match = FILE_PATTERN.fullmatch(source.name)
        if match and source.is_file():
            sources.append((match.group(1), source))
    if not sources:
        raise FileNotFoundError(
            f"No icon_eq_exclusive_c*.png files found in {source_dir}"
        )
    return sources


def main() -> int:
    arguments = parse_args()
    if arguments.workers < 1 or arguments.retries < 0:
        print("--workers must be >= 1 and --retries must be >= 0", file=sys.stderr)
        return 2

    try:
        sources = collect_sources(arguments.source_dir.expanduser().resolve())
    except FileNotFoundError as error:
        print(str(error), file=sys.stderr)
        return 2

    base_url = clean_url(arguments.supabase_url)
    targets = [
        (hero_code, source, storage_path(hero_code, arguments.bucket))
        for hero_code, source in sources
    ]
    print(f"Found {len(targets)} icons in {arguments.source_dir}")
    if arguments.dry_run:
        for _hero_code, source, target in targets:
            print(f"  {source.name} -> {target}")
        return 0

    secret_key = (
        os.environ.get("SUPABASE_SECRET_KEY")
        or os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
        or ""
    ).strip()
    if not secret_key:
        print(
            "Missing SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY. "
            "Set it only in the current shell.",
            file=sys.stderr,
        )
        return 2
    key_error = validate_admin_key(secret_key)
    if key_error:
        print(key_error, file=sys.stderr)
        return 2

    print("Checking Supabase Storage admin access...")
    authorized, detail = validate_admin_access(base_url, arguments.bucket, secret_key)
    if not authorized:
        print(f"Supabase admin preflight failed: {detail}", file=sys.stderr)
        return 2

    if not arguments.purge_only:
        failed: list[tuple[str, str]] = []
        uploaded = 0
        with ThreadPoolExecutor(max_workers=arguments.workers) as executor:
            pending = {
                executor.submit(
                    upload_one,
                    source,
                    target,
                    base_url,
                    secret_key,
                    arguments.retries,
                ): hero_code
                for hero_code, source, target in targets
            }
            for future in as_completed(pending):
                hero_code = pending[future]
                ok, detail = future.result()
                if ok:
                    uploaded += 1
                    print(f"[{uploaded}/{len(targets)}] {hero_code}: {detail}")
                else:
                    failed.append((hero_code, detail))
                    print(f"[UPLOAD FAILED] {hero_code}: {detail}", file=sys.stderr)

        if failed:
            print(f"Upload failed for {len(failed)} of {len(targets)} files.", file=sys.stderr)
            return 1

    print(f"Purging {len(targets)} canonical URLs from Smart CDN...")
    purge_failed: list[tuple[str, str]] = []
    purged = 0
    with ThreadPoolExecutor(max_workers=arguments.workers) as executor:
        pending = {
            executor.submit(
                purge_one,
                target,
                base_url,
                secret_key,
                arguments.retries,
            ): hero_code
            for hero_code, _source, target in targets
        }
        for future in as_completed(pending):
            hero_code = pending[future]
            ok, detail = future.result()
            if ok:
                purged += 1
                print(f"[{purged}/{len(targets)}] {hero_code}: {detail}")
            else:
                purge_failed.append((hero_code, detail))
                print(f"[PURGE FAILED] {hero_code}: {detail}", file=sys.stderr)

    if purge_failed:
        print(f"CDN purge failed for {len(purge_failed)} of {len(targets)} files.", file=sys.stderr)
        return 1

    if arguments.verify:
        print(f"Verifying {len(targets)} public URLs...")
        verification_failed: list[tuple[str, str]] = []
        with ThreadPoolExecutor(max_workers=arguments.workers) as executor:
            pending = {
                executor.submit(
                    verify_one,
                    public_url(base_url, target),
                    source,
                ): hero_code
                for hero_code, source, target in targets
            }
            for future in as_completed(pending):
                hero_code = pending[future]
                ok, detail = future.result()
                if ok:
                    print(f"[OK] {hero_code}: {detail}")
                else:
                    verification_failed.append((hero_code, detail))
                    print(f"[VERIFY FAILED] {hero_code}: {detail}", file=sys.stderr)
        if verification_failed:
            print(
                f"Verification failed for {len(verification_failed)} of {len(targets)} files.",
                file=sys.stderr,
            )
            return 1

    action = "Purged and verified" if arguments.purge_only else "Uploaded, purged, and verified"
    print(f"{action} {len(targets)} exclusive-equipment icons.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
