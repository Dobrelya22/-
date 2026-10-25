#!/usr/bin/env python3
"""Закадровый голос демо-ролика.

Озвучивает фразы из narration.txt («секунда метка фраза»), раскладывает их по секундам
ролика и обрабатывает голос. Пишет narration.wav и файл меток (метка, начало, конец) — по нему
сценарий в src/demo привязывает действия в игре к фразам. Два движка:
    narration.py --engine silero --model v5_6_ru.pt --speaker xenia --voice anime narration.txt out.wav
    narration.py --engine piper --model ru_RU-ruslan-medium.onnx --voice baritone narration.txt out.wav
Silero нужен torch, Piper — piper-tts==1.2.0; обоим — numpy и ffmpeg с rubberband.
Голос Silero распространяется по лицензии CC BY-NC 4.0: ролик — не для коммерции.
"""
import argparse
import re
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

LINE_GAP = 0.3
# Не помещается до следующей фразы — читаем быстрее, но не быстрее этого.
FASTEST_TEMPO = 1.28
OUT_RATE = 48000

# Обработка голоса: высота, форманты и эквалайзер.
PROFILES = {
    # Низкий баритон: ниже тон и чуть ниже форманты, тёплый низ.
    "baritone": dict(target_f0=95.0, formant=0.94, min_pitch=0.65, max_pitch=1.0, f0_range=(60, 300), eq=[
        "highpass=f=55",
        "equalizer=f=130:t=q:w=1.1:g=3.5",
        "equalizer=f=3200:t=q:w=1.6:g=-2.5",
    ]),
    # Звонкий голосок аниме-девочки: выше тон, чуть выше форманты, светлый верх без гула.
    "anime": dict(target_f0=290.0, formant=1.06, min_pitch=1.0, max_pitch=1.35, f0_range=(120, 520), eq=[
        "highpass=f=120",
        "equalizer=f=380:t=q:w=1.0:g=-2.5",
        "equalizer=f=3300:t=q:w=1.3:g=2.5",
        "treble=g=2:f=9000",
    ]),
}


def read_lines(path):
    """(секунда, метка, фраза); последняя строка с фразой «-» — конец ролика."""
    lines = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            start, cue, text = line.split(maxsplit=2)
            lines.append((float(start), cue, text))
    return lines


def trim(pcm, rate):
    loud = np.flatnonzero(np.abs(pcm) > 0.01)
    if loud.size == 0:
        return pcm[:0]
    margin = int(0.03 * rate)
    return pcm[max(0, loud[0] - margin):loud[-1] + margin]


class Silero:
    def __init__(self, model_path, speaker):
        import torch
        torch.set_num_threads(4)
        self.model = torch.package.PackageImporter(model_path).load_pickle("tts_models", "model")
        self.model.to(torch.device("cpu"))
        self.speaker = speaker
        self.rate = OUT_RATE

    def speak(self, text):
        # Модель знает только кириллицу и простую пунктуацию; «+» перед гласной — ударение.
        text = text.replace("—", ",").replace("–", ",").replace("…", ".")
        text = re.sub(r"[«»\"()]", "", text)
        text = re.sub(r"[^А-Яа-яЁё0-9+\-.,!?: ]", " ", text).strip()
        if text[-1] not in ".!?":
            text += "."
        ssml = f'<speak><prosody pitch="high">{text}</prosody></speak>'
        audio = self.model.apply_tts(ssml_text=ssml, speaker=self.speaker, sample_rate=self.rate)
        return trim(audio.numpy().astype(np.float32), self.rate)


class Piper:
    # Ударения, которые espeak-ng ставит неверно: фонемы слова → исправленные.
    STRESS_FIXES = {"danʲˈik": "dˈanʲɪk"}
    SENTENCE_PAUSE = 0.22

    def __init__(self, model_path):
        from piper import PiperVoice
        self.voice = PiperVoice.load(model_path)
        self.rate = self.voice.config.sample_rate

    def speak(self, text):
        pieces = []
        for sentence in self.voice.phonemize(text.replace("+", "")):
            phonemes = "".join(sentence)
            for wrong, right in self.STRESS_FIXES.items():
                phonemes = phonemes.replace(wrong, right)
            raw = self.voice.synthesize_ids_to_raw(self.voice.phonemes_to_ids(list(phonemes)))
            pieces.append(trim(np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0, self.rate))
            pieces.append(np.zeros(int(self.SENTENCE_PAUSE * self.rate), np.float32))
        return np.concatenate(pieces[:-1])


