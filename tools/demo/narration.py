#!/usr/bin/env python3
"""Закадровый голос демо-ролика.

Озвучивает фразы из narration.txt голосом Piper TTS, раскладывает их по секундам ролика
и опускает голос до низкого баритона (ffmpeg: rubberband). Запуск:
    narration.py голос.onnx narration.txt narration.wav
Нужны piper-tts==1.2.0, numpy и ffmpeg с rubberband.
"""
import argparse
import subprocess
import sys
import wave

import numpy as np
from piper import PiperVoice

# Ударения, которые espeak-ng ставит неверно: фонемы слова → исправленные.
STRESS_FIXES = {"danʲˈik": "dˈanʲɪk"}
# Средняя высота голоса после обработки — низкий баритон.
TARGET_F0 = 100.0
# Формантам — чуть ниже, чем было: голос звучит грудным, а не «мультяшным».
FORMANT_SHIFT = 0.94
SENTENCE_PAUSE = 0.22
LINE_GAP = 0.3
# Быстрее этого фраза не читается, даже если не помещается до следующей.
FASTEST = 0.78
OUT_RATE = 48000


def read_lines(path):
    """Пары (секунда, фраза); последняя строка с «-» — конец ролика."""
    lines = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            start, text = line.split(maxsplit=1)
            lines.append((float(start), text))
    return lines


def trim(pcm, rate):
    loud = np.flatnonzero(np.abs(pcm) > 0.01)
    if loud.size == 0:
        return pcm[:0]
    margin = int(0.03 * rate)
    return pcm[max(0, loud[0] - margin):loud[-1] + margin]


def speak(voice, text, length_scale):
    rate = voice.config.sample_rate
    pieces = []
    for sentence in voice.phonemize(text):
        phonemes = "".join(sentence)
        for wrong, right in STRESS_FIXES.items():
            phonemes = phonemes.replace(wrong, right)
        raw = voice.synthesize_ids_to_raw(voice.phonemes_to_ids(list(phonemes)), length_scale=length_scale)
        pieces.append(trim(np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0, rate))
        pieces.append(np.zeros(int(SENTENCE_PAUSE * rate), np.float32))
    return np.concatenate(pieces[:-1])


def median_f0(pcm, rate):
    """Медиана основного тона по звонким кадрам (автокорреляция)."""
    frame, hop = int(0.04 * rate), int(0.01 * rate)
    low, high = int(rate / 300), int(rate / 60)
    loudness = np.sqrt(np.mean(pcm ** 2)) if pcm.size else 0.0
    found = []
    for i in range(0, len(pcm) - frame, hop):
        chunk = pcm[i:i + frame] * np.hanning(frame)
        if np.sqrt(np.mean(chunk ** 2)) < loudness * 0.5:
            continue
        spectrum = np.fft.rfft(chunk, 2 * frame)
        corr = np.fft.irfft(spectrum * np.conj(spectrum))[:frame]
        if corr[0] <= 0:
            continue
        lag = low + int(np.argmax(corr[low:high]))
        # Пик на двойном периоде — частая ошибка «на октаву ниже»: берём половинный, если он почти не хуже.
        while lag // 2 >= low and corr[lag // 2] > 0.85 * corr[lag]:
            lag //= 2
        if corr[lag] / corr[0] > 0.45:
            found.append(rate / lag)
    return float(np.median(found)) if found else 0.0


def write_wav(path, pcm, rate):
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes((np.clip(pcm, -1.0, 1.0) * 32767).astype(np.int16).tobytes())


def read_wav(path):
    with wave.open(path, "rb") as src:
        data = np.frombuffer(src.readframes(src.getnframes()), np.int16).astype(np.float32) / 32768.0
        return data, src.getframerate()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model")
    parser.add_argument("lines")
    parser.add_argument("out")
    parser.add_argument("--length-scale", type=float, default=None, help="темп речи (больше — медленнее)")
    args = parser.parse_args()

    voice = PiperVoice.load(args.model)
    rate = voice.config.sample_rate
    base_scale = args.length_scale or voice.config.length_scale
    lines = read_lines(args.lines)
    placed = []
    cursor = 0.0
    for (start, text), (next_start, _) in zip(lines, lines[1:]):
        at = max(start, cursor)
        slot = next_start - LINE_GAP - at
        scale = base_scale
        audio = speak(voice, text, scale)
        # Не помещается до следующей фразы — читаем быстрее (длительность почти пропорциональна темпу).
        for _ in range(2):
            if len(audio) / rate <= slot or scale <= base_scale * FASTEST:
                break
            scale = max(base_scale * FASTEST, scale * slot / (len(audio) / rate) * 0.98)
            audio = speak(voice, text, scale)
        end = at + len(audio) / rate
        notes = []
        if scale < base_scale:
            notes.append(f"темп ×{base_scale / scale:.2f}")
        if at - start >= 0.05:
            notes.append(f"сдвинута на {at - start:.1f} с")
        print(f"{at:6.2f}–{end:6.2f}  {text}" + (f"   ({', '.join(notes)})" if notes else ""))
        placed.append((at, audio))
        cursor = end + LINE_GAP

    track = np.zeros(int(max(cursor, lines[-1][0]) * rate), np.float32)
    for at, audio in placed:
        i = int(at * rate)
        track[i:i + len(audio)] += audio

    raw_path = args.out + ".raw.wav"
    write_wav(raw_path, track, rate)
    f0 = median_f0(track, rate)
    pitch = min(1.0, max(0.6, TARGET_F0 / f0)) if f0 > 0 else 0.8
    print(f"Голос: основной тон {f0:.0f} Гц")
    # Сдвиг высоты оценивается по готовому звуку и при необходимости уточняется.
    for _ in range(3):
        lower(raw_path, args.out, rate, pitch)
        result, result_rate = read_wav(args.out)
        result_f0 = median_f0(result, result_rate)
        print(f"  понижение в {1 / pitch:.2f} раза → {result_f0:.0f} Гц")
        if result_f0 <= 0 or abs(result_f0 / TARGET_F0 - 1.0) < 0.04:
            break
        corrected = min(1.0, max(0.6, pitch * TARGET_F0 / result_f0))
        if corrected == pitch:
            break
        pitch = corrected
    print(f"Готово: {args.out}, {len(result) / result_rate:.1f} с, основной тон {result_f0:.0f} Гц")


def lower(source, out, rate, pitch):
    """Баритон: ниже тон и чуть ниже форманты, плюс тёплый эквалайзер и компрессор."""
    chain = ",".join([
        f"asetrate={rate * FORMANT_SHIFT:.0f}",
        f"rubberband=tempo={1 / FORMANT_SHIFT:.5f}:pitch={pitch / FORMANT_SHIFT:.5f}:formant=preserved",
        f"aresample={OUT_RATE}",
        "highpass=f=55",
        "equalizer=f=130:t=q:w=1.1:g=3.5",
        "equalizer=f=3200:t=q:w=1.6:g=-2.5",
        "acompressor=threshold=0.12:ratio=3:attack=8:release=160:makeup=1.6",
        "alimiter=limit=0.89",
    ])
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", source, "-af", chain, "-ac", "1",
                    "-c:a", "pcm_s16le", out], check=True)

if __name__ == "__main__":
    sys.exit(main())
