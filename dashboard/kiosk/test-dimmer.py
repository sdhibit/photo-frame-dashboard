#!/usr/bin/env python3
"""Host checks for the local dimmer patch's schedule logic (DimPolicy.java).

Needs Python 3 and a JDK on PATH, no SDK/device/network. Covers time-window parsing, windows that
cross midnight and the normal/dim/night decision. It does not prove backlight writes, su, touch
delivery or Android's brightness clamping; those were checked on the frame.
"""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

KIOSK = Path(__file__).resolve().parent
POLICY = KIOSK / "src" / "com" / "wesbos" / "homehq" / "DimPolicy.java"

TEST = r"""
package com.wesbos.homehq;
public class DimPolicyTest {
  static int failures;
  static void check(boolean ok, String what) {
    System.out.println((ok ? "PASS " : "FAIL ") + what);
    if (!ok) failures++;
  }
  static int m(int h, int min) { return h * 60 + min; }
  static final long MIN = 60000L;
  public static void main(String[] a) {
    int[] w = DimPolicy.parseWindow("22:00-06:30");
    check(w != null && w[0] == m(22, 0) && w[1] == m(6, 30), "parses 22:00-06:30");
    check(DimPolicy.parseWindow(" 7:05 - 8:15 ") != null, "tolerates spaces and one-digit hours");
    check(DimPolicy.parseWindow("off") == null, "'off' means no night window");
    check(DimPolicy.parseWindow("") == null && DimPolicy.parseWindow(null) == null, "empty means no night window");
    check(DimPolicy.parseWindow("24:00-06:00") == null && DimPolicy.parseWindow("22:60-06:00") == null,
        "rejects out-of-range times");
    check(DimPolicy.parseWindow("22:00-22:00") == null, "rejects an empty window");
    check(DimPolicy.parseWindow("22-06") == null, "rejects times without minutes");

    DimPolicy p = new DimPolicy(true, 10 * MIN, 10, m(22, 0), m(6, 30), 1 * MIN, 0);
    check(p.inNight(m(22, 0)) && p.inNight(m(23, 59)) && p.inNight(m(0, 0)) && p.inNight(m(6, 29)),
        "night window crosses midnight");
    check(!p.inNight(m(6, 30)) && !p.inNight(m(12, 0)) && !p.inNight(m(21, 59)), "end is exclusive, day is not night");
    check(p.state(m(14, 0), 0) == DimPolicy.NORMAL, "day, just touched: normal");
    check(p.state(m(14, 0), 10 * MIN - 1) == DimPolicy.NORMAL, "day, idle just under 10 min: normal");
    check(p.state(m(14, 0), 10 * MIN) == DimPolicy.DIM, "day, idle 10 min: dim");
    check(p.state(m(23, 0), 30000) == DimPolicy.NORMAL, "night, idle 30 s: still normal");
    check(p.state(m(23, 0), 1 * MIN) == DimPolicy.NIGHT, "night, idle 1 min: night");
    check(p.state(m(3, 0), 60 * MIN) == DimPolicy.NIGHT, "after midnight, long idle: night");
    check(p.state(m(6, 30), 60 * MIN) == DimPolicy.DIM, "night ends at 06:30 into daytime dim");
    check(p.levelFor(DimPolicy.NIGHT) == 0 && p.levelFor(DimPolicy.DIM) == 10 && p.levelFor(DimPolicy.NORMAL) == -1,
        "levels: night 0, dim 10, normal -1");

    DimPolicy day = new DimPolicy(true, 0, 10, m(22, 0), m(6, 30), 1 * MIN, 0);
    check(day.state(m(14, 0), 999 * MIN) == DimPolicy.NORMAL, "dim_after 0 turns the daytime dim off");
    check(day.state(m(23, 0), 2 * MIN) == DimPolicy.NIGHT, "night still works without the daytime dim");

    DimPolicy noNight = new DimPolicy(true, 10 * MIN, 10, 0, 0, 1 * MIN, 0);
    check(noNight.state(m(23, 0), 60 * MIN) == DimPolicy.DIM, "no night window: only the daytime dim");

    DimPolicy lateNight = new DimPolicy(true, 5 * MIN, 30, m(22, 0), m(6, 0), 20 * MIN, 0);
    check(lateNight.state(m(23, 0), 6 * MIN) == DimPolicy.DIM, "night_after longer than dim_after: dims first");
    check(lateNight.state(m(23, 0), 20 * MIN) == DimPolicy.NIGHT, "then goes to night");

    DimPolicy sameDay = new DimPolicy(true, 10 * MIN, 10, m(1, 0), m(5, 0), 0, 0);
    check(sameDay.inNight(m(1, 0)) && !sameDay.inNight(m(5, 0)) && !sameDay.inNight(m(23, 0)),
        "window inside one day");
    check(sameDay.state(m(2, 0), 0) == DimPolicy.NIGHT, "night_after 0: dark as soon as the window starts");

    DimPolicy off = new DimPolicy(false, 10 * MIN, 10, m(22, 0), m(6, 30), 1 * MIN, 0);
    check(off.state(m(23, 0), 999 * MIN) == DimPolicy.NORMAL, "disabled: always normal");

    DimPolicy clamp = new DimPolicy(true, MIN, 999, m(22, 0), m(6, 0), MIN, -5);
    check(clamp.dimLevel == 255 && clamp.nightLevel == 0, "levels clamp to 0-255");

    String night = DimPolicy.pageScript(DimPolicy.NIGHT, true, "timer");
    check(night.endsWith("('night',true,'timer')"), "page script carries state, night flag and cause");
    String odd = DimPolicy.pageScript(DimPolicy.DIM, false, "x');alert(1)//");
    check(odd.endsWith("('dim',false,'other')") && !odd.contains("alert"), "unknown causes become 'other'");
    // Scripts for the JavaScript run (node), in the order a night and a wake would send them.
    System.out.println("SCRIPT " + DimPolicy.pageScript(DimPolicy.NORMAL, false, "load"));
    System.out.println("SCRIPT " + night);
    System.out.println("SCRIPT " + DimPolicy.pageScript(DimPolicy.NORMAL, true, "touch"));

    if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
    System.out.println("all dimmer policy checks passed");
  }
}
"""


