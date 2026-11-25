"""Regression tests for staged atlas validation and promotion recovery."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image

import build_nyc_map as atlas


def write_atlas(path):
    path.mkdir(parents=True)
    metadata = {
        'stations': {'x': ['Station', 0, 0, '']},
        'routes': {'1': [['shape', 'Terminal', ['xN', 'xS']]]},
        'shapes': {'shape': [0, 0, 1, 1]},
    }
    (path / 'network.json').write_text(json.dumps(metadata))
    for col in range(16):
        for row in range(16):
            Image.new('RGB', (512, 512), (250, 248, 238)).save(path / f'8-{col}-{row}.png')
    for col in range(-4, 4):
        for row in range(-3, 5):
            Image.new('RGB', (512, 512), (250, 248, 238)).save(path / f'64-{col}-{row}.png')
    files = {
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in path.iterdir()
    }
    manifest = {
        'format': 1, 'gtfsVersion': 'fixture', 'gtfsSha256': '0' * 64,
        'inputs': {}, 'files': files,
    }
    (path / 'manifest.json').write_text(json.dumps(manifest))
    return metadata


class AtlasGenerationTest(unittest.TestCase):
    def test_bundled_historical_atlas_matches_recorded_digests(self):
        root = Path(__file__).resolve().parents[1]
        bundled = root / 'android/app/src/main/assets/nyc'
        metadata = json.loads((bundled / 'network.json').read_text())
        atlas.validate_atlas(bundled, metadata)

    def test_missing_tile_fails_before_promotion(self):
        with tempfile.TemporaryDirectory() as temp:
            staging = Path(temp) / 'staging'
            metadata = write_atlas(staging)
            (staging / '8-5-6.png').unlink()
            with self.assertRaisesRegex(ValueError, 'tile set mismatch'):
                atlas.validate_atlas(staging, metadata)

    def test_interrupted_promotion_restores_the_previous_atlas(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / 'nyc'
            metadata = write_atlas(output)
            backup = root / '.nyc-atlas-previous-1'
            output.rename(backup)  # Simulate process death between the two atomic renames.
            self.assertFalse(output.exists())
            atlas.recover_previous(output, root)
            self.assertTrue(output.is_dir())
            atlas.validate_atlas(output, metadata)
            self.assertFalse(backup.exists())

    def test_cleanup_removes_only_reserved_interrupted_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            abandoned = root / '.nyc-atlas-staging-interrupted'
            unrelated = root / 'atlas-staging-not-owned'
            abandoned.mkdir()
            unrelated.mkdir()
            atlas.cleanup_stale_staging(root)
            self.assertFalse(abandoned.exists())
            self.assertTrue(unrelated.exists())


if __name__ == '__main__':
    unittest.main()
