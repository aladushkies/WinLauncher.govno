#!/usr/bin/env python3
"""Compile every Java source against android.jar (API 35) with a real javac.

The project shipped a static source-coherence verifier only: nothing in the repository ever
proved that the 23 Java files compile. This gate closes that gap. It is deliberately
standalone and side-effect free — it never writes into the source tree.

Toolchain discovery (first hit wins):
  * android.jar : $ANDROID_JAR, $ANDROID_HOME/platforms/android-35/android.jar,
                   $ANDROID_SDK_ROOT/platforms/android-35/android.jar, tools/android-35.jar
  * javac       : $JAVA_HOME/bin/javac, `javac` on PATH

Result codes: 0 = compiled, 1 = compile errors, 2 = toolchain unavailable (skip).
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app/src/main/java"
COMPILE_SDK = 35


def find_android_jar() -> Path | None:
    candidates = []
    env = os.environ.get("ANDROID_JAR")
    if env:
        candidates.append(Path(env))
    for base in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if base:
            candidates.append(Path(base) / "platforms" / f"android-{COMPILE_SDK}" / "android.jar")
    candidates.append(ROOT / "tools" / f"android-{COMPILE_SDK}.jar")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def find_javac() -> str | None:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "javac"
        if candidate.is_file():
            return str(candidate)
    return shutil.which("javac")


def sources() -> list[str]:
    return [str(p) for p in sorted(JAVA_ROOT.rglob("*.java"))]


def compile_once(javac: str, jar: Path, out: Path, release: bool) -> subprocess.CompletedProcess:
    command = [javac, "-encoding", "UTF-8", "-nowarn", "-Xlint:-options",
               "-classpath", str(jar), "-d", str(out)]
    if release:
        command += ["-source", "1.8", "-target", "1.8"]
    command += sources()
    return subprocess.run(command, capture_output=True, text=True)


def run() -> tuple[int, str]:
    javac = find_javac()
    jar = find_android_jar()
    if javac is None or jar is None:
        missing = []
        if javac is None:
            missing.append("javac (set JAVA_HOME or put javac on PATH)")
        if jar is None:
            missing.append(f"android.jar for API {COMPILE_SDK} (set ANDROID_JAR or ANDROID_HOME)")
        return 2, "toolchain unavailable: missing " + "; ".join(missing)
    with tempfile.TemporaryDirectory(prefix="winlauncher-compile-") as tmp:
        out = Path(tmp) / "classes"
        out.mkdir()
        result = compile_once(javac, jar, out, release=True)
        if result.returncode != 0 and ("Source option 8 is no longer supported" in result.stderr
                                       or "release version 8 not supported" in result.stderr
                                       or "option --source 8" in result.stderr):
            # A newer JDK dropped source/target 8: retry with the toolchain default level.
            result = compile_once(javac, jar, out, release=False)
        classes = len(list(out.rglob("*.class")))
        if result.returncode != 0:
            return 1, (result.stdout or "") + (result.stderr or "")
        return 0, f"{classes} class files compiled against {jar.name} (API {COMPILE_SDK})"


def main() -> int:
    code, message = run()
    if code == 0:
        print(f"PASS  javac: {message}")
        return 0
    if code == 2:
        print(f"SKIP  javac: {message}")
        return 2
    print("FAIL  javac: compilation failed")
    print(message)
    return 1


if __name__ == "__main__":
    sys.exit(main())
