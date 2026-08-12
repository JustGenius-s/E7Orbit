#!/usr/bin/env python3
"""Migrate curated artifact artwork and icons to layered Supabase paths."""

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
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote


DEFAULT_SUPABASE_URL = "https://biayslzufpixsyuitjus.supabase.co"
DEFAULT_BUCKET = "Epic7"
ARTWORK_PATTERN = re.compile(r"^(art[0-9_]+)_fu\.png$")
LEGACY_IMAGE_EXTENSIONS = ("png", "webp", "jpg", "jpeg")


@dataclass(frozen=True)
class ArtifactSource:
    asset_id: str
    artwork: Path
    icon: Path


@dataclass(frozen=True)
class ArtifactTarget:
    code: str
    name: str
    source: ArtifactSource
    image_path: str
    icon_path: str
    legacy_paths: tuple[str, ...]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path(
            os.environ.get(
                "E7_CURATED_ARTIFACT_DIR",
                "~/Temp/E7Data_curated/item_arti",
            )
        ).expanduser(),
        help="Directory containing art*_fu.png and icon_art*.png",
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
        "--catalog-json",
        type=Path,
        help="Use an exported artifact_catalog JSON array instead of REST",
    )
    parser.add_argument(
        "--asset-map",
        type=Path,
        default=Path(__file__).with_name("artifact-asset-overrides.json"),
        help="JSON object mapping catalog rows without legacy images to local asset IDs",
    )
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument(
        "--verify",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Verify every canonical URL by SHA-256 (default: enabled)",
    )
    parser.add_argument(
        "--allow-unmapped",
        action="store_true",
        help="Migrate matched rows even if some catalog rows cannot be mapped",
    )
    parser.add_argument(
        "--skip-database",
        action="store_true",
        help="Upload and verify objects without changing artifact_catalog",
    )
    parser.add_argument(
        "--delete-legacy",
        action="store_true",
        help="Delete old flat artifacts/{code}.ext objects after database verification",
    )
    parser.add_argument(
        "--cleanup-legacy-only",
        action="store_true",
        help="Verify the completed migration and delete only old flat objects",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve mappings and print target paths without writing",
    )
    return parser.parse_args()


def clean_url(value: str) -> str:
    return value.rstrip("/")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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
            "sb_secret_ key or the legacy service-role JWT."
        )
    return (
        "Unsupported Supabase key. Expected an sb_secret_ key or a legacy JWT "
        "whose role is service_role."
    )


def curl_config(api_key: str) -> bytes:
    lines = [f'header = "apikey: {api_key}"']
    if api_key.count(".") == 2:
        lines.append(f'header = "Authorization: Bearer {api_key}"')
    return ("\n".join(lines) + "\n").encode()


def run_curl(
    arguments: list[str],
    *,
    api_key: str | None = None,
    timeout: int,
) -> subprocess.CompletedProcess[bytes]:
    command = ["curl", "--silent", "--show-error", "--fail-with-body", "--location"]
    input_data = None
    if api_key:
        command.extend(["--config", "-"])
        input_data = curl_config(api_key)
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
    stderr = result.stderr.decode(errors="replace").strip()
    stdout = result.stdout.decode(errors="replace").strip()
    return (stderr or stdout or f"curl exited with status {result.returncode}")[:400]


def retry_curl(
    arguments: list[str],
    *,
    api_key: str | None,
    retries: int,
    timeout: int,
) -> subprocess.CompletedProcess[bytes]:
    last_result = None
    for attempt in range(retries + 1):
        last_result = run_curl(arguments, api_key=api_key, timeout=timeout)
        if last_result.returncode == 0:
            return last_result
        if attempt < retries:
            time.sleep(2**attempt)
    assert last_result is not None
    return last_result


def storage_object_url(base_url: str, bucket: str, path: str) -> str:
    return f"{base_url}/storage/v1/object/{bucket.strip('/')}/{path}"


def public_url(base_url: str, bucket: str, path: str) -> str:
    return f"{base_url}/storage/v1/object/public/{bucket.strip('/')}/{path}"


def purge_url(base_url: str, bucket: str, path: str) -> str:
    return f"{base_url}/storage/v1/cdn/{bucket.strip('/')}/{path}"


