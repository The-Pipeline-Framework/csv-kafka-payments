#!/usr/bin/env python3
"""Run Maven with operator package credentials in child memory, never files/logs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape


def main():
    root = Path(__file__).resolve().parents[1]
    token = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
    user = subprocess.run(["gh", "api", "user", "--jq", ".login"], capture_output=True, text=True)
    if token.returncode or user.returncode or not token.stdout.strip() or not user.stdout.strip():
        sys.exit("GitHub package credentials are unavailable; authenticate the operator first.")
    environment = os.environ.copy()
    environment["TPF_PROOF_PACKAGES_TOKEN"] = token.stdout.strip()
    environment["TPF_PROOF_PACKAGES_USERNAME"] = user.stdout.strip()
    # Maven's standard server binding supplies auth; no credential appears in arguments.
    if sys.argv[1:2] == ["--tpf"]:
        # The current public resolver does not interpolate env expressions in settings.
        # Materialize a short-lived standard credential file in an owner-only directory.
        # Neither the checked-in settings nor descriptor contains credential values.
        with tempfile.TemporaryDirectory(prefix="tpf-proof-credentials-") as temporary:
            settings = Path(temporary) / "settings.xml"
            template = (root / ".mvn/cloud-proof-settings.xml").read_text()
            rendered = template.replace("${env.TPF_PROOF_PACKAGES_USERNAME}", escape(user.stdout.strip()))
            rendered = rendered.replace("${env.TPF_PROOF_PACKAGES_TOKEN}", escape(token.stdout.strip()))
            descriptor = os.open(settings, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as output:
                output.write(rendered)
            config = Path(temporary) / "verification.yaml"
            config.write_text((root / "cloud-proof-verify.yaml").read_text().replace(
                "${TPF_PROOF_ROOT}", str(root)).replace(
                str(root / ".mvn/cloud-proof-settings.xml"), str(settings)))
            command = [str(root / ".m2/proof-cli/tpf-26.10.1-SNAPSHOT-osx-aarch_64/bin/tpf"),
                       *sys.argv[2:], "--config", str(config)]
            return subprocess.run(command, cwd=root, env=environment).returncode
    else:
        command = [str(root / "mvnw"), "-s", str(root / ".mvn/cloud-proof-settings.xml"),
                   "-Dmaven.repo.local=" + str(root / ".m2/repository"), *sys.argv[1:]]
    return subprocess.run(command, cwd=root, env=environment).returncode


if __name__ == "__main__":
    sys.exit(main())
