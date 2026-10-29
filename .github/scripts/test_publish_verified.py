import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-verified.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
checked, digest, validate = publisher.checked, publisher.digest, publisher.validate


class ReleaseValidationTest(unittest.TestCase):
    def test_native_gate_checks_both_jars_and_propagates_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            checkout = root / 'checkout'
            source = checkout / 'src/main/java/com/reactor/rust/codegen/PackagedNativeProvenanceVerifier.java'
            contract = checkout / 'scripts/native-provenance-gate.properties'
            for path in (source, contract):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            for name in ('main.jar', 'runtime.jar'):
                (root / name).write_text(name)
            artifact = dict(id='rust-java-rest', primary='main.jar',
                            attached=[dict(file='runtime.jar', classifier='core-runtime')])
            manifest = dict(artifacts=[artifact], files={name: digest(root / name)
                                                       for name in ('main.jar', 'runtime.jar')})
            with patch.object(publisher.subprocess, 'run') as run:
                publisher.verify_native_jars(root, checkout, manifest)
                run.assert_called_once_with(['java', str(source), str(contract), str(root / 'main.jar'),
                                             str(root / 'runtime.jar')], cwd=checkout, check=True)
            with patch.object(publisher.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'java')):
                with self.assertRaises(subprocess.CalledProcessError):
                    publisher.verify_native_jars(root, checkout, manifest)
            artifact['attached'] = []
            with patch.object(publisher.subprocess, 'run') as run:
                with self.assertRaises(ValueError):
                    publisher.verify_native_jars(root, checkout, manifest)
                run.assert_not_called()

    def test_deploys_sources_and_all_attached_classifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('a.pom', 'a.jar', 'a-sources.jar', 'a-codegen.jar', 'a-core-runtime.jar'):
                (root / name).write_text(name)
            artifact = dict(group='com.reactor', id='a', pom='a.pom', primary='a.jar', sources='a-sources.jar',
                            attached=[dict(file='a-codegen.jar', classifier='codegen'),
                                      dict(file='a-core-runtime.jar', classifier='core-runtime')])
            args = publisher.deploy_args(root, artifact, 'https://example.invalid', '1.0.0')
            self.assertIn('-Dclassifiers=codegen,core-runtime', args)
            self.assertIn('-Dtypes=jar,jar', args)
            self.assertIn(f'-Dsources={root / "a-sources.jar"}', args)
            self.assertIn(f'-Dfiles={root / "a-codegen.jar"},{root / "a-core-runtime.jar"}', args)

    def test_rejects_changed_or_unverified_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout = root / 'checkout'
            checkout.mkdir()
            (checkout / 'pom.xml').write_text('source')
            (root / 'artifact.pom').write_text('artifact')
            receipt = {'execution_environment': 'EmaTech', 'test_failures': 0,
                       'abis': {'rest': 29, 'dubbo': 7, 'spring_dubbo': 3, 'redis': 6, 'glowroot': 6}}
            manifest = {'repository': 'owner/repo', 'commit': 'abc', 'version': '1.0.0',
                        'files': {'artifact.pom': digest(root / 'artifact.pom')},
                        'source_files': {'pom.xml': digest(checkout / 'pom.xml')},
                        'artifacts': [{'version': '1.0.0', 'pom': 'artifact.pom', 'primary': 'artifact.pom',
                                       'downloads': [{'file': 'artifact.pom'}]}]}

            def check(m=manifest, r=receipt):
                (root / 'manifest.json').write_text(json.dumps(m))
                (root / 'release-receipt.json').write_text(json.dumps(r))
                return validate(root, checkout, 'owner/repo', 'abc', '1.0.0')

            self.assertEqual(check()['version'], '1.0.0')
            for field, value in [('test_failures', 1), ('execution_environment', 'local'), ('abis', {})]:
                changed = dict(receipt, **{field: value})
                with self.assertRaises(ValueError):
                    check(r=changed)
            changed = copy.deepcopy(manifest)
            changed['commit'] = 'wrong'
            with self.assertRaises(ValueError):
                check(m=changed)
            (checkout / 'pom.xml').write_text('changed')
            with self.assertRaises(ValueError):
                check()
            (checkout / 'pom.xml').write_text('source')
            (root / 'artifact.pom').write_text('changed')
            with self.assertRaises(ValueError):
                check()
            with self.assertRaises(ValueError):
                checked(root, '../outside')

    def test_rejects_empty_downloads_and_preserves_platform_pom_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'artifact.pom').write_text('platform POM')
            receipt = {'execution_environment': 'EmaTech', 'test_failures': 0,
                       'abis': {'rest': 29, 'dubbo': 7, 'spring_dubbo': 3, 'redis': 6, 'glowroot': 6}}
            artifact = {'id': 'rust-java-platform-parent', 'version': '1.0.0', 'pom': 'artifact.pom',
                        'primary': 'artifact.pom', 'downloads': [{'file': 'artifact.pom'}]}
            manifest = {'repository': 'owner/repo', 'commit': 'abc', 'version': '1.0.0',
                        'files': {'artifact.pom': digest(root / 'artifact.pom')},
                        'source_files': {}, 'artifacts': [artifact]}
            (root / 'release-receipt.json').write_text(json.dumps(receipt))
            (root / 'manifest.json').write_text(json.dumps(manifest))
            self.assertEqual(validate(root, root, 'owner/repo', 'abc', '1.0.0'), manifest)
            with patch.object(publisher.subprocess, 'run') as run:
                publisher.verify_native_jars(root, root, manifest)
                run.assert_not_called()
            artifact['downloads'] = []
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'downloads must not be empty'):
                validate(root, root, 'owner/repo', 'abc', '1.0.0')

    def test_rejects_primary_missing_from_downloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('artifact.pom', 'artifact.jar'):
                (root / name).write_text(name)
            receipt = {'execution_environment': 'EmaTech', 'test_failures': 0,
                       'abis': {'rest': 29, 'dubbo': 7, 'spring_dubbo': 3, 'redis': 6, 'glowroot': 6}}
            manifest = {'repository': 'owner/repo', 'commit': 'abc', 'version': '1.0.0',
                        'files': {name: digest(root / name) for name in ('artifact.pom', 'artifact.jar')},
                        'source_files': {}, 'artifacts': [{'id': 'rust-java-rest', 'version': '1.0.0',
                            'pom': 'artifact.pom', 'primary': 'artifact.jar',
                            'downloads': [{'file': 'artifact.pom'}]}]}
            (root / 'release-receipt.json').write_text(json.dumps(receipt))
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'Primary artifact is missing from downloads'):
                validate(root, root, 'owner/repo', 'abc', '1.0.0')


if __name__ == '__main__':
    unittest.main()