def collect_sources(source_dir: Path) -> list[ArtifactSource]:
    if not source_dir.is_dir():
        raise FileNotFoundError(f"Source directory does not exist: {source_dir}")
    sources = []
    for artwork in sorted(source_dir.glob("art*_fu.png")):
        match = ARTWORK_PATTERN.fullmatch(artwork.name)
        if not match:
            continue
        asset_id = match.group(1)
        icon = source_dir / f"icon_{asset_id}.png"
        if not icon.is_file():
            raise FileNotFoundError(f"Missing icon paired with {artwork.name}: {icon.name}")
        sources.append(ArtifactSource(asset_id, artwork, icon))
    if not sources:
        raise FileNotFoundError(f"No art*_fu.png files found in {source_dir}")
    return sources


def load_asset_map(path: Path | None) -> dict[str, str]:
    if not path:
        return {}
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or not all(
        isinstance(key, str) and isinstance(item, str) for key, item in value.items()
    ):
        raise ValueError("--asset-map must contain a JSON object of string pairs")
    return value


def load_catalog(
    catalog_json: Path | None,
    base_url: str,
    api_key: str,
) -> list[dict]:
    if catalog_json:
        rows = json.loads(catalog_json.read_text())
    else:
        select = quote("code,name,image_url,icon_url", safe=",")
        result = run_curl(
            [f"{base_url}/rest/v1/artifact_catalog?select={select}&order=code"],
            api_key=api_key,
            timeout=45,
        )
        if result.returncode != 0:
            raise RuntimeError(f"artifact_catalog read failed: {curl_error(result)}")
        rows = json.loads(result.stdout)
    if not isinstance(rows, list):
        raise ValueError("Artifact catalog must be a JSON array")
    return rows


