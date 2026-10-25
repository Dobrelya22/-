#!/usr/bin/env python3
"""Рок-звуки стрелы: сочный бас в полёте, гитарный удар, рок во время серии побед.

Партии записаны нотами прямо здесь, а играют их настоящие сэмплы инструментов из банка
MuseScore General (лицензия MIT; в Ubuntu — пакет musescore-general-soundfont) через FluidSynth:
бас-гитара, гитара с перегрузом и «мощная» рок-установка. Скрипт сводит их сам — подгружает
гитарам перегруза и «кабинет», басу рычания, — и пишет в ресурсы мода:
    flight.ogg       бас-рифф, бесшовная петля — тянется за стрелой в полёте;
    strike1..4.ogg   гитарный «лязг» при ударе по мобу;
    combo_rock.ogg   драйвовый рок (барабаны, две гитары по сторонам, бас), бесшовная петля;
    combo_lick.ogg   короткий визг гитары — с каждой победой серии игра поднимает его выше.
Петли бесшовные: каждая рендерится тремя кругами подряд и берётся средний, поэтому хвосты нот
и отзвук из конца круга уже лежат в его начале. Запуск:
    sudo apt-get install fluidsynth musescore-general-soundfont ffmpeg
    tools/rock_sounds.py /usr/share/sounds/sf3/MuseScore_General_Full.sf3
Нужны numpy, scipy, fluidsynth и ffmpeg с libvorbis.
"""
import argparse
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

RATE = 48000
SOUNDS = Path("src/main/resources/assets/andryushka_arrow/sounds")
PPQ = 480

# Инструменты (General MIDI) и ноты.
FINGERED_BASS, PICKED_BASS, DISTORTION_GUITAR = 33, 34, 30
POWER_KIT = 16
KICK, SNARE, HAT, CRASH, CRASH2 = 36, 38, 42, 49, 57
TOM_HIGH, TOM_MID, TOM_LOW, TOM_FLOOR = 50, 47, 43, 41
E1, G1, A1, BB1, B1, C2, D2, E2, G2, A2 = 28, 31, 33, 34, 35, 36, 38, 40, 43, 45
# Квинтаккорды гитары: прима, квинта, октава.
POWER = {
    "E5": (40, 47, 52), "G5": (43, 50, 55), "A5": (45, 52, 57),
    "C5": (48, 55, 60), "D5": (50, 57, 62),
}
ROOT = {"E5": E1, "G5": G1, "A5": A1, "C5": C2, "D5": D2}


# ---------------------------------------------------------------- MIDI

class Song:
    """Ноты по каналам; время — в долях (четвертях)."""

    def __init__(self, bpm):
        self.bpm = bpm
        self.events = []

    def setup(self, channel, program, volume=100, pan=64, reverb=20):
        self.events += [
            (0.0, 0, bytes([0xC0 | channel, program])),
            (0.0, 0, bytes([0xB0 | channel, 7, volume])),
            (0.0, 0, bytes([0xB0 | channel, 10, pan])),
            (0.0, 0, bytes([0xB0 | channel, 91, reverb])),
            (0.0, 0, bytes([0xB0 | channel, 93, 0])),
        ]

    def note(self, channel, start, length, pitch, velocity):
        # Отпускание раньше нажатия того же момента: повтор ноты не обрывается.
        self.events.append((start, 1, bytes([0x90 | channel, pitch, max(1, min(127, velocity))])))
        self.events.append((start + length, 0, bytes([0x80 | channel, pitch, 0])))

    def bend(self, channel, time, semitones):
        """Высота всего канала: ±2 полутона — весь диапазон колеса."""
        value = max(0, min(16383, 8192 + round(semitones / 2.0 * 8192)))
        self.events.append((time, 0, bytes([0xE0 | channel, value & 0x7F, value >> 7])))

    def glide(self, channel, start, length, frm, to, steps=24):
        for i in range(steps + 1):
            self.bend(channel, start + length * i / steps, frm + (to - frm) * i / steps)

    def write(self, path, end):
        tempo = round(60_000_000 / self.bpm)
        track = bytearray()
        last = 0
        # Мета-событие темпа и пустое событие в конце — чтобы FluidSynth доиграл хвосты.
        events = [(0.0, 0, b"\xff\x51\x03" + tempo.to_bytes(3, "big"))] + self.events
        events.append((end, 2, bytes([0xB0, 110, 0])))
        for time, _, data in sorted(events, key=lambda e: (e[0], e[1])):
            tick = round(time * PPQ)
            track += vlq(tick - last) + data
            last = tick
        track += b"\x00\xff\x2f\x00"
        path.write_bytes(b"MThd" + struct.pack(">IHHH", 6, 0, 1, PPQ) + b"MTrk" + struct.pack(">I", len(track)) + track)


