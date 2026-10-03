#!/usr/bin/env python3
"""Host repro: a second configuration change after a delivered state item yields no state item.

    repro_stale_view_state.py --mimir /path/to/mimir --root /abs/installed/root

Exit 0 means the view announced the second change, exit 1 means it stayed silent.
"""

import argparse
import queue
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import transport  # noqa: E402
from bridge_client import Bridge  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mimir", required=True, type=Path)
    parser.add_argument("--root", required=True, type=Path)
    args = parser.parse_args()
    fixture = transport.FIXTURE = transport.Fixture(args.mimir, HERE.parent / "out" / "sh.roboco.bridge-0.1.0", args.root, True)
    fixture.start_provider()
    try:
        cwd = fixture.workdir("repro-view-state")
        bridge = Bridge(fixture.mimir, fixture.env, cwd)
        bridge.initialize()
        session = bridge.request("session.create", {"cwd": str(cwd), "configuration": transport.FAKE})["session"]
        bridge.request("session.open_view", {"session": session})
        modes = []
        for mode in ("plan", "build"):
            bridge.request("session.configure", {"session": session, "change": {"mode": mode}})
            try:
                while True:
                    item = transport.view_item(bridge.notification(timeout=5))
                    if transport.kind(item) == "state":
                        modes.append(item["state"]["configuration"]["mode"])
                        break
            except queue.Empty:
                modes.append(None)
        print({"announced_after_each_change": modes, "session_state": bridge.request("session.state", {"session": session})["configuration"]["mode"]})
        return 0 if modes == ["plan", "build"] else 1
    finally:
        bridge.kill()
        fixture.provider.stop()


if __name__ == "__main__":
    sys.exit(main())
