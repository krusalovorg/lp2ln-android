"""Generate a test-only probe and analyze a synchronized stereo PCM measurement.
Requires Python 3, NumPy and SciPy. Never records from the phone microphone.
"""
import argparse
import json
import wave
import numpy as np
from scipy.signal import chirp, correlate, find_peaks
from scipy.signal.windows import tukey

RATE = 48_000

def marker():
    t = np.arange(int(0.09 * RATE)) / RATE
    return (chirp(t, 480, t[-1], 2680, method="logarithmic") * tukey(len(t), .3)).astype(np.float32)

def generate(path, count):
    pulse = np.zeros(RATE, dtype="<i2")
    probe = marker()
    pulse[RATE // 4:RATE // 4 + len(probe)] = np.rint(probe * 9000).astype("<i2")
    with wave.open(path, "wb") as out:
        out.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        for _ in range(count):
            out.writeframes(pulse.tobytes())

def matches(samples, template):
    score = np.abs(correlate(samples, template, mode="valid", method="fft"))
    energy = np.r_[0, np.cumsum(samples.astype(np.float64) ** 2)]
    norm = np.sqrt(np.maximum(energy[len(template):] - energy[:-len(template)], 1e-12))
    score /= np.maximum(norm * np.linalg.norm(template), 1e-12)
    score[norm < .001 * np.sqrt(len(template))] = 0
    peaks, _ = find_peaks(score, height=.28, distance=RATE//20)
    return peaks

def measure(path, correction_ms, expected):
    delays = []
    refs = missing = ambiguous = 0
    template = marker()
    with wave.open(path, "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (2, 2, RATE):
            raise ValueError("Use 48 kHz, 16-bit stereo PCM on one capture clock")
        # Overlap covers the largest allowed acoustic delay and full marker.
        for pos in range(0, wav.getnframes(), 8*RATE):
            pre = max(0, pos-RATE//4)
            wav.setpos(pre)
            data = np.frombuffer(wav.readframes(9*RATE+pos-pre), dtype="<i2").reshape(-1,2)
            if len(data) < len(template): break
            left,right = (matches(data[:,c].astype(np.float32)/32768,template)+pre for c in (0,1))
            for source in left[(left>=pos) & (left<pos+8*RATE)]:
                refs += 1
                candidates = right[(right>=source) & (right<=source+RATE*.65)]
                if len(candidates)==1: delays.append((candidates[0]-source)*1000/RATE-correction_ms)
                elif len(candidates)>1: ambiguous += 1
                else: missing += 1
    return dict(expected=expected, reference_events=refs, matched=len(delays), missing_return=missing,
                ambiguous=ambiguous, correction_ms=correction_ms,
                coverage=len(delays)/expected,
                latency_ms=dict(zip(["p50","p95","p99"],np.percentile(delays,[50,95,99]))) if delays else None)

if __name__=="__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    sub=cli.add_subparsers(dest="cmd",required=True)
    gen=sub.add_parser("generate")
    gen.add_argument("mono_wav");gen.add_argument("--count",type=int,default=100)
    read=sub.add_parser("measure")
    read.add_argument("stereo_wav");read.add_argument("--expected",type=int,required=True)
    read.add_argument("--correction-ms",type=float,required=True)
    args=cli.parse_args()
    if args.cmd=="generate":
        if not 1<=args.count<=1800: cli.error("count must be 1..1800")
        generate(args.mono_wav,args.count)
    else:
        if args.expected<1: cli.error("expected must be positive")
        print(json.dumps(measure(args.stereo_wav,args.correction_ms,args.expected),indent=2))