def vlq(value):
    out = [value & 0x7F]
    value >>= 7
    while value:
        out.append(0x80 | (value & 0x7F))
        value >>= 7
    return bytes(reversed(out))


def render(song, soundfont, end):
    """Стерео 48 кГц, float: [n, 2]."""
    with tempfile.TemporaryDirectory() as tmp:
        mid, wav = Path(tmp) / "song.mid", Path(tmp) / "song.wav"
        song.write(mid, end)
        subprocess.run(["fluidsynth", "-ni", "-q", "-g", "0.35", "-r", str(RATE),
                        "-o", "synth.chorus.active=0", "-o", "synth.reverb.active=1",
                        "-o", "synth.reverb.room-size=0.35", "-o", "synth.reverb.damp=0.5",
                        "-o", "synth.reverb.width=0.9", "-o", "synth.reverb.level=0.6",
                        "-o", "audio.file.format=float", "-F", str(wav), soundfont, str(mid)],
                       check=True, stdout=subprocess.DEVNULL)
        rate, data = wavfile.read(wav)
        assert rate == RATE
        return data.astype(np.float64)


def beats_to_samples(song, beats):
    return round(beats * 60.0 / song.bpm * RATE)


# ---------------------------------------------------------------- обработка

def peaking(x, freq, gain_db, q):
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * freq / RATE
    alpha = np.sin(w) / (2 * q)
    b = [1 + alpha * a, -2 * np.cos(w), 1 - alpha * a]
    den = [1 + alpha / a, -2 * np.cos(w), 1 - alpha / a]
    return signal.lfilter(np.array(b) / den[0], np.array(den) / den[0], x, axis=0)


def highpass(x, freq, order=2):
    return signal.sosfilt(signal.butter(order, freq, "highpass", fs=RATE, output="sos"), x, axis=0)


def lowpass(x, freq, order=2):
    return signal.sosfilt(signal.butter(order, freq, "lowpass", fs=RATE, output="sos"), x, axis=0)


def drive(x, amount):
    """Мягкий перегруз: tanh с той же громкостью на тихих местах."""
    return np.tanh(x * amount) / np.tanh(amount)


def compress(x, threshold_db, ratio, attack_ms, release_ms, makeup_db=0.0):
    """Компрессор по огибающей обоих каналов (блоки по 1 мс)."""
    block = RATE // 1000
    level = np.abs(x).max(axis=1) if x.ndim == 2 else np.abs(x)
    blocks = level[:len(level) // block * block].reshape(-1, block).max(axis=1)
    attack = np.exp(-1.0 / attack_ms)
    release = np.exp(-1.0 / release_ms)
    env = np.empty_like(blocks)
    current = 0.0
    for i, value in enumerate(blocks):
        coeff = attack if value > current else release
        current = coeff * current + (1 - coeff) * value
        env[i] = current
    env_db = 20 * np.log10(np.maximum(env, 1e-6))
    gain_db = np.minimum(0.0, (threshold_db - env_db) * (1 - 1 / ratio)) + makeup_db
    gain = np.interp(np.arange(len(level)), np.arange(len(blocks)) * block + block / 2, 10 ** (gain_db / 20))
    return x * (gain[:, None] if x.ndim == 2 else gain)


def limit(x, ceiling_db=-1.0):
    """Ограничитель: быстрое поджатие пиков выше потолка, без обрезки."""
    ceiling = 10 ** (ceiling_db / 20)
    level = np.abs(x).max(axis=1) if x.ndim == 2 else np.abs(x)
    need = np.minimum(1.0, ceiling / np.maximum(level, 1e-9))
    # Упреждение 2 мс: усиление плавно опускается заранее и к пику уже достаточно мало.
    look = RATE // 500
    ahead = np.lib.stride_tricks.sliding_window_view(np.concatenate([need, np.ones(look)]), look + 1).min(axis=1)
    smooth = np.convolve(np.concatenate([np.ones(look), ahead]), np.ones(look + 1) / (look + 1), "valid")
    # Отпуск — 60 мс.
    pole = np.exp(-1 / (0.06 * RATE))
    released = signal.lfilter([1 - pole], [1, -pole], smooth - 1.0) + 1.0
    gain = np.minimum(smooth, released)
    return x * (gain[:, None] if x.ndim == 2 else gain)


def loop_cycle(audio, song, beats):
    """Средний из трёх кругов: бесшовная петля длиной ровно beats долей."""
    length = beats_to_samples(song, beats)
    return audio[length:2 * length]


def rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)


