#!/usr/bin/env python3
import json
import pathlib
import subprocess
import sys

event = json.loads(pathlib.Path(sys.argv[1]).read_text())
payload = {"event_type": "tpf-candidate-v1", "client_payload": event}
subprocess.run(
    ["gh", "api", "repos/The-Pipeline-Framework/pipelineframework/dispatches", "--input", "-"],
    input=json.dumps(payload),
    text=True,
    check=True,
)
