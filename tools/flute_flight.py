#!/usr/bin/env python3
"""Звук полёта стрелы — мелодия флейты.

Ноты — живые записи флейты из VSCO 2: Community Edition (Versilian Studios, лицензия CC0,
https://github.com/sgossner/VSCO-2-CE), протяжные с мягким вибрато. Скрипт раскладывает их
в спокойную мелодию в ля-минорной пентатонике, добавляет лёгкий зал и сводит в бесшовную
петлю. Запуск:
    git clone --filter=blob:none --sparse https://github.com/sgossner/VSCO-2-CE vsco
    git -C vsco sparse-checkout set Woodwinds/Flute/susvib
    tools/flute_flight.py vsco/Woodwinds/Flute/susvib
Нужны numpy и ffmpeg с libvorbis.
"""
import argparse
import math
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 44100
BPM = 84
TARGET = Path("src/main/resources/assets/andryushka_arrow/sounds/flight.ogg")
NOTES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
# Какие ноты записаны (научная нотация). В именах файлов VSCO октава на единицу меньше:
# «LDFlute_susvib_A3» звучит как ля первой октавы, 440 Гц.
RECORDED = ["C4", "E4", "A4", "C5", "E5", "A5"]
# Мелодия: нота и длительность в долях. Восемь тактов по четыре доли, конец переходит в начало.
MELODY = [
    ("E5", 1), ("D5", 0.5), ("C5", 0.5), ("A4", 2),
    ("C5", 1), ("D5", 1), ("E5", 2),
    ("G5", 1), ("E5", 0.5), ("D5", 0.5), ("C5", 1), ("D5", 1),
    ("E5", 4),
    ("A5", 1), ("G5", 0.5), ("E5", 0.5), ("D5", 2),
    ("E5", 1), ("D5", 0.5), ("C5", 0.5), ("A4", 2),
    ("C5", 1), ("D5", 0.5), ("E5", 0.5), ("G4", 1), ("A4", 1),
    ("A4", 4),
]
# Нота звучит чуть дольше своей доли и затихает поверх следующей — получается легато.
OVERLAP = 0.09
RELEASE = 0.16
REVERB_SECONDS = 1.6
REVERB_WET = 0.22
MEAN_DB = -17.0


def midi(name):
    return 12 * (int(name[-1]) + 1) + NOTES[name[0]] + (1 if "#" in name else 0)


def vsco_file(folder, note):
    return folder / f"LDFlute_susvib_{note[:-1]}{int(note[-1]) - 1}_v1_1.wav"


def load_wav(path):
    with wave.open(str(path)) as src:
        raw = src.readframes(src.getnframes())
        width, channels, rate = src.getsampwidth(), src.getnchannels(), src.getframerate()
    if width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        pcm = ((b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)) << 8 >> 8).astype(np.float32) / 8388608.0
    else:
        pcm = np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0
    mono = pcm.reshape(-1, channels).mean(axis=1)
    if rate != RATE:
        mono = np.interp(np.arange(0, len(mono), rate / RATE), np.arange(len(mono)), mono)
    # Начинаем с самого вдоха ноты, без тишины перед ней.
    envelope = np.convolve(np.abs(mono), np.ones(441) / 441, "same")
    onset = max(0, int(np.argmax(envelope > envelope.max() * 0.05)) - int(0.005 * RATE))
    return mono[onset:]


def shifted(sample, semitones):
    """Сдвиг на несколько полутонов передискретизацией (вибрато чуть ускоряется — на слух незаметно)."""
    if semitones == 0:
        return sample
    ratio = 2.0 ** (semitones / 12.0)
    return np.interp(np.arange(0, len(sample) - 1, ratio), np.arange(len(sample)), sample)


def note_audio(samples, name, seconds):
    target = midi(name)
    source = min(samples, key=lambda recorded: abs(midi(recorded) - target))
    audio = shifted(samples[source], target - midi(source))
    length = int((seconds + OVERLAP + RELEASE) * RATE)
    audio = audio[:length].copy()
    release = min(len(audio), int(RELEASE * RATE))
    audio[-release:] *= np.linspace(1.0, 0.0, release) ** 2
    return audio


def reverb_impulse():
    """Мягкий «зал»: затухающий шум, у которого высокие гаснут быстрее низких."""
    rng = np.random.default_rng(7)
    n = int(REVERB_SECONDS * RATE)
    t = np.arange(n) / RATE
    noise = rng.standard_normal(n)
    smooth = np.convolve(noise, np.ones(6) / 6, "same")
    bright = noise - smooth
    ir = smooth * np.exp(-t * 6.9 / REVERB_SECONDS) + 0.35 * bright * np.exp(-t * 6.9 / (REVERB_SECONDS * 0.4))
    ir[: int(0.012 * RATE)] = 0.0
    return ir / np.sqrt(np.sum(ir ** 2))


def convolve(signal, kernel):
    size = 1 << int(math.ceil(math.log2(len(signal) + len(kernel))))
    return np.fft.irfft(np.fft.rfft(signal, size) * np.fft.rfft(kernel, size), size)[: len(signal) + len(kernel)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("susvib", type=Path, help="папка Woodwinds/Flute/susvib из VSCO 2: CE")
    parser.add_argument("out", type=Path, nargs="?", default=TARGET)
    args = parser.parse_args()

    samples = {note: load_wav(vsco_file(args.susvib, note)) for note in RECORDED}
    beat = 60.0 / BPM
    loop = int(sum(beats for _, beats in MELODY) * beat * RATE)
    # Два круга подряд: второй уже содержит хвосты нот и зала от первого, поэтому
    # его конец бесшовно переходит в его же начало.
    dry = np.zeros(loop * 2 + int(2 * RATE))
    for cycle in range(2):
        at = cycle * loop
        for name, beats in MELODY:
            audio = note_audio(samples, name, beats * beat)
            dry[at:at + len(audio)] += audio
            at += int(beats * beat * RATE)
    wet = convolve(dry, reverb_impulse())[: len(dry)]
    mix = dry + REVERB_WET * wet / max(1e-9, np.sqrt(np.mean(wet ** 2))) * np.sqrt(np.mean(dry ** 2))
    track = mix[loop:loop * 2]
    track *= 10 ** (MEAN_DB / 20) / np.sqrt(np.mean(track ** 2))
    peak = np.abs(track).max()
    if peak > 0.89:
        track *= 0.89 / peak

    wav_path = args.out.with_suffix(".wav")
    with wave.open(str(wav_path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((np.clip(track, -1.0, 1.0) * 32767).astype(np.int16).tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", "5",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-serial_offset", "1",
                    str(args.out)], check=True)
    wav_path.unlink()
    print(f"{args.out}: {len(track) / RATE:.1f} с, пик {20 * math.log10(np.abs(track).max()):.1f} дБ")


if __name__ == "__main__":
    sys.exit(main())