def normalize(x, target_db):
    return x * 10 ** ((target_db - rms_db(x)) / 20)


def trim_tail(x, threshold_db=-55.0, fade_ms=30):
    level = np.abs(x).max(axis=1) if x.ndim == 2 else np.abs(x)
    loud = np.flatnonzero(level > 10 ** (threshold_db / 20))
    end = min(len(x), (loud[-1] if loud.size else 0) + RATE // 50)
    out = x[:end].copy()
    fade = min(len(out), RATE * fade_ms // 1000)
    ramp = np.linspace(1.0, 0.0, fade)
    out[-fade:] *= ramp[:, None] if out.ndim == 2 else ramp
    return out


def save(audio, name, mono):
    if mono and audio.ndim == 2:
        audio = audio.mean(axis=1)
    audio = np.clip(audio, -1.0, 1.0)
    channels = 1 if audio.ndim == 1 else audio.shape[1]
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "out.wav"
        with wave.open(str(wav), "wb") as out:
            out.setnchannels(channels)
            out.setsampwidth(2)
            out.setframerate(RATE)
            out.writeframes((audio * 32767).astype(np.int16).tobytes())
        target = SOUNDS / f"{name}.ogg"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "6",
                        str(target)], check=True)
    peak = 20 * np.log10(np.abs(audio).max() + 1e-12)
    print(f"{target}: {len(audio) / RATE:.2f} с, {'моно' if channels == 1 else 'стерео'}, "
          f"RMS {rms_db(audio):.1f} дБ, пик {peak:.1f} дБ")


def guitar_tone(x, amount=2.2):
    """Ламповый перегруз сверху сэмпла и «кабинет» 4×12: без гула внизу и песка наверху."""
    x = highpass(x, 85)
    x = peaking(x, 700, 2.0, 0.9)
    x = drive(x * 2.0, amount)
    x = peaking(x, 1900, 3.0, 1.1)
    x = peaking(x, 380, -2.5, 1.2)
    return lowpass(x, 6800, order=4)


def bass_tone(x):
    """Круглый низ, рычание на середине и щелчок струны — «сочный» бас."""
    low = lowpass(x, 220, order=2)
    grit = drive(highpass(x, 220) * 3.0, 1.8) * 0.45
    x = low * 1.15 + highpass(x, 220) * 0.7 + grit
    x = peaking(x, 90, 3.0, 0.8)
    x = peaking(x, 850, 3.0, 1.0)
    x = peaking(x, 2600, 2.5, 1.4)
    x = lowpass(x, 7500, order=2)
    return compress(x, -20, 4.0, 4, 90, makeup_db=4.0)


# ---------------------------------------------------------------- партии

# Бас-рифф полёта: 4 такта по 16 шестнадцатых, 120 ударов в минуту, ми-минор.
# (шестнадцатая, длина в шестнадцатых, нота, сила). Конец переходит в начало.
BASS_RIFF = [
    [(0, 2, E1, 112), (2, 1, E1, 80), (3, 1, E2, 96), (5, 1, E1, 70), (6, 2, G1, 104), (8, 1, A1, 100),
     (9, 1, A1, 62), (10, 2, B1, 106), (12, 1, D2, 98), (13, 1, B1, 84), (14, 1, A1, 90), (15, 1, G1, 86)],
    [(0, 3, E1, 114), (3, 1, E1, 72), (4, 2, E2, 104), (6, 1, D2, 90), (7, 1, E2, 96), (8, 2, E1, 108),
     (10, 1, G1, 92), (11, 1, G1, 60), (12, 1, A1, 96), (13, 1, BB1, 88), (14, 2, B1, 104)],
    [(0, 2, A1, 112), (2, 1, A1, 80), (3, 1, A2, 96), (5, 1, A1, 70), (6, 2, C2, 104), (8, 1, D2, 100),
     (9, 1, D2, 62), (10, 2, E2, 106), (12, 1, G2, 98), (13, 1, E2, 84), (14, 1, D2, 90), (15, 1, C2, 86)],
    [(0, 2, G1, 110), (2, 1, G1, 78), (3, 1, A1, 92), (4, 2, A1, 104), (6, 1, BB1, 90), (7, 1, B1, 96),
     (8, 2, B1, 108), (10, 1, D2, 94), (11, 1, B1, 84), (12, 1, A1, 92), (13, 1, G1, 88), (14, 1, E1, 96),
     (15, 1, E2, 80)],
]
# Подъезды снизу на ноту: (такт, шестнадцатая).
BASS_SLIDES = {(1, 4), (2, 3)}


