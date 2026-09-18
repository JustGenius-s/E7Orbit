import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("upload-artifact-images.py")
SPEC = importlib.util.spec_from_file_location("upload_artifact_images", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class LegacyStoragePathTest(unittest.TestCase):
    def setUp(self):
        self.base_url = "https://example.supabase.co"
        self.bucket = "Epic7"

    def paths(self, url):
        return MODULE.legacy_storage_paths(url, "ef101", self.base_url, self.bucket)

    def expected_paths(self):
        return (
            "artifacts/ef101.png",
            "artifacts/ef101.webp",
            "artifacts/ef101.jpg",
            "artifacts/ef101.jpeg",
        )

    def test_includes_every_flat_extension_before_migration(self):
        url = (
            f"{self.base_url}/storage/v1/object/public/"
            f"{self.bucket}/artifacts/ef101.webp"
        )
        self.assertEqual(
            self.paths(url),
            ("artifacts/ef101.webp", *self.expected_paths()[:1], *self.expected_paths()[2:]),
        )

    def test_includes_every_flat_extension_after_migration(self):
        url = (
            f"{self.base_url}/storage/v1/object/public/"
            f"{self.bucket}/artifacts/ef101/image.png"
        )
        self.assertEqual(self.paths(url), self.expected_paths())

    def test_includes_every_flat_extension_when_old_url_was_empty(self):
        self.assertEqual(self.paths(None), self.expected_paths())


class MissingStorageObjectTest(unittest.TestCase):
    def test_detects_supabase_no_such_key_response(self):
        result = subprocess.CompletedProcess(
            [],
            22,
            stdout=b'{"statusCode":"404","code":"NoSuchKey"}',
            stderr=b"curl: (22) The requested URL returned error: 400",
        )

        self.assertTrue(MODULE.object_missing(MODULE.curl_failure_details(result)))


if __name__ == "__main__":
    unittest.main()