def main():
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "src" / "com" / "wesbos" / "homehq"
        src.mkdir(parents=True)
        (src / "DimPolicy.java").write_text(POLICY.read_text(encoding="utf-8"), encoding="utf-8")
        (src / "DimPolicyTest.java").write_text(TEST, encoding="utf-8")
        out = tmp / "classes"
        subprocess.run(["javac", "-nowarn", "-d", str(out), str(src / "DimPolicy.java"),
                        str(src / "DimPolicyTest.java")], check=True)
        run = subprocess.run(["java", "-cp", str(out), "com.wesbos.homehq.DimPolicyTest"],
                             capture_output=True, text=True)
        scripts = [l[len("SCRIPT "):] for l in run.stdout.splitlines() if l.startswith("SCRIPT ")]
        print("\n".join(l for l in run.stdout.splitlines() if not l.startswith("SCRIPT ")))
        if run.returncode:
            raise SystemExit(run.returncode)
        check_page_scripts(scripts)


# Runs the generated scripts in node against a fake <html> element and window.
NODE = r"""
const scripts = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const attrs = {}, events = [];
globalThis.document = { documentElement: { setAttribute(k, v) { attrs[k] = String(v); } } };
globalThis.window = new EventTarget();
window.addEventListener('homehq:backlight', e => events.push(e.detail));
for (const s of scripts) (0, eval)(s);
console.log(JSON.stringify({ attrs, events }));
"""


def check_page_scripts(scripts):
    if not shutil.which("node"):
        print("SKIP page script run: node not on PATH")
        return
    out = subprocess.run(["node", "-e", NODE], input=json.dumps(scripts), capture_output=True,
                         text=True, check=True)
    result = json.loads(out.stdout)
    attrs, events = result["attrs"], result["events"]
    checks = [
        (attrs == {"data-homehq-backlight": "normal", "data-homehq-night": "1"},
         "page ends with data-homehq-backlight=normal, data-homehq-night=1 after a night wake"),
        ([e["state"] for e in events] == ["normal", "night", "normal"], "one event per script, in order"),
        ([e["cause"] for e in events] == ["load", "timer", "touch"], "events carry the cause"),
        (events[1]["night"] is True and events[0]["night"] is False, "events carry the night flag as a boolean"),
    ]
    failed = 0
    for ok, what in checks:
        print(("PASS " if ok else "FAIL ") + what)
        failed += not ok
    if failed:
        raise SystemExit(f"{failed} page script checks FAILED")
    print("all page script checks passed")


if __name__ == "__main__":
    main()