def flight(soundfont):
    song = Song(120)
    song.setup(0, FINGERED_BASS, volume=110, reverb=12)
    for cycle in range(3):
        for bar, notes in enumerate(BASS_RIFF):
            for step, length, pitch, velocity in notes:
                start = cycle * 16 + bar * 4 + step / 4
                gate = 0.82 if length == 1 else 0.92
                if (bar, step) in BASS_SLIDES:
                    song.glide(0, start, 0.12, -2.0, 0.0, steps=12)
                song.note(0, start, length / 4 * gate, pitch, velocity)
    audio = render(song, soundfont, 3 * 16 + 4)
    audio = bass_tone(audio.mean(axis=1))
    audio = loop_cycle(audio, song, 16)
    # Бас почти весь внизу, а на слух громкость меряется серединой — уровень выше, чем у гитар.
    save(limit(normalize(audio, -13.5), -1.0), "flight", mono=True)


# Рок серии побед: 150 ударов в минуту, 4 такта. Аккорды гитар: (шестнадцатая, длина, аккорд, сила).
# Короткие ноты — «чаги» с заглушенными струнами, длинные звенят.
ROCK_GUITAR = [
    [(0, 1, "E5", 118), (2, 1, "E5", 96), (4, 1, "E5", 100), (6, 1, "E5", 96), (8, 1, "E5", 104),
     (10, 1, "E5", 96), (12, 2, "G5", 120), (14, 2, "A5", 120)],
    [(0, 2, "E5", 118), (2, 1, "E5", 92), (3, 1, "E5", 92), (4, 1, "E5", 100), (6, 1, "E5", 92),
     (7, 1, "E5", 92), (8, 4, "D5", 122), (12, 2, "C5", 116), (14, 2, "D5", 118)],
    [(0, 1, "E5", 118), (2, 1, "E5", 92), (3, 1, "E5", 92), (4, 1, "E5", 100), (6, 1, "E5", 92),
     (7, 1, "E5", 92), (8, 1, "E5", 104), (10, 1, "E5", 92), (11, 1, "E5", 92), (12, 2, "G5", 120),
     (14, 2, "A5", 120)],
    [(0, 6, "C5", 124), (6, 6, "D5", 124), (12, 4, "E5", 124)],
]
# Барабаны: (шестнадцатая, нота, сила) по тактам.
BEAT = [(0, KICK, 120), (8, KICK, 112), (10, KICK, 104), (4, SNARE, 118), (12, SNARE, 120)]
HATS = [(step, HAT, 96 if step % 4 == 0 else 72) for step in range(0, 16, 2)]
FILL = [(0, KICK, 120), (4, SNARE, 118), (8, KICK, 112), (8, SNARE, 110), (10, TOM_HIGH, 112),
        (11, TOM_HIGH, 100), (12, TOM_MID, 114), (13, TOM_MID, 102), (14, TOM_LOW, 116), (15, TOM_FLOOR, 120)]


def rock_drums(song, start):
    for bar in range(4):
        hits = FILL + [h for h in HATS if h[0] < 8] if bar == 3 else BEAT + HATS
        for step, pitch, velocity in hits:
            if step == 0 and pitch == HAT and bar in (0, 2):
                pitch, velocity = (CRASH if bar == 0 else CRASH2), 118
            song.note(9, start + bar * 4 + step / 4, 0.2, pitch, velocity)


