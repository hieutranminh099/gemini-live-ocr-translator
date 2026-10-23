"""Offline checks for diagnostic preservation; never starts ADB or calls an AI service."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import verify_api_pipeline as verify


class ReportTest(unittest.TestCase):
    def test_partial_success_and_failure_are_saved_and_client_closed(self):
        bench = Mock()
        bench.args = argparse.Namespace(roi=[233, 496, 809, 538])
        bench.status.return_value = dict(settings=dict(provider="api", personal_cli_enabled=False),
                                         api_configured=True, translation_available=True, version="test")
        bench.events.return_value = [{"event": "actual_overlay_evidence"}]
        bench.run.side_effect = RuntimeError("Diagnostic unavailable in offline test")

        def sample(_bench, source, index, row):
            row.update(source=source, source_to_overlay_ms=1217 + index, expected_source_hash=verify.source_hash(source))
            if index:
                row.update(observed_source_hash="0a13e9e6b8909139", exact_source_match=False)
                raise AssertionError("Exact source mismatch")
            row["status"] = "passed"

        with tempfile.TemporaryDirectory(dir=verify.ROOT / "build") as directory, patch.object(verify, "verify_sample", side_effect=sample):
            report = Path(directory) / "partial.json"
            with self.assertRaisesRegex(AssertionError, "Exact source mismatch"):
                verify.execute(bench, report, Path(directory) / "image.png")
            saved = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual("failed", saved["status"])
        self.assertEqual(["passed", "failed"], [row["status"] for row in saved["samples"]])
        self.assertEqual(1, saved["passed_samples"])
        self.assertEqual(1217, saved["descriptive_e2e_ms"]["max_ms"])
        self.assertEqual("0a13e9e6b8909139", saved["samples"][1]["observed_source_hash"])
        self.assertEqual(bench.events.return_value, saved["events"])
        self.assertIn("screenshot", saved["diagnostic_errors"])
        bench.client.close.assert_called_once()

    def test_exact_ocr_mismatch_fails_before_cache_readback(self):
        bench = Mock()
        bench.args = argparse.Namespace(timeout=50)
        bench.status.side_effect = [dict(usage=dict(requests=1)), dict(usage=dict(requests=2))]
        source = "请把窗户关上。"
        bench.scene.return_value = dict(at_ms=1000, source_hash=verify.source_hash(source))
        overlay = dict(event="overlay_drawn", at_ms=1923, request_id="1", source_hash="0a13e9e6b8909139")
        bench.wait.side_effect = [overlay, dict(event="result_received", at_ms=1900, request_id="1",
                                               server_timings_ms=dict(provider=657))]
        row = {}
        with self.assertRaisesRegex(AssertionError, "differs from the exact native source"):
            verify.verify_sample(bench, source, 0, row)
        self.assertFalse(row["exact_source_match"])
        self.assertEqual(923, row["source_to_overlay_ms"])
        self.assertEqual(1, row["fresh_ai_requests"])
        self.assertEqual(verify.source_hash("靖把窗戶关上。"), row["observed_source_hash"])
        bench.client.post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
