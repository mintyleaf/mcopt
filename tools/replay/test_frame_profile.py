"""Frame profiling must not enable VM-wide inventory walks."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
import shutil
import subprocess

SPEC = importlib.util.spec_from_file_location('frame_profile', Path(__file__).parents[1] / 'frame-profile.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProfileSafety(unittest.TestCase):
    def java_home(self):
        java = shutil.which('java')
        if not java:
            self.skipTest('JDK needed for its stock JFR configuration')
        output = subprocess.run([java, '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
        for line in output.stderr.splitlines():
            if line.strip().startswith('java.home = '):
                return line.split(' = ', 1)[1]
        self.fail('java.home missing')

    def test_disable_existing_inventory_controls_and_missing_events(self):
        tree = ET.ElementTree(ET.fromstring('''<configuration><event name="jdk.ClassLoaderStatistics">
          <setting name="enabled" control="inventory">true</setting>
          <setting name="period">everyChunk</setting></event></configuration>'''))
        MODULE.omit_vm_inventory(tree)
        for name in MODULE.VM_INVENTORY_EVENTS:
            enabled = [s for e in tree.getroot().findall('event') if e.get('name') == name
                       for s in e.findall('setting') if s.get('name') == 'enabled']
            self.assertTrue(enabled)
            self.assertTrue(all(s.text == 'false' and 'control' not in s.attrib for s in enabled))
        before = ET.tostring(tree.getroot())
        MODULE.omit_vm_inventory(tree)
        self.assertEqual(before, ET.tostring(tree.getroot()))

    def test_gpu_only_capture_also_excludes_inventory(self):
        tree = MODULE.profile_configuration('', True, False)
        settings = {(e.get('name'), s.get('name')): s.text for e in tree.getroot().findall('event')
                    for s in e.findall('setting')}
        self.assertEqual(settings['jdk.ClassLoaderStatistics', 'enabled'], 'false')
        self.assertEqual(settings['jdk.GCPhasePause', 'enabled'], 'true')
        self.assertEqual(settings['mcopt.MetalOperation', 'enabled'], 'true')

    def test_first_use_is_opt_in_and_does_not_restore_inventory(self):
        java_home = self.java_home()
        for first_use in (False, True):
            tree = MODULE.profile_configuration(java_home, False, False, first_use)
            settings = {(e.get('name'), s.get('name')): s for e in tree.getroot().findall('event')
                        for s in e.findall('setting')}
            for name in MODULE.VM_INVENTORY_EVENTS:
                self.assertEqual(settings[name, 'enabled'].text, 'false')
            for name in ('jdk.ClassLoad', 'mcopt.GameplayWork', 'mcopt.GameplayAction'):
                self.assertEqual(settings[name, 'enabled'].text, str(first_use).lower())
                self.assertNotIn('control', settings[name, 'enabled'].attrib)
            if first_use:
                self.assertEqual(settings['jdk.ClassLoad', 'threshold'].text, '2 ms')
                self.assertEqual(settings['jdk.ExecutionSample', 'period'].text, '20 ms')
                self.assertNotIn('control', settings['jdk.ExecutionSample', 'period'].attrib)
                self.assertEqual(settings['jdk.ObjectAllocationSample', 'throttle'].text, '50/s')

    def test_first_use_rejects_gpu_only(self):
        with self.assertRaises(ValueError):
            MODULE.profile_configuration('', True, False, True)

    def test_first_use_analysis_keeps_resource_identity_and_vm_stops(self):
        def event(kind, **values):
            return {'type': kind, 'values': {'startTime': '2026-10-25T00:00:00Z',
                                           'eventThread': {'javaName': 'Render thread'}, **values}}
        result = MODULE.parse_events([
            event('mcopt.GameplayWork', duration='PT0.003S', operation='textureLoad',
                  subject='minecraft:textures/entity/check.png', implementation='SimpleTexture'),
            event('mcopt.GameplayAction', operation='attackOrBreak'),
            event('jdk.ClassLoad', duration='PT0.004S', loadedClass={'name': 'example/Helper'}),
            event('jdk.ExecuteVMOperation', duration='PT0.002S', operation='JFRSafepointWrite', safepoint=True),
        ])
        self.assertEqual(result['gameplayWork'][0]['subject'], 'minecraft:textures/entity/check.png')
        self.assertEqual(result['gameplayWork'][0]['durationMs'], 3)
        self.assertEqual(result['gameplayActions'][0]['operation'], 'attackOrBreak')
        self.assertEqual(result['slowClassLoads'][0]['class'], 'example/Helper')
        self.assertTrue(result['vmOperations'][0]['safepoint'])


if __name__ == '__main__':
    unittest.main()