def combo_rock(soundfont):
    song = Song(150)
    song.setup(9, POWER_KIT, volume=112, reverb=30)
    # Две гитары по сторонам — как дубль в студии: чуть разный момент, сила и строй.
    song.setup(0, DISTORTION_GUITAR, volume=104, pan=8, reverb=22)
    song.setup(1, DISTORTION_GUITAR, volume=104, pan=120, reverb=22)
    song.setup(2, PICKED_BASS, volume=112, reverb=8)
    song.bend(1, 0.0, 0.06)
    rng = np.random.default_rng(1500)
    for cycle in range(3):
        rock_drums(song, cycle * 16)
        for bar, chords in enumerate(ROCK_GUITAR):
            for step, length, chord, velocity in chords:
                start = cycle * 16 + bar * 4 + step / 4
                gate = 0.72 if length == 1 else 0.96
                for channel in (0, 1):
                    shift = rng.uniform(0.0, 0.025) if channel else 0.0
                    for i, pitch in enumerate(POWER[chord]):
                        # Медиатор идёт по струнам сверху вниз: ноты аккорда на 6 мс друг за другом.
                        song.note(channel, start + shift + i * 0.015, length / 4 * gate - i * 0.015, pitch,
                                  velocity - rng.integers(0, 8))
                # Бас — прима аккорда восьмыми.
                for k in range(0, length, 2) if length > 1 else [0]:
                    song.note(2, start + k / 4, min(2, length) / 4 * 0.9, ROOT[chord], velocity - 6)
    audio = render(song, soundfont, 3 * 16 + 4)
    audio = loop_cycle(audio, song, 16)
    audio = peaking(highpass(audio, 35), 2500, 1.5, 1.0)
    audio = compress(audio, -16, 3.0, 8, 120, makeup_db=3.0)
    save(limit(normalize(audio, -14.0), -1.0), "combo_rock", mono=False)


# Удары: короткий «лязг» гитары с перегрузом. (аккорд, длительность в долях, подъезд вниз в конце).
STRIKES = [
    ((40, 47, 52), 0.55, False),
    ((43, 50, 55, 62), 0.5, True),
    ((45, 52, 57, 64), 0.55, False),
    ((52, 58, 64), 0.6, True),
]


def strikes(soundfont):
    for index, (chord, length, dive) in enumerate(STRIKES, start=1):
        song = Song(120)
        song.setup(0, DISTORTION_GUITAR, volume=120, reverb=18)
        for i, pitch in enumerate(chord):
            # Медиатор идёт по струнам: ноты аккорда на 6 мс друг за другом.
            song.note(0, i * 0.012, length - i * 0.012, pitch, 124 - i * 4)
        if dive:
            # Медиатор съезжает по струнам — металлический визг вниз.
            song.glide(0, length * 0.45, length * 0.55, 0.0, -2.0, steps=20)
        audio = render(song, soundfont, length + 1.0).mean(axis=1)
        audio = guitar_tone(audio, 2.6)
        audio = trim_tail(audio, -50.0, 40)
        save(limit(normalize(audio, -13.0), -1.0), f"strike{index}", mono=True)


def combo_lick(soundfont):
    """Визг соло-гитары: подтяжка на ми первой октавы и вибрато; выше — уже в игре."""
    song = Song(120)
    song.setup(0, DISTORTION_GUITAR, volume=120, reverb=30)
    song.glide(0, 0.0, 0.18, -2.0, 0.0, steps=16)
    for i in range(1, 30):
        t = 0.2 + i * 0.03
        song.bend(0, t, 0.35 * np.sin(2 * np.pi * 6.0 * (t - 0.2) * 60 / song.bpm))
    song.note(0, 0.0, 1.1, 64, 124)
    song.note(0, 0.0, 1.1, 76, 96)
    audio = render(song, soundfont, 2.2).mean(axis=1)
    audio = guitar_tone(audio, 2.4)
    audio = trim_tail(audio, -50.0, 80)
    save(limit(normalize(audio, -15.0), -1.0), "combo_lick", mono=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("soundfont", help="банк звуков, например MuseScore_General_Full.sf3")
    args = parser.parse_args()
    flight(args.soundfont)
    strikes(args.soundfont)
    combo_rock(args.soundfont)
    combo_lick(args.soundfont)


if __name__ == "__main__":
    sys.exit(main())