def download_digest(url: str, retries: int) -> str:
    result = retry_curl([url], api_key=None, retries=retries, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(curl_error(result))
    return hashlib.sha256(result.stdout).hexdigest()


def legacy_storage_paths(
    url: str | None,
    code: str,
    base_url: str,
    bucket: str,
) -> tuple[str, ...]:
    paths = [f"artifacts/{code}.{extension}" for extension in LEGACY_IMAGE_EXTENSIONS]
    if not url:
        return tuple(paths)

    prefix = f"{base_url}/storage/v1/object/public/{bucket.strip('/')}/artifacts/"
    value = str(url).split("?", 1)[0]
    if not value.startswith(prefix):
        return tuple(paths)
    current_path = value.removeprefix(
        f"{base_url}/storage/v1/object/public/{bucket.strip('/')}/"
    )
    if "/" in current_path.removeprefix("artifacts/"):
        return tuple(paths)
    if not re.fullmatch(
        rf"artifacts/{re.escape(code)}\.(?:{'|'.join(LEGACY_IMAGE_EXTENSIONS)})",
        current_path,
    ):
        return tuple(paths)
    return tuple(dict.fromkeys((current_path, *paths)))


def resolve_targets(
    rows: list[dict],
    sources: list[ArtifactSource],
    overrides: dict[str, str],
    base_url: str,
    bucket: str,
    retries: int,
    workers: int,
) -> tuple[list[ArtifactTarget], list[tuple[str, str, str]]]:
    by_id = {source.asset_id: source for source in sources}
    by_artwork_hash: dict[str, list[ArtifactSource]] = defaultdict(list)
    icon_hashes = {}
    for source in sources:
        by_artwork_hash[sha256_file(source.artwork)].append(source)
        icon_hashes[source.asset_id] = sha256_file(source.icon)

    remote_hashes: dict[str, str] = {}
    errors: dict[str, str] = {}

    def fetch_row(row: dict) -> tuple[str, str]:
        return str(row["code"]), download_digest(str(row["image_url"]), retries)

    downloadable = [
        row
        for row in rows
        if row.get("code") not in overrides and row.get("image_url")
    ]
    with ThreadPoolExecutor(max_workers=workers) as executor:
        pending = {executor.submit(fetch_row, row): str(row.get("code")) for row in downloadable}
        for future in as_completed(pending):
            code = pending[future]
            try:
                resolved_code, digest = future.result()
                remote_hashes[resolved_code] = digest
            except (OSError, subprocess.TimeoutExpired, RuntimeError) as error:
                errors[code] = str(error)

    targets = []
    unmapped = []
    for row in rows:
        code = str(row.get("code") or "").strip()
        name = str(row.get("name") or code)
        if not code:
            unmapped.append((code, name, "missing code"))
            continue

        if code in overrides:
            source = by_id.get(overrides[code])
            if not source:
                unmapped.append((code, name, f"unknown override asset {overrides[code]}"))
                continue
        elif code in errors:
            unmapped.append((code, name, f"download failed: {errors[code]}"))
            continue
        elif code not in remote_hashes:
            unmapped.append((code, name, "current image_url is empty"))
            continue
        else:
            candidates = by_artwork_hash.get(remote_hashes[code], [])
            if not candidates:
                unmapped.append((code, name, "current artwork did not match a curated file"))
                continue
            candidate_icon_hashes = {icon_hashes[candidate.asset_id] for candidate in candidates}
            if len(candidate_icon_hashes) != 1:
                ids = ", ".join(candidate.asset_id for candidate in candidates)
                unmapped.append((code, name, f"ambiguous artwork aliases: {ids}"))
                continue
            source = sorted(
                candidates,
                key=lambda candidate: ("_" in candidate.asset_id, candidate.asset_id),
            )[0]

        targets.append(
            ArtifactTarget(
                code=code,
                name=name,
                source=source,
                image_path=f"artifacts/{code}/image.png",
                icon_path=f"artifacts/{code}/icon.png",
                legacy_paths=legacy_storage_paths(
                    row.get("image_url"), code, base_url, bucket
                ),
            )
        )
    return targets, unmapped


def validate_admin_access(base_url: str, bucket: str, secret_key: str) -> None:
    result = run_curl(
        [f"{base_url}/storage/v1/bucket/{bucket.strip('/')}"],
        api_key=secret_key,
        timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Supabase admin preflight failed: {curl_error(result)}")


def upload_file(
    source: Path,
    path: str,
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
) -> None:
    result = retry_curl(
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
            storage_object_url(base_url, bucket, path),
        ],
        api_key=secret_key,
        retries=retries,
        timeout=60,
    )
    if result.returncode != 0:
        raise RuntimeError(curl_error(result))


def purge_file(
    path: str,
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
) -> None:
    result = retry_curl(
        ["--request", "DELETE", purge_url(base_url, bucket, path)],
        api_key=secret_key,
        retries=retries,
        timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(curl_error(result))


def verify_file(
    source: Path,
    path: str,
    base_url: str,
    bucket: str,
    attempts: int = 13,
) -> None:
    expected = sha256_file(source)
    last_actual = "no response"
    for attempt in range(attempts):
        result = run_curl([public_url(base_url, bucket, path)], timeout=30)
        if result.returncode == 0:
            actual = hashlib.sha256(result.stdout).hexdigest()
            if actual == expected:
                return
            last_actual = f"SHA-256 {actual[:12]}"
        else:
            last_actual = curl_error(result)
        if attempt + 1 < attempts:
            time.sleep(5)
    raise RuntimeError(f"expected SHA-256 {expected[:12]}, got {last_actual}")


def update_catalog_row(
    target: ArtifactTarget,
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
) -> None:
    body = json.dumps(
        {
            "image_url": public_url(base_url, bucket, target.image_path),
            "icon_url": public_url(base_url, bucket, target.icon_path),
        },
        separators=(",", ":"),
    )
    code = quote(target.code, safe="")
    result = retry_curl(
        [
            "--request",
            "PATCH",
            "--header",
            "Content-Type: application/json",
            "--header",
            "Prefer: return=minimal",
            "--data-binary",
            body,
            f"{base_url}/rest/v1/artifact_catalog?code=eq.{code}",
        ],
        api_key=secret_key,
        retries=retries,
        timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(curl_error(result))


def verify_catalog(
    targets: list[ArtifactTarget],
    base_url: str,
    bucket: str,
    secret_key: str,
) -> None:
    expected = {
        target.code: (
            public_url(base_url, bucket, target.image_path),
            public_url(base_url, bucket, target.icon_path),
        )
        for target in targets
    }
    select = quote("code,image_url,icon_url", safe=",")
    result = run_curl(
        [f"{base_url}/rest/v1/artifact_catalog?select={select}"],
        api_key=secret_key,
        timeout=45,
    )
    if result.returncode != 0:
        raise RuntimeError(f"artifact_catalog verification failed: {curl_error(result)}")
    actual = {
        row["code"]: (row.get("image_url"), row.get("icon_url"))
        for row in json.loads(result.stdout)
    }
    mismatched = [code for code, urls in expected.items() if actual.get(code) != urls]
    if mismatched:
        raise RuntimeError(f"artifact_catalog URL verification failed for: {', '.join(mismatched)}")


def object_missing(error: str) -> bool:
    value = error.lower()
    return "404" in value or "not found" in value or "nosuchkey" in value


def curl_failure_details(result: subprocess.CompletedProcess[bytes]) -> str:
    output = result.stdout.decode(errors="replace").strip()
    return f"{curl_error(result)} {output}".strip()[:800]


def delete_storage_object(
    path: str,
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
) -> None:
    result = retry_curl(
        ["--request", "DELETE", storage_object_url(base_url, bucket, path)],
        api_key=secret_key,
        retries=retries,
        timeout=30,
    )
    if result.returncode != 0:
        error = curl_failure_details(result)
        if object_missing(error):
            return
        raise RuntimeError(error)


def purge_legacy_file(
    path: str,
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
) -> None:
    result = retry_curl(
        ["--request", "DELETE", purge_url(base_url, bucket, path)],
        api_key=secret_key,
        retries=retries,
        timeout=30,
    )
    if result.returncode != 0:
        error = curl_failure_details(result)
        if object_missing(error):
            return
        raise RuntimeError(error)


def verify_storage_object_absent(
    path: str,
    base_url: str,
    bucket: str,
    attempts: int = 13,
) -> None:
    last_error = "object is still publicly accessible"
    for attempt in range(attempts):
        result = run_curl([public_url(base_url, bucket, path)], timeout=30)
        if result.returncode != 0:
            error = curl_failure_details(result)
            if object_missing(error):
                return
            last_error = error
        if attempt + 1 < attempts:
            time.sleep(5)
    raise RuntimeError(last_error)


def cleanup_legacy_objects(
    targets: list[ArtifactTarget],
    base_url: str,
    bucket: str,
    secret_key: str,
    retries: int,
    workers: int,
) -> None:
    paths = [
        (target.code, path)
        for target in targets
        for path in target.legacy_paths
    ]
    delete_jobs = [
        (
            path,
            lambda p=path: delete_storage_object(
                p, base_url, bucket, secret_key, retries
            ),
        )
        for _, path in paths
    ]
    purge_jobs = [
        (
            path,
            lambda p=path: purge_legacy_file(
                p, base_url, bucket, secret_key, retries
            ),
        )
        for _, path in paths
    ]
    absence_jobs = [
        (
            path,
            lambda p=path: verify_storage_object_absent(p, base_url, bucket),
        )
        for _, path in paths
    ]
    run_parallel("Legacy delete", delete_jobs, workers)
    run_parallel("Legacy CDN purge", purge_jobs, workers)
    run_parallel("Legacy absence verification", absence_jobs, workers)


def run_parallel(label: str, jobs: list[tuple[str, object]], workers: int) -> None:
    if not jobs:
        return
    failures = []
    completed = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        pending = {executor.submit(job): name for name, job in jobs}
        for future in as_completed(pending):
            name = pending[future]
            try:
                future.result()
                completed += 1
                if completed % 50 == 0 or completed == len(jobs):
                    print(f"{label}: {completed}/{len(jobs)}")
            except Exception as error:  # noqa: BLE001 - aggregate remote failures
                failures.append((name, str(error)))
                print(f"[{label} FAILED] {name}: {error}", file=sys.stderr)
    if failures:
        raise RuntimeError(f"{label} failed for {len(failures)} of {len(jobs)} operations")


def main() -> int:
    arguments = parse_args()
    if arguments.workers < 1 or arguments.retries < 0:
        print("--workers must be >= 1 and --retries must be >= 0", file=sys.stderr)
        return 2
    if arguments.skip_database and arguments.delete_legacy:
        print(
            "--delete-legacy requires database URL updates; remove --skip-database.",
            file=sys.stderr,
        )
        return 2
    if arguments.cleanup_legacy_only and (arguments.skip_database or arguments.delete_legacy):
        print(
            "--cleanup-legacy-only cannot be combined with --skip-database or --delete-legacy.",
            file=sys.stderr,
        )
        return 2
    if arguments.allow_unmapped and (arguments.delete_legacy or arguments.cleanup_legacy_only):
        print("Legacy cleanup requires a complete artifact mapping.", file=sys.stderr)
        return 2

    base_url = clean_url(arguments.supabase_url)
    secret_key = (
        os.environ.get("SUPABASE_SECRET_KEY")
        or os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
        or ""
    ).strip()
    read_key = secret_key or os.environ.get("SUPABASE_ANON_KEY", "").strip()
    if not arguments.catalog_json and not read_key:
        print(
            "Set SUPABASE_SECRET_KEY/SUPABASE_SERVICE_ROLE_KEY, set "
            "SUPABASE_ANON_KEY, or pass --catalog-json.",
            file=sys.stderr,
        )
        return 2

    try:
        sources = collect_sources(arguments.source_dir.expanduser().resolve())
        overrides = load_asset_map(arguments.asset_map)
        rows = load_catalog(arguments.catalog_json, base_url, read_key)
        targets, unmapped = resolve_targets(
            rows,
            sources,
            overrides,
            base_url,
            arguments.bucket,
            arguments.retries,
            arguments.workers,
        )
    except (FileNotFoundError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        return 2

    print(
        f"Resolved {len(targets)}/{len(rows)} artifact rows from "
        f"{len(sources)} curated artwork/icon pairs."
    )
    for code, name, reason in unmapped:
        print(f"[UNMAPPED] {code} {name}: {reason}", file=sys.stderr)
    if unmapped and not arguments.allow_unmapped:
        print(
            "Refusing a partial migration. Provide --asset-map or pass --allow-unmapped.",
            file=sys.stderr,
        )
        return 1

    if arguments.dry_run:
        for target in targets:
            if arguments.cleanup_legacy_only:
                print(f"{target.code}: delete {', '.join(target.legacy_paths)}")
            else:
                print(
                    f"{target.code} {target.source.asset_id}: "
                    f"{target.image_path}, {target.icon_path}"
                )
        return 0

    if not secret_key:
        print(
            "Writing requires SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY in the shell.",
            file=sys.stderr,
        )
        return 2
    key_error = validate_admin_key(secret_key)
    if key_error:
        print(key_error, file=sys.stderr)
        return 2

    try:
        validate_admin_access(base_url, arguments.bucket, secret_key)
        verify_jobs = []
        for target in targets:
            for kind, source, path in (
                ("image", target.source.artwork, target.image_path),
                ("icon", target.source.icon, target.icon_path),
            ):
                label = f"{target.code}/{kind}"
                verify_jobs.append(
                    (
                        label,
                        lambda s=source, p=path: verify_file(
                            s, p, base_url, arguments.bucket
                        ),
                    )
                )

        if arguments.cleanup_legacy_only:
            verify_catalog(targets, base_url, arguments.bucket, secret_key)
            run_parallel("Verify canonical objects", verify_jobs, arguments.workers)
            cleanup_legacy_objects(
                targets,
                base_url,
                arguments.bucket,
                secret_key,
                arguments.retries,
                arguments.workers,
            )
            print(
                f"Deleted and verified legacy flat paths for {len(targets)} artifacts."
            )
            return 0

        upload_jobs = []
        purge_jobs = []
        for target in targets:
            for kind, source, path in (
                ("image", target.source.artwork, target.image_path),
                ("icon", target.source.icon, target.icon_path),
            ):
                label = f"{target.code}/{kind}"
                upload_jobs.append(
                    (
                        label,
                        lambda s=source, p=path: upload_file(
                            s,
                            p,
                            base_url,
                            arguments.bucket,
                            secret_key,
                            arguments.retries,
                        ),
                    )
                )
                purge_jobs.append(
                    (
                        label,
                        lambda p=path: purge_file(
                            p,
                            base_url,
                            arguments.bucket,
                            secret_key,
                            arguments.retries,
                        ),
                    )
                )

        run_parallel("Upload", upload_jobs, arguments.workers)
        run_parallel("CDN purge", purge_jobs, arguments.workers)
        if arguments.verify:
            run_parallel("Verify", verify_jobs, arguments.workers)
        if not arguments.skip_database:
            database_jobs = [
                (
                    target.code,
                    lambda t=target: update_catalog_row(
                        t,
                        base_url,
                        arguments.bucket,
                        secret_key,
                        arguments.retries,
                    ),
                )
                for target in targets
            ]
            run_parallel("Database update", database_jobs, arguments.workers)
            verify_catalog(targets, base_url, arguments.bucket, secret_key)

        if arguments.delete_legacy:
            cleanup_legacy_objects(
                targets,
                base_url,
                arguments.bucket,
                secret_key,
                arguments.retries,
                arguments.workers,
            )
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1

    print(
        f"Migrated {len(targets)} artifacts to "
        "artifacts/{artifact_code}/{image|icon}.png."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
