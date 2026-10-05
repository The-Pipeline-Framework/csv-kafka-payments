"""The image provenance version follows Maven's selected runtime, not a property."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest


RESOLVER = Path(__file__).resolve().parents[1] / "resolve-framework-version.sh"


class FrameworkVersionTest(unittest.TestCase):
    def resolve(self, dependencies, managed=""):
        with tempfile.TemporaryDirectory(prefix="csv version ") as directory:
            root = Path(directory)
            effective = root / "effective.xml"
            effective.write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f'<dependencies>{dependencies}</dependencies>'
                f'<dependencyManagement><dependencies>{managed}</dependencies></dependencyManagement>'
                '</project>'
            )
            maven = root / "mvnw"
            maven.write_text(
                '#!/usr/bin/env bash\nset -eu\n'
                'test -z "${MAVEN_ARGS:-}"\n'
                '[[ " $* " == *" -N "* ]]\n'
                'for arg in "$@"; do\n'
                'case "$arg" in -Doutput=*) cp "$FIXTURE" "${arg#-Doutput=}";; esac\n'
                'done\nprintf "Maven banner\\n"\n'
            )
            maven.chmod(0o755)
            return subprocess.run(
                ['bash', str(RESOLVER), str(maven), str(root / 'pom.xml'), str(root / '.m2/repository')],
                capture_output=True, text=True,
                env={**os.environ, 'FIXTURE': str(effective), 'MAVEN_ARGS': '-V'},
            )

    @staticmethod
    def dependency(version):
        return ('<dependency><groupId>org.pipelineframework</groupId>'
                '<artifactId>pipelineframework</artifactId>'
                f'<version>{version}</version></dependency>')

    def test_bom_without_runtime_property(self):
        result = self.resolve('', self.dependency('26.10.1-SNAPSHOT'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('26.10.1-SNAPSHOT\n', result.stdout)

    def test_direct_dependency_overrides_management(self):
        result = self.resolve(self.dependency('26.10.1-pr.12.abcdef123456'), self.dependency('26.9.4'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('26.10.1-pr.12.abcdef123456\n', result.stdout)

    def test_missing_dependency_fails_clearly(self):
        result = self.resolve('')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Effective POM does not select', result.stderr)

    def test_unresolved_and_conflicting_versions_fail(self):
        for dependencies in (self.dependency('${runtime.version}'), self.dependency(''),
                             self.dependency('26.9.4') + self.dependency('26.10.1-SNAPSHOT')):
            with self.subTest(dependencies=dependencies):
                result = self.resolve(dependencies)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('one concrete', result.stderr)


if __name__ == '__main__':
    unittest.main()
