"""
generate_alarm.py
-----------------
Generates the IEC 60601-1-8 fluid-delivery alarm tone as a WAV file.

Standard reference: IEC 60601-1-8, "Drug Delivery / Infusion" melodic pattern.
Priority produced: HIGH (5-note motif repeated once = 10 pulses per loop cycle).

Motif:  C5 - D4 - G4 - [PAUSE] - C4 - D4
        Repeated once immediately after a short gap.
        Then a tail silence before the MediaPlayer loop point.

Note frequencies (equal temperament):
    C4 = 261.63 Hz
    D4 = 293.66 Hz
    G4 = 392.00 Hz
    C5 = 523.25 Hz

Timbre: Additive synthesis of odd harmonics (1/n rolloff, approximates square
        wave character).  Produces >= 4 harmonics in the 300-4000 Hz band
        required by the IEC standard.

Output: fluid_delivery_alarm.wav  (placed next to this script)
        Copy to app/src/main/res/raw/fluid_delivery_alarm.wav before building.

Dependencies: numpy, scipy
    pip install numpy scipy
"""

import numpy as np
import scipy.io.wavfile as wav
import os

# ---------------------------------------------------------------------------
# Tunable parameters  (match these names to AlertConfig in the Kotlin source)
# ---------------------------------------------------------------------------
SAMPLE_RATE      = 44100   # Hz
NOTE_DUR_MS      = 150     # ms per tone pulse  (IEC range: 75-200)
NOTE_GAP_MS      = 100     # ms silence between consecutive tones in a group
MOTIF_PAUSE_MS   = 200     # ms for the named [PAUSE] between G4 and C4
REPEAT_GAP_MS    = 250     # ms gap between the two repetitions of the motif
LOOP_SILENCE_MS  = 2500    # ms tail silence before MediaPlayer loops back
ATTACK_MS        = 8       # ms linear attack per note  (click suppression)
RELEASE_MS       = 15      # ms linear release per note
AMPLITUDE        = 0.70    # peak amplitude 0.0-1.0
N_HARMONICS      = 7       # number of odd harmonics: 1, 3, 5, ..., 2n-1
# ---------------------------------------------------------------------------

NOTES = {
    "C4": 261.63,
    "D4": 293.66,
    "G4": 392.00,
    "C5": 523.25,
}

# IEC fluid-delivery High-priority pattern
#   Motif: C5, D4, G4, [intra-pause], C4, D4
#   High priority = motif x2, separated by a repeat gap
MOTIF         = ["C5", "D4", "G4", None, "C4", "D4"]   # None = intra-motif pause
FULL_SEQUENCE = MOTIF + [None] + MOTIF                  # centre None = repeat gap


def ms_to_samples(ms: float) -> int:
    return int(SAMPLE_RATE * ms / 1000.0)


def make_tone(freq_hz: float, dur_ms: float) -> np.ndarray:
    """Synthesise one note with harmonically rich content."""
    n = ms_to_samples(dur_ms)
    t = np.arange(n) / SAMPLE_RATE
    wave = np.zeros(n, dtype=np.float64)
    for k in range(1, N_HARMONICS * 2, 2):          # odd harmonics only
        h = freq_hz * k
        if h > SAMPLE_RATE / 2:                     # stay below Nyquist
            break
        wave += (1.0 / k) * np.sin(2.0 * np.pi * h * t)
    peak = np.max(np.abs(wave))
    if peak > 0:
        wave /= peak
    # Linear attack / release envelope
    att = min(ms_to_samples(ATTACK_MS),  n // 4)
    rel = min(ms_to_samples(RELEASE_MS), n // 4)
    env = np.ones(n, dtype=np.float64)
    env[:att]   = np.linspace(0.0, 1.0, att)
    env[-rel:]  = np.linspace(1.0, 0.0, rel)
    return wave * env * AMPLITUDE


def make_silence(dur_ms: float) -> np.ndarray:
    return np.zeros(ms_to_samples(dur_ms), dtype=np.float64)


def build_sequence() -> np.ndarray:
    segments = []
    prev_none = False
    for i, entry in enumerate(FULL_SEQUENCE):
        if entry is None:
            # Centre None between motif repetitions uses repeat gap;
            # intra-motif None uses motif pause.
            dur = REPEAT_GAP_MS if i == len(MOTIF) else MOTIF_PAUSE_MS
            segments.append(make_silence(dur))
            prev_none = True
        else:
            if not prev_none and segments:
                segments.append(make_silence(NOTE_GAP_MS))
            segments.append(make_tone(NOTES[entry], NOTE_DUR_MS))
            prev_none = False
    # Tail silence before MediaPlayer loop point
    segments.append(make_silence(LOOP_SILENCE_MS))
    return np.concatenate(segments)


def main() -> None:
    print("IEC 60601-1-8  Fluid Delivery Alarm  -  High Priority")
    print(f"  Motif    : {' - '.join(str(e) if e else '[PAUSE]' for e in MOTIF)}")
    print(f"  Repeats  : 2  (High priority)")
    print(f"  Note dur : {NOTE_DUR_MS} ms  |  Harmonics: odd 1..{2*N_HARMONICS - 1}")
    print(f"  Rate     : {SAMPLE_RATE} Hz  |  Amplitude: {AMPLITUDE}")

    signal    = build_sequence()
    dur_s     = len(signal) / SAMPLE_RATE
    pcm_int16 = (np.clip(signal, -1.0, 1.0) * 32767).astype(np.int16)

    out_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "fluid_delivery_alarm.wav"
    )
    wav.write(out_path, SAMPLE_RATE, pcm_int16)

    print(f"  Loop dur : {dur_s * 1000:.0f} ms  ({dur_s:.2f} s)")
    print(f"  Written  : {out_path}")
    print()
    print("Next step:")
    print("  Copy fluid_delivery_alarm.wav to")
    print("  app/src/main/res/raw/fluid_delivery_alarm.wav")
    print("  (Create the 'raw' folder if it does not exist.)")


if __name__ == "__main__":
    main()
