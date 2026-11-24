"""Build and compare the baseline and current file transfers on localhost."""

import argparse
import io
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
TARGET = ROOT / "rust" / "target"
BASE = "14f3d3c038499e83fb244cc3c5aabf507567a476"
SAMPLE = re.compile(r"TRANSFER_SAMPLE (normal|offline|silent|slow) (\d+) (\d+) (\d+) (\{.*\})$")
CHECK = re.compile(r"TRANSFER_(RETRY|PLACEMENT) (\{.*\})$")


def build(manifest, label, scratch, offline):
    env = os.environ | {
        "CARGO_PROFILE_TEST_OPT_LEVEL": "2",
        "CARGO_PROFILE_TEST_DEBUG": "0",
        "CARGO_TARGET_DIR": str(TARGET / "transfer-opt"),
    }
    command = ["cargo", "test", "--manifest-path", str(manifest), "--locked", "--lib",
               "--no-run", "--message-format=json"]
    if offline:
        command.append("--offline")
    output = subprocess.check_output(command, cwd=ROOT / "rust", env=env, text=True,
                                     encoding="utf-8")
    artifacts = (json.loads(line) for line in output.splitlines())
    executable = next(Path(item["executable"]) for item in artifacts
                      if item.get("profile", {}).get("test") and item.get("executable"))
    binary = scratch / (label + executable.suffix)
    shutil.copy2(executable, binary)
    return binary


def run(binary, test, env, log, ignored=False):
    command = [str(binary), test, "--nocapture", "--test-threads=1"]
    if ignored:
        command.append("--ignored")
    samples = []
    with log.open("w", encoding="utf-8") as output:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              env=env, text=True, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                output.write(line)
                match = SAMPLE.search(line.strip())
                if match:
                    scenario, delay, size, trial, raw = match.groups()
                    samples.append({"case": scenario, "delay_ms": int(delay), "mib": int(size),
                                    "run": int(trial), **json.loads(raw)})
                match = CHECK.search(line.strip())
                if match:
                    samples.append({"check": match[1].lower(), **json.loads(match[2])})
            failed = process.wait() != 0
    if failed and not samples:
        raise RuntimeError(f"{test} failed. Local log: {log}")
    print(f"{log.stem}: {len(samples)} samples", flush=True)
    return samples, failed


def summarize(samples):
    groups = {}
    for sample in samples:
        key = (sample["variant"], sample["case"], sample["mib"])
        groups.setdefault(key, []).append(sample)
    summaries = []
    for (variant, case, size), group in groups.items():
        row = {"variant": variant, "case": case, "mib": size, "runs": len(group),
               "errors": sum(bool(sample.get("error")) for sample in group)}
        valid = [sample for sample in group if sample.get("sha256_ok")]
        for field in ("ack", "uploaded", "post_ack", "download"):
            if valid:
                median = statistics.median(s[field + "_s"] for s in valid)
                row[field + "_median_s"] = median
                if field != "post_ack":
                    row[field + "_mib_per_s"] = size / median
        summaries.append(row)
    return summaries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use Cargo's dependency cache")
    parser.add_argument("--output", type=Path, required=True, help="Anonymized report path")
    args = parser.parse_args()
    TARGET.mkdir(exist_ok=True)
    git = ["git", "-c", f"safe.directory={ROOT.as_posix()}"]
    scratch = Path(tempfile.mkdtemp(prefix="transfer-bench-", dir=TARGET))
    baseline = scratch / "baseline"
    archive = subprocess.check_output(git + ["archive", "--format=zip", BASE, "rust"], cwd=ROOT)
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        source.extractall(baseline)
    patch = HERE / "baseline-instrumentation.patch"
    subprocess.run(git + ["apply", "--unidiff-zero",
                          "--directory=" + baseline.relative_to(ROOT).as_posix(), str(patch)],
                   cwd=ROOT, check=True)
    benchmark = ROOT / "rust/src/file_transfer_benchmark.rs"
    (baseline / "rust/src" / benchmark.name).write_bytes(benchmark.read_bytes())
    # Separate the bridge artifacts while sharing the compiled dependencies.
    for file in ("Cargo.toml", "Cargo.lock"):
        path = baseline / "rust" / file
        path.write_bytes(path.read_bytes().replace(
            b'name = "lp2ln-android-bridge"', b'name = "lp2ln-android-bridge-bench-base"', 1
        ))
    binaries = {
        "baseline": build(baseline / "rust/Cargo.toml", "before", scratch, args.offline),
        "window": build(ROOT / "rust/Cargo.toml", "after", scratch, args.offline),
    }
    samples, checks = [], []
    report = {"baseline_commit": BASE, "test_profile": "opt-level=2, debug=0",
              "transport": "localhost TCP", "samples": samples, "checks": checks}
    jobs = [
        (variant, "normal", size)
        for size, variants in ((1, ("baseline", "window4")),
                               (8, ("window4", "baseline", "window1", "window2")),
                               (32, ("baseline", "window4")))
        for variant in variants
    ]
    jobs.extend((variant, case, 1) for case in ("offline", "slow", "silent")
                for variant in ("baseline", "window4"))
    failed = False
    for variant, case, size in jobs:
        window = variant.removeprefix("window") if variant != "baseline" else "1"
        env = os.environ | {"LP2LN_BENCH_WINDOW": window, "LP2LN_BENCH_CASE": case,
                            "LP2LN_BENCH_SIZE_MIB": str(size), "LP2LN_BENCH_DELAY_MS": "25"}
        rows, bad = run(binaries["baseline" if variant == "baseline" else "window"],
                        "measure_full_replica_transfer", env, scratch / f"{variant}-{case}-{size}.log",
                        ignored=True)
        if len(rows) != (5 if case == "normal" else 1):
            raise RuntimeError(f"Incomplete {variant} {case} {size} MiB")
        failed |= bad
        samples.extend(row | {"variant": variant} for row in rows)
        report["summary"] = summarize(samples)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for test in ("retry_after_partial_replica", "disjoint_placement_requires"):
        rows, bad = run(binaries["window"], test, os.environ.copy(), scratch / f"{test}.log")
        if len(rows) != 1:
            raise RuntimeError(f"Incomplete {test}")
        failed |= bad
        checks.extend(rows)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if failed:
        raise SystemExit("One or more runs failed. See report and local logs.")


if __name__ == "__main__":
    main()
