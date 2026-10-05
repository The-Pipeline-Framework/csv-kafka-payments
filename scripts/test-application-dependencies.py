#!/usr/bin/env python3
"""Keep the application on one product BOM, with an explicit plugin version."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ApplicationDependenciesTest(unittest.TestCase):
    def test_bom_and_plugin_are_the_only_framework_version_choices(self):
        root = ET.parse(ROOT / "pom.xml").getroot()
        properties = root.find("m:properties", NS)
        self.assertEqual(1, len(properties.findall("m:pipelineframework.bom.version", NS)))
        self.assertEqual(1, len(properties.findall("m:tpf.release.maven-plugin.version", NS)))
        for name in ("compiler", "runtime", "connectors"):
            self.assertIsNone(properties.find(f"m:pipelineframework.{name}.version", NS))
        bom = root.find("m:dependencyManagement/m:dependencies/m:dependency[m:artifactId='pipelineframework-bom']", NS)
        self.assertEqual("${pipelineframework.bom.version}", bom.findtext("m:version", namespaces=NS))
        self.assertEqual("import", bom.findtext("m:scope", namespaces=NS))
        self.assertEqual("pom", bom.findtext("m:type", namespaces=NS))
        self.assertEqual("true", root.findtext(".//m:annotationProcessorPathsUseDepMgmt", namespaces=NS))

        pending = [ROOT / "pom.xml"]
        plugin_count = 0
        while pending:
            pom = pending.pop()
            project = ET.parse(pom).getroot()
            pending.extend(pom.parent / module.text / "pom.xml"
                           for module in project.findall("m:modules/m:module", NS))
            for entry in project.findall(".//m:dependency", NS) + project.findall(".//m:annotationProcessorPaths/m:path", NS):
                if entry.findtext("m:groupId", namespaces=NS) == "org.pipelineframework":
                    artifact = entry.findtext("m:artifactId", namespaces=NS)
                    if artifact != "pipelineframework-bom":
                        self.assertIsNone(entry.find("m:version", NS), f"{pom}: {artifact} bypasses BOM")
            for plugin in project.findall(".//m:plugin", NS):
                if plugin.findtext("m:artifactId", namespaces=NS) == "pipelineframework-release-maven-plugin":
                    plugin_count += 1
                    self.assertEqual("${tpf.release.maven-plugin.version}", plugin.findtext("m:version", namespaces=NS))
        self.assertEqual(2, plugin_count)


if __name__ == "__main__":
    unittest.main()
