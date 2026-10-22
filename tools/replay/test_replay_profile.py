"""Safety checks for destructive fixture reset boundaries, using temporary worlds only."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('replay_profile', Path(__file__).parents[1] / 'replay-profile.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ResetSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = self.root / 'owned/.minecraft'
        self.game.mkdir(parents=True)
        MODULE.write_json(self.game / MODULE.MARKER,
                          dict(owner=MODULE.OWNER, game=str(self.game.resolve())))
        self.world = self.game / 'saves/mcopt-replay'
        self.world.mkdir(parents=True)
        self.personal = self.root / 'personal-world'
        self.personal.mkdir()
        (self.personal / 'level.dat').write_bytes(b'never modify')

    def test_reset_preserves_world_identity_and_fixture(self):
        (self.world / 'old').write_text('stale')
        before = self.world.stat()
        source_hash = MODULE.tree_digest(self.personal)
        MODULE.clear_children(self.world, self.game)
        MODULE.clone_world(self.personal, self.world)
        self.assertEqual(MODULE.tree_digest(self.world), source_hash)
        self.assertEqual(MODULE.tree_digest(self.personal), source_hash)
        self.assertEqual(self.world.stat().st_ino, before.st_ino)
        if hasattr(before, 'st_birthtime'):
            self.assertEqual(self.world.stat().st_birthtime, before.st_birthtime)

    def test_refuse_unmarked_game(self):
        (self.game / MODULE.MARKER).unlink()
        with self.assertRaises(FileNotFoundError):
            MODULE.clear_children(self.world, self.game)

    def test_refuse_wrong_owner_or_path(self):
        for owner, path in [('someone-else', self.game), (MODULE.OWNER, self.personal)]:
            MODULE.write_json(self.game / MODULE.MARKER, dict(owner=owner, game=str(path)))
            with self.assertRaises(ValueError):
                MODULE.clear_children(self.world, self.game)

    def test_refuse_normal_world_and_game_root(self):
        for path in (self.personal, self.game, self.game / 'saves'):
            with self.assertRaises(ValueError):
                MODULE.clear_children(path, self.game)
        self.assertEqual((self.personal / 'level.dat').read_bytes(), b'never modify')

    def test_refuse_symlinked_world_parent_and_settings(self):
        self.world.rmdir()
        (self.game / 'saves').rmdir()
        (self.game / 'saves').symlink_to(self.personal, target_is_directory=True)
        with self.assertRaises(ValueError):
            MODULE.clear_children(self.world, self.game)
        (self.game / 'saves').unlink()
        (self.game / 'options.txt').symlink_to(self.personal / 'level.dat')
        with self.assertRaises(ValueError):
            MODULE.read_owner(self.game)
        self.assertEqual((self.personal / 'level.dat').read_bytes(), b'never modify')

    def test_child_symlink_unlinked_without_following(self):
        (self.world / 'escape').symlink_to(self.personal, target_is_directory=True)
        MODULE.clear_children(self.world, self.game)
        self.assertEqual((self.personal / 'level.dat').read_bytes(), b'never modify')

    def test_refuse_symlinked_graphics_config(self):
        (self.game / 'config').mkdir()
        for relative in MODULE.EXTRA_CONFIGS:
            with self.subTest(config=relative):
                target = self.game / relative
                target.symlink_to(self.personal / 'level.dat')
                with self.assertRaises(ValueError):
                    MODULE.read_owner(self.game)
                target.unlink()
        self.assertEqual((self.personal / 'level.dat').read_bytes(), b'never modify')

    def test_unresolved_launch_stays_locked_regardless_of_age(self):
        output = self.root / 'output'
        MODULE.write_json(self.game / '.replay-active.json', dict(output=str(output), startedEpoch=0))
        with self.assertRaises(RuntimeError):
            MODULE.inactive(self.game)

    def test_invalid_route_rejected_before_world_reset(self):
        route = self.root / 'bad.json'
        plan = json.loads((Path(__file__).parent / 'smoke.json').read_text())
        plan['phases'][0]['yaw'] = float('nan')
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError):
            MODULE.validate_plan(route)

    def test_pan_endpoint_validated(self):
        route = self.root / 'pan.json'
        plan = json.loads((Path(__file__).parent / 'smoke.json').read_text())
        plan['phases'][0]['yawTo'] = 135
        MODULE.write_json(route, plan)
        self.assertEqual(MODULE.validate_plan(route)['phases'][0]['yawTo'], 135)
        plan['phases'][0]['yawTo'] = float('inf')
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError):
            MODULE.validate_plan(route)


if __name__ == '__main__':
    unittest.main()