def faster(pcm, rate, tempo):
    """Ускорение фразы без изменения высоты (rubberband)."""
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "in.wav", Path(tmp) / "out.wav"
        write_wav(src, pcm, rate)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(src), "-af", f"rubberband=tempo={tempo:.4f}",
                        str(dst)], check=True)
        return read_wav(dst)[0]


def median_f0(pcm, rate, f0_range):
    """Медиана основного тона по звонким кадрам (автокорреляция)."""
    frame, hop = int(0.04 * rate), int(0.01 * rate)
    low, high = int(rate / f0_range[1]), int(rate / f0_range[0])
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
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes((np.clip(pcm, -1.0, 1.0) * 32767).astype(np.int16).tobytes())


def read_wav(path):
    with wave.open(str(path), "rb") as src:
        data = np.frombuffer(src.readframes(src.getnframes()), np.int16).astype(np.float32) / 32768.0
        return data, src.getframerate()


def process(source, out, rate, pitch, profile):
    """Высота и форманты (asetrate + rubberband), эквалайзер, компрессор и ограничитель."""
    formant = profile["formant"]
    chain = ",".join([
        f"asetrate={rate * formant:.0f}",
        f"rubberband=tempo={1 / formant:.5f}:pitch={pitch / formant:.5f}:formant=preserved",
        f"aresample={OUT_RATE}",
        *profile["eq"],
        "acompressor=threshold=0.12:ratio=3:attack=8:release=160:makeup=1.6",
        "alimiter=limit=0.89",
    ])
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(source), "-af", chain, "-ac", "1",
                    "-c:a", "pcm_s16le", str(out)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lines")
    parser.add_argument("out")
    parser.add_argument("--engine", choices=["silero", "piper"], default="silero")
    parser.add_argument("--model", required=True, help="модель Silero (.pt) или голос Piper (.onnx)")
    parser.add_argument("--speaker", default="xenia", help="голос Silero")
    parser.add_argument("--voice", choices=sorted(PROFILES), default="anime", help="обработка голоса")
    parser.add_argument("--timing", help="куда записать метки: «метка начало конец» (по умолчанию рядом с out)")
    args = parser.parse_args()

    engine = Silero(args.model, args.speaker) if args.engine == "silero" else Piper(args.model)
    rate = engine.rate
    profile = PROFILES[args.voice]
    lines = read_lines(args.lines)
    placed = []
    cues = []
    cursor = 0.0
    for (start, cue, text), (next_start, _, _) in zip(lines, lines[1:]):
        at = max(start, cursor)
        slot = next_start - LINE_GAP - at
        audio = engine.speak(text)
        tempo = 1.0
        if slot > 0 and len(audio) / rate > slot:
            tempo = min(FASTEST_TEMPO, len(audio) / rate / slot * 1.02)
            audio = faster(audio, rate, tempo)
        end = at + len(audio) / rate
        notes = []
        if tempo > 1.0:
            notes.append(f"темп ×{tempo:.2f}")
        if at - start >= 0.05:
            notes.append(f"сдвинута на {at - start:.1f} с")
        print(f"{at:7.2f}–{end:7.2f}  {cue:<14} {text}" + (f"   ({', '.join(notes)})" if notes else ""))
        placed.append((at, audio))
        cues.append((cue, at, end))
        cursor = end + LINE_GAP
    final_start, final_cue, _ = lines[-1]
    finish = max(cursor, final_start)
    cues.append((final_cue, finish, finish))

    track = np.zeros(int(finish * rate) + rate, np.float32)
    for at, audio in placed:
        i = int(at * rate)
        track[i:i + len(audio)] += audio

    timing = Path(args.timing) if args.timing else Path(args.out).with_name("narration-timing.txt")
    timing.write_text("".join(f"{cue} {begin:.3f} {end:.3f}\n" for cue, begin, end in cues), encoding="utf-8")

    raw_path = Path(args.out).with_suffix(".raw.wav")
    write_wav(raw_path, track, rate)
    # Высоту меряем по исходному голосу: после компрессора оценка ненадёжна.
    f0 = median_f0(track, rate, profile["f0_range"])
    pitch = min(profile["max_pitch"], max(profile["min_pitch"], profile["target_f0"] / f0)) if f0 > 0 else 1.0
    print(f"Голос: основной тон {f0:.0f} Гц, сдвиг ×{pitch:.2f} — до ~{f0 * pitch:.0f} Гц")
    process(raw_path, args.out, rate, pitch, profile)
    raw_path.unlink()
    result, result_rate = read_wav(args.out)
    print(f"Готово: {args.out}, {len(result) / result_rate:.1f} с; метки — {timing}")


if __name__ == "__main__":
    sys.exit(main())
