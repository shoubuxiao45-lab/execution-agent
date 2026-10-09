"""Host-driven scenarios. Only the project-owned emulator and validation package are accepted."""
import json
import argparse
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--apk", required=True, help="Path to app-validation.apk")
parser.add_argument("--out", required=True, help="Evidence directory outside source archives")
parser.add_argument("--scenario", choices=("all", "extra"), default="all", help="extra reruns Doze, inexact registration and offline UI")
args = parser.parse_args()
ROOT = Path(args.out).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
ADB = args.adb
APK = str(Path(args.apk).resolve())
SERIAL = args.serial
PACKAGE = "com.executionagent.app.validation"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
REPORT = ROOT / "phase8-runtime-matrix.json"
results = json.loads(REPORT.read_text(encoding="utf-8")) if REPORT.exists() else {}

def adb(*arguments, timeout=60):
    result = subprocess.run([ADB, "-s", SERIAL, *arguments], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    output = result.stdout.decode("utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(output)
    return output

def record(name, value):
    results[name] = value
    REPORT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{name}: {json.dumps(value, ensure_ascii=False)}", flush=True)

def fixture(method, delay=30):
    output = adb("shell", "am", "instrument", "-w", "-e", "class",
                 f"com.executionagent.app.Phase8RecoveryFixture#{method}",
                 "-e", "deadlineSeconds", str(delay), RUNNER)
    (ROOT / f"phase8-fixture-{method}-latest.log").write_text(output, encoding="utf-8")
    if "OK (1 test)" not in output:
        raise RuntimeError(output)

def launch_background():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.executionagent.app.MainActivity")
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")

def notification():
    output = adb("shell", "dumpsys", "notification")
    return [line.strip() for line in output.splitlines() if "NotificationRecord(" in line and f"pkg={PACKAGE} " in line]

def delivered(name, timeout=150):
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        rows = notification()
        if rows:
            snapshot = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/reminder_runtime.xml")
            fixture("probe")
            record(name, {"passed": True, "notification": rows, "waitSeconds": round(time.monotonic() - started, 1), "runtime": snapshot, "persistedTaskSettingsAndEvent": True})
            fixture("cleanup")
            return True
        time.sleep(2)
    record(name, {"passed": False, "reason": "No notification within bounded observation window", "waitSeconds": timeout,
                  "runtime": adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/reminder_runtime.xml")})
    fixture("cleanup")
    return False

if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
    raise RuntimeError("A project-owned emulator is required")
if "ExecutionAgentPhase8" not in adb("emu", "avd", "name"):
    raise RuntimeError("Unexpected AVD")
record("device", {"avd": "ExecutionAgentPhase8", "android": adb("shell", "getprop", "ro.build.version.release").strip(), "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip()})
adb("shell", "cmd", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
previous_doze = {mode: adb("shell", "dumpsys", "deviceidle", "enabled", mode).strip() == "1" for mode in ("deep", "light")}
try:
    if args.scenario == "all":
        fixture("seed", 30)
        launch_background()
        delivered("backgroundExact")

        fixture("seed", 30)
        launch_background()
        output = adb("install", "-r", APK)
        if "Success" not in output:
            raise RuntimeError(output)
        delivered("packageReplacementRecovery")

        fixture("seed", 60)
        launch_background()
        adb("reboot")
        adb("wait-for-device", timeout=120)
        boot_started = time.monotonic()
        while adb("shell", "getprop", "sys.boot_completed").strip() != "1":
            if time.monotonic() - boot_started > 120:
                raise RuntimeError("Emulator boot timeout")
            time.sleep(2)
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        delivered("bootRecovery")

        fixture("seed", 20)
        launch_background()
        adb("shell", "am", "force-stop", PACKAGE)
        time.sleep(25)
        stopped_has_notification = bool(notification())
        record("forceStopBlocksReminder", {"passed": not stopped_has_notification})
        launch_background()
        delivered("reopenAfterForceStop")

    fixture("seed", 30)
    launch_background()
    adb("shell", "dumpsys", "battery", "unplug")
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    adb("shell", "dumpsys", "deviceidle", "enable", "all")
    entry = adb("shell", "dumpsys", "deviceidle", "force-idle").strip()
    state = adb("shell", "dumpsys", "deviceidle", "get", "deep").strip()
    record("dozeEntry", {"command": entry, "state": state, "enabledBefore": previous_doze})
    if state != "IDLE":
        raise RuntimeError("The emulator did not enter deep Doze; no Doze result may be claimed")
    delivered("dozeExact", timeout=150)
    adb("shell", "dumpsys", "deviceidle", "unforce")
    adb("shell", "dumpsys", "battery", "reset")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")

    adb("shell", "cmd", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "ignore")
    fixture("seed", 30)
    launch_background()
    snapshot = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/reminder_runtime.xml")
    record("inexactFallbackRegistration", {"passed": '<string name="mode">INEXACT</string>' in snapshot, "runtime": snapshot})
    fixture("cleanup")

    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    output = adb("shell", "am", "instrument", "-w", "-e", "class",
                 "com.executionagent.app.Phase8ApplicationUiTest#mockCoachKeepsDraftThroughRecreationAndAppliesOnlyAfterConfirmation", RUNNER)
    (ROOT / "phase8-offline-ui.log").write_text(output, encoding="utf-8")
    record("offlineMockAndDraftUi", {"passed": "OK (1 test)" in output})
finally:
    adb("shell", "dumpsys", "deviceidle", "unforce")
    for mode, enabled in previous_doze.items():
        adb("shell", "dumpsys", "deviceidle", "enable" if enabled else "disable", mode)
    adb("shell", "dumpsys", "battery", "reset")
    adb("shell", "svc", "wifi", "enable")
    adb("shell", "svc", "data", "enable")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
print("Runtime scenarios completed", flush=True)
