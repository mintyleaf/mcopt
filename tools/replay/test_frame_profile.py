"""Frame profiling must not enable VM-wide inventory walks."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('frame_profile', Path(__file__).parents[1] / 'frame-profile.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProfileSafety(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
