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

    def test_mutation_fixture_rejects_truncated_or_nonboolean_plan(self):
        route = self.root / 'edits.json'
        plan = json.loads((Path(__file__).parent / 'smoke.json').read_text())
        plan['terrainChanges'] = True
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError):
            MODULE.validate_plan(route)
        plan['phases'][0]['seconds'] = 60
        MODULE.write_json(route, plan)
        self.assertTrue(MODULE.validate_plan(route)['terrainChanges'])
        plan['terrainChanges'] = 'true'
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError):
            MODULE.validate_plan(route)

    def test_missing_or_incomplete_edit_work_is_not_comparable(self):
        plan = {'terrainChanges': True}
        self.assertFalse(MODULE.workload_valid(plan, {}))
        work = dict(valid=True, expectedBatches=80, completedBatches=80, rockChanges=320,
                    lightChanges=80, fixtureMode='creative', heldItems='diamond_pickaxe/torch',
                    completedAtSeconds=49.55)
        self.assertTrue(MODULE.workload_valid(plan, {'terrainChanges': work}))
        for field, value in [('completedBatches', 79), ('rockChanges', 319),
                             ('completedAtSeconds', 52), ('fixtureMode', 'spectator'), ('valid', False)]:
            with self.subTest(field=field):
                self.assertFalse(MODULE.workload_valid(plan, {'terrainChanges': dict(work, **{field: value})}))

    def test_first_use_and_effects_require_actual_work(self):
        self.assertFalse(MODULE.workload_valid({'firstUseTracing': True}, {}))
        self.assertTrue(MODULE.workload_valid({'firstUseTracing': True}, {'setup': {'firstUseTracing': True}}))
        effects = dict(valid=True, applied=14, commands=22, failures=0, brokenBlocks=2)
        self.assertFalse(MODULE.workload_valid({'gameplayEffects': True}, {}))
        self.assertTrue(MODULE.workload_valid({'gameplayEffects': True}, {'gameplayEffects': effects}))
        self.assertFalse(MODULE.workload_valid({'gameplayEffects': True}, {'gameplayEffects': dict(effects, failures=1)}))

    def test_falling_star_reload_comparison_requires_the_actual_paths(self):
        plan = dict(gameplayEffects=True, fallingBlocks=True, starTransition=True, reloadBeforeWarmup=True)
        effects = dict(valid=True, applied=14, commands=27, failures=0, brokenBlocks=2, fallingBlocks=True, landedBlocks=2)
        report = dict(gameplayEffects=effects, setup=dict(resourceReloadCompleted=True),
                      starPipeline=dict(draws=120, firstMeasuredSeconds=23.1, runtimeGateOpened=True))
        self.assertTrue(MODULE.workload_valid(plan, report))
        self.assertFalse(MODULE.workload_valid(plan, dict(report, setup={})))
        self.assertFalse(MODULE.workload_valid(plan, dict(report, starPipeline=dict(draws=120, firstMeasuredSeconds=23.1))))
        self.assertFalse(MODULE.workload_valid(plan, dict(report, gameplayEffects=dict(effects, landedBlocks=1))))
        self.assertFalse(MODULE.workload_valid(plan, dict(report, gameplayEffects=dict(effects, commands=22))))
        for star in (dict(draws=0, firstMeasuredSeconds=-1), dict(draws=10, firstMeasuredSeconds=-18),
                     dict(draws=10, firstMeasuredSeconds=40)):
            self.assertFalse(MODULE.workload_valid(plan, dict(report, starPipeline=star)))

    def test_optional_effect_flags_rejected_before_reset(self):
        route = self.root / 'effects.json'
        plan = json.loads((Path(__file__).parent / 'smoke.json').read_text())
        plan['fallingBlocks'] = True
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError): MODULE.validate_plan(route)
        plan.pop('fallingBlocks'); plan['reloadBeforeWarmup'] = 'true'
        MODULE.write_json(route, plan)
        with self.assertRaises(ValueError): MODULE.validate_plan(route)

    def test_natural_caves_preserves_the_real_scene(self):
        route = self.root / 'natural.json'
        plan = json.loads((Path(__file__).parent / 'smoke.json').read_text())
        plan['naturalCaves'] = True
        MODULE.write_json(route, plan)
        self.assertTrue(MODULE.validate_plan(route)['naturalCaves'])
        self.assertFalse(MODULE.workload_valid(plan, {}))
        self.assertTrue(MODULE.workload_valid(plan, {'setup': {'naturalCaves': True}}))
        for flag in ('terrainChanges', 'compatFixture', 'schematicFixture'):
            MODULE.write_json(route, dict(plan, **{flag: True}))
            with self.assertRaises(ValueError): MODULE.validate_plan(route)
        MODULE.write_json(route, dict(plan, naturalCaves='true'))
        with self.assertRaises(ValueError): MODULE.validate_plan(route)

    def test_startup_properties_are_explicit_bounded_boolean_controls(self):
        cave = ['mcopt.lod.captureHash', 'mcopt.preload.caves', 'mcopt.preload.caveTextures', 'mcopt.metal.recoverDrawables']
        self.assertEqual(MODULE.startup_properties([key+'=false' for key in cave]), dict.fromkeys(cave, 'false'))
        self.assertEqual(MODULE.startup_properties(['mcopt.preload.fallingBlocks=false', 'atmosphericsfixes.starPreload=true']),
                         {'mcopt.preload.fallingBlocks': 'false', 'atmosphericsfixes.starPreload': 'true'})
        for values in (['user.dir=/tmp/elsewhere'], ['atmosphericsfixes.starPreload=false -Xmx8G'],
                       ['mcopt.preload.fallingBlocks=1'], ['mcopt.preload.fallingBlocks=true', 'mcopt.preload.fallingBlocks=false']):
            with self.assertRaises(ValueError): MODULE.startup_properties(values)


if __name__ == '__main__':
    unittest.main()
