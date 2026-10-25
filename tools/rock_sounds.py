#!/usr/bin/env python3
"""Рок-звуки стрелы: сочный бас в полёте, гитара на удар, рок и пауэр-метал на серии побед.

Партии записаны нотами прямо здесь, а играют их настоящие сэмплы инструментов из банка
MuseScore General (лицензия MIT; в Ubuntu — пакет musescore-general-soundfont) через FluidSynth:
бас-гитара, гитара с перегрузом и «мощная» рок-установка. Скрипт сводит их сам — подгружает
гитарам перегруза и «кабинет», басу рычания, — и пишет в ресурсы мода:
    flight.ogg            бас-рифф, бесшовная петля — тянется за стрелой в полёте;
    strike1..8.ogg        восемь гитарных приёмов на удар по мобу;
    combo_rock.ogg        драйвовый рок серии (барабаны, две гитары по сторонам, бас), петля;
    combo_rock_lead.ogg   две соло-гитары поверх рока с четвёртой победы, петля той же длины;
    combo_lick2..5.ogg    своя гитарная фраза на «двойную», «тройную», «четверную» и «мега-серию»;
    combo_epic.ogg        пауэр-метал на «неудержимую» серию: 180 ударов в минуту, хор, струнные,
                          две соло-гитары терциями, двойная бочка — петля на 27 секунд;
    combo_end.ogg         финальный аккорд, когда серия кончилась.
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


# ---------------------------------------------------------------- ноты и аккорды

NOTE_NAMES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
OVERDRIVE_GUITAR, GUITAR_HARMONICS = 29, 31
STRINGS, CHOIR, ORCHESTRA_HIT, TIMPANI = 48, 52, 55, 47
RIDE, CHINA = 51, 52
TOM_HIGH2, TOM_LOW2 = 48, 45
POWER.update({"B5": (47, 54, 59)})
ROOT.update({"B5": B1})
# Трезвучия под квинтаккордами — для хора, струнных и второго голоса соло.
TRIAD = {"E5": (4, 7, 11), "C5": (0, 4, 7), "D5": (2, 6, 9), "G5": (7, 11, 2), "A5": (9, 0, 4), "B5": (11, 3, 6)}
# Гармонический ми-минор — для неоклассических пробежек.
HARMONIC_MINOR = (4, 6, 7, 9, 11, 0, 3)


def n(name):
    """Нота по имени: «F#5» → 78."""
    return 12 * (int(name[-1]) + 1) + NOTE_NAMES[name[0]] + name.count("#")


def scale_run(start, count, step=1, scale=HARMONIC_MINOR):
    """Пробежка по ладу: count нот от start вверх (step=1) или вниз (step=-1)."""
    notes, pitch = [], start
    while len(notes) < count:
        if pitch % 12 in scale:
            notes.append(pitch)
        pitch += step
    return notes


def third_below(pitch, scale=HARMONIC_MINOR):
    """Терция вниз по ладу — второй голос для пробежек."""
    found, candidate = 0, pitch - 1
    while True:
        if candidate % 12 in scale:
            found += 1
            if found == 2:
                return candidate
        candidate -= 1


def chord_tone_below(pitch, chord):
    """Ближайший звук аккорда хотя бы на малую терцию ниже — второй голос, который не спорит с аккордом."""
    candidate = pitch - 3
    while candidate % 12 not in TRIAD[chord]:
        candidate -= 1
    return candidate


def triad_voicing(chord, low=55, high=76):
    return [p for p in range(low, high + 1) if p % 12 in TRIAD[chord]][:4]


def vibrato(song, channel, start, length, depth=0.35, rate=5.5, delay=0.25):
    """Вибрато соло-гитары: колесо высоты качается после небольшой задержки (в долях)."""
    seconds = 60.0 / song.bpm
    t = start + delay
    while t < start + length:
        song.bend(channel, t, depth * np.sin(2 * np.pi * rate * (t - start - delay) * seconds))
        t += 0.02 / seconds
    song.bend(channel, start + length, 0.0)


def lead_line(song, channel, notes, velocity=116, bend_in=False):
    """Мелодия соло-гитары: [(доля, длительность, нота)], легато и вибрато на длинных нотах."""
    for i, (start, length, pitch) in enumerate(notes):
        if bend_in and i == 0:
            song.glide(channel, start, 0.12, -1.0, 0.0, steps=8)
        song.note(channel, start, length * 0.97, pitch, velocity)
        if length >= 0.75:
            vibrato(song, channel, start, length * 0.97)


def lead_tone(x):
    """Соло-гитара: мягче ритма, с выпуклой серединой и без гула."""
    x = highpass(x, 180)
    x = drive(x * 1.6, 1.6)
    x = peaking(x, 2400, 2.5, 1.0)
    return lowpass(x, 7800, order=2)


def render_stems(song, soundfont, end, stems):
    """Рендер по группам каналов, чтобы обработать каждую отдельно: {имя: каналы}."""
    out = {}
    for name, channels in stems.items():
        part = Song(song.bpm)
        part.events = [e for e in song.events if e[2][0] & 0x0F in channels]
        out[name] = render(part, soundfont, end)
    length = max(len(a) for a in out.values())
    return {k: np.pad(v, ((0, length - len(v)), (0, 0))) for k, v in out.items()}


def master(audio, target_db=-13.5):
    audio = highpass(audio, 32)
    audio = compress(audio, -15, 3.0, 6, 140, makeup_db=3.0)
    return limit(normalize(audio, target_db), -1.0)


# ---------------------------------------------------------------- удары

def strike_song():
    song = Song(120)
    song.setup(0, DISTORTION_GUITAR, volume=122, reverb=24)
    song.setup(1, GUITAR_HARMONICS, volume=110, reverb=30)
    return song


def strum(song, start, length, chord, velocity=124, channel=0):
    for i, pitch in enumerate(chord):
        # Медиатор идёт по струнам: ноты аккорда на 6 мс друг за другом.
        song.note(channel, start + i * 0.012, max(0.03, length - i * 0.012), pitch, velocity - i * 4)


def strike_parts():
    """Восемь разных гитарных приёмов на удар: стаб, слайд, двойной удар, высокий аккорд, визг,
    «чугга», диссонанс и тремоло. Доли — при 120 ударах в минуту."""
    parts = []
    song = strike_song()
    strum(song, 0, 0.6, POWER["E5"])
    parts.append(song)
    song = strike_song()
    strum(song, 0, 0.55, POWER["A5"])
    song.glide(0, 0.3, 0.3, 0.0, -2.0, steps=20)
    parts.append(song)
    song = strike_song()
    strum(song, 0, 0.22, POWER["G5"])
    strum(song, 0.28, 0.5, POWER["A5"])
    parts.append(song)
    song = strike_song()
    strum(song, 0, 0.55, (62, 69, 74, 78))
    parts.append(song)
    song = strike_song()
    song.glide(0, 0.0, 0.1, -1.0, 0.6, steps=8)
    vibrato(song, 0, 0.1, 0.7, depth=0.45, rate=7.0, delay=0.05)
    song.note(0, 0, 0.8, n("E5"), 120)
    song.note(1, 0, 0.8, n("B5"), 110)
    parts.append(song)
    song = strike_song()
    for k in range(3):
        strum(song, k * 0.17, 0.09, POWER["E5"], 112)
    strum(song, 0.51, 0.45, POWER["E5"])
    parts.append(song)
    song = strike_song()
    strum(song, 0, 0.18, POWER["B5"])
    strum(song, 0.22, 0.5, POWER["C5"])
    parts.append(song)
    song = strike_song()
    for k in range(8):
        strum(song, k * 0.0625, 0.05, (n("E3"), n("E4")), 112 + (k % 2) * 8)
    strum(song, 0.5, 0.35, (n("E3"), n("B3"), n("E4")))
    parts.append(song)
    return parts


def strikes(soundfont):
    for index, song in enumerate(strike_parts(), start=1):
        audio = render(song, soundfont, 2.0).mean(axis=1)
        audio = guitar_tone(audio, 2.4)
        audio = peaking(audio, 3200, 2.0, 1.2)
        audio = trim_tail(audio, -48.0, 50)
        save(limit(normalize(audio, -12.0), -1.0), f"strike{index}", mono=True)


# ---------------------------------------------------------------- серия: фразы гитары

def lick_parts():
    """Своя фраза соло-гитары на каждую ступень серии (150 ударов в минуту)."""
    licks = {}
    # ДВОЙНАЯ: пробежка по пентатонике и подтяжка ре → ми с вибрато.
    song = Song(150)
    song.setup(0, OVERDRIVE_GUITAR, volume=122, reverb=30)
    for k, name in enumerate(["E4", "G4", "A4", "B4", "D5"]):
        song.note(0, k * 0.25, 0.24, n(name), 116)
    song.glide(0, 1.25, 0.12, -2.0, 0.0, steps=10)
    song.note(0, 1.25, 1.3, n("E5"), 122)
    vibrato(song, 0, 1.3, 1.25)
    licks[2] = song
    # ТРОЙНАЯ: слайд вниз по струнам и удар октавным аккордом.
    song = Song(150)
    song.setup(0, DISTORTION_GUITAR, volume=122, reverb=30)
    song.glide(0, 0.0, 0.4, 2.0, -2.0, steps=24)
    strum(song, 0.0, 0.4, (n("E4"), n("B4"), n("E5")), 100)
    song.bend(0, 0.45, 0.0)
    strum(song, 0.5, 1.4, (n("E3"), n("B3"), n("E4"), n("G4")), 124)
    vibrato(song, 0, 0.6, 1.3, depth=0.25)
    licks[3] = song
    # ЧЕТВЕРНАЯ: тэппинг — ми-минорное арпеджио вверх-вниз и долгая верхняя нота.
    song = Song(150)
    song.setup(0, OVERDRIVE_GUITAR, volume=122, reverb=32)
    arpeggio = ["E5", "G5", "B5", "E6", "B5", "G5", "E5", "G5", "B5", "E6", "G6"]
    for k, name in enumerate(arpeggio):
        song.note(0, k / 6, 0.17, n(name), 112 + (k % 3) * 5)
    song.note(0, len(arpeggio) / 6, 1.0, n("E6"), 120)
    vibrato(song, 0, len(arpeggio) / 6, 1.0, depth=0.4)
    licks[4] = song
    # МЕГА-СЕРИЯ: двойная гармония — две гитары терциями вниз и вибрато на финальной ноте.
    song = Song(150)
    song.setup(0, OVERDRIVE_GUITAR, volume=120, pan=40, reverb=32)
    song.setup(1, OVERDRIVE_GUITAR, volume=114, pan=88, reverb=32)
    run = scale_run(n("B5"), 7, step=-1)
    for k, pitch in enumerate(run):
        song.note(0, k * 0.25, 0.24, pitch, 116)
        song.note(1, k * 0.25 + 0.008, 0.24, third_below(pitch), 110)
    song.note(0, 1.75, 1.2, n("E5"), 122)
    song.note(1, 1.758, 1.2, n("B4"), 116)
    vibrato(song, 0, 1.75, 1.2)
    vibrato(song, 1, 1.75, 1.2, rate=5.0)
    licks[5] = song
    return licks


def combo_licks(soundfont):
    for level, song in lick_parts().items():
        audio = render(song, soundfont, 5.0)
        audio = lead_tone(audio)
        audio = trim_tail(audio, -50.0, 80)
        save(limit(normalize(audio, -14.0), -1.0), f"combo_lick{level}", mono=False)


# ---------------------------------------------------------------- серия: вторая гитара в рок

# Мелодия двух соло-гитар поверх рока серии (те же 4 такта, 150 ударов в минуту):
# (доля в петле, длительность, нота, аккорд под ней).
ROCK_LEAD = [
    (0, 1, "E5", "E5"), (1, 1, "G5", "E5"), (2, 1, "B5", "E5"), (3, 0.5, "A5", "G5"), (3.5, 0.5, "G5", "A5"),
    (4, 1, "G5", "E5"), (5, 1, "E5", "E5"), (6, 1, "F#5", "D5"), (7, 0.5, "E5", "C5"), (7.5, 0.5, "D5", "D5"),
    (8, 1, "E5", "E5"), (9, 1, "G5", "E5"), (10, 1, "B5", "E5"), (11, 0.5, "D6", "G5"), (11.5, 0.5, "B5", "A5"),
    (12, 1.5, "C6", "C5"), (13.5, 1.5, "A5", "D5"), (15, 1, "B5", "E5"),
]


def combo_rock_lead(soundfont):
    song = Song(150)
    song.setup(2, OVERDRIVE_GUITAR, volume=118, pan=36, reverb=34)
    song.setup(3, OVERDRIVE_GUITAR, volume=110, pan=92, reverb=34)
    for cycle in range(3):
        offset = cycle * 16
        lead_line(song, 2, [(offset + s, d, n(p)) for s, d, p, _ in ROCK_LEAD], 116)
        lead_line(song, 3, [(offset + s + 0.01, d, chord_tone_below(n(p), c)) for s, d, p, c in ROCK_LEAD], 108)
    audio = render(song, soundfont, 3 * 16 + 4)
    audio = loop_cycle(lead_tone(audio), song, 16)
    save(limit(normalize(audio, -16.5), -1.0), "combo_rock_lead", mono=False)


# ---------------------------------------------------------------- серия: пауэр-метал

EPIC_BPM = 180
# 20 тактов: вступление (2), куплет-галоп (8), припев (8), сбивка (2). Аккорд на такт;
# пара — по половине такта.
EPIC_CHORDS = (["E5", "E5"]
               + ["E5", "E5", "C5", "D5", "E5", "E5", ("C5", "D5"), "B5"]
               + ["E5", "C5", "G5", "D5", "E5", "C5", "D5", "B5"]
               + ["E5", "B5"])
VERSE_MELODY = [  # такт куплета (2–9), доля, длительность, нота
    (2, 0, 2, "B4"), (2, 2, 1, "E5"), (2, 3, 1, "D5"), (3, 0, 3, "B4"), (3, 3, 1, "A4"),
    (4, 0, 1, "G4"), (4, 1, 1, "A4"), (4, 2, 2, "C5"), (5, 0, 1, "B4"), (5, 1, 1, "A4"), (5, 2, 2, "F#4"),
    (6, 0, 1, "G4"), (6, 1, 1, "B4"), (6, 2, 2, "E5"), (7, 0, 2, "F#5"), (7, 2, 1, "E5"), (7, 3, 1, "D5"),
    (8, 0, 1, "C5"), (8, 1, 1, "E5"), (8, 2, 1, "D5"), (8, 3, 1, "F#5"), (9, 0, 2, "D#5"), (9, 2, 1, "F#5"),
    (9, 3, 1, "B5"),
]
CHORUS_MELODY = [  # такт припева (10–17)
    (10, 0, 2, "G5"), (10, 2, 1, "F#5"), (10, 3, 1, "E5"), (11, 0, 2, "E5"), (11, 2, 2, "G5"),
    (12, 0, 3, "B5"), (12, 3, 1, "A5"), (13, 0, 4, "F#5"), (14, 0, 1, "G5"), (14, 1, 1, "A5"), (14, 2, 2, "B5"),
    (15, 0, 2, "C6"), (15, 2, 1, "B5"), (15, 3, 1, "A5"), (16, 0, 2, "A5"), (16, 2, 2, "F#5"),
    (17, 0, 1, "D#5"), (17, 1, 1, "F#5"), (17, 2, 2, "B5"),
]


def epic_chord(bar, beat):
    chord = EPIC_CHORDS[bar]
    return chord if isinstance(chord, str) else chord[0 if beat < 2 else 1]


def epic_drums(song, start):
    def hit(bar, beat, pitch, velocity, length=0.15):
        song.note(9, start + bar * 4 + beat, length, pitch, velocity)

    # Вступление: удар, дробь малого с нарастанием; второй такт — двойная бочка.
    hit(0, 0, CRASH, 127)
    hit(0, 0, KICK, 127)
    hit(0, 0, CRASH2, 120)
    for k in range(8):
        hit(0, 2 + k * 0.25, SNARE, 70 + k * 7)
    for bar in range(1, 20):
        for k in range(16):
            if bar == 19 and k >= 8:
                break
            hit(bar, k * 0.25, KICK, 112 if k % 2 == 0 else 98)
        if bar != 19:
            hit(bar, 1, SNARE, 122)
            hit(bar, 3, SNARE, 124)
        if 2 <= bar <= 9:
            for k in range(8):
                hit(bar, k * 0.5, RIDE, 96 if k % 2 == 0 else 80)
            if bar in (2, 6):
                hit(bar, 0, CRASH, 120)
        if 10 <= bar <= 17 or bar in (1, 18):
            hit(bar, 0, CRASH if bar % 2 == 0 else CRASH2, 124)
            if 10 <= bar <= 17:
                hit(bar, 2, CHINA, 110)
                for k in range(8):
                    hit(bar, k * 0.5, HAT, 70)
    # Сбивки томами в конце куплета и в последнем такте.
    for k, tom in enumerate([TOM_HIGH, TOM_HIGH, TOM_HIGH2, TOM_MID]):
        hit(9, 3 + k * 0.25, tom, 116)
    hit(19, 0, CRASH, 126)
    for k, tom in enumerate([TOM_HIGH, TOM_HIGH, TOM_HIGH2, TOM_HIGH2, TOM_MID, TOM_MID, TOM_LOW2, TOM_FLOOR]):
        hit(19, 2 + k * 0.25, tom, 108 + k * 2)
        hit(19, 2 + k * 0.25, KICK, 104)


def epic_guitars_and_bass(song, start, rng):
    def chord_at(bar, beat, length, chord, velocity, gate):
        for channel in (0, 1):
            shift = rng.uniform(0.0, 0.02) if channel else 0.0
            for i, pitch in enumerate(POWER[chord]):
                song.note(channel, start + bar * 4 + beat + shift + i * 0.015,
                          max(0.05, length * gate - i * 0.015), pitch, velocity - int(rng.integers(0, 6)))

    def bass_at(bar, beat, length, pitch, velocity, gate=0.85):
        song.note(4, start + bar * 4 + beat, length * gate, pitch, velocity)

    def run(bar, notes):
        for k, pitch in enumerate(notes):
            t = bar * 4 + k * 0.25
            for channel in (0, 1):
                song.note(channel, start + t + (0.01 if channel else 0.0), 0.23, pitch - 12, 116)
            song.note(2, start + t, 0.24, pitch, 118)
            song.note(3, start + t + 0.008, 0.24, third_below(pitch), 110)
            bass_at(bar, k * 0.25, 0.25, pitch - 24, 112, 0.9)

    # Вступление: аккорд звенит, потом неоклассическая пробежка вверх.
    chord_at(0, 0, 2, "E5", 126, 0.97)
    bass_at(0, 0, 2, E1, 120, 0.95)
    run(1, scale_run(n("E3"), 16))
    # Куплет: галоп — восьмая и две шестнадцатых на каждую долю, струны приглушены.
    for bar in range(2, 10):
        for beat in range(4):
            chord = epic_chord(bar, beat)
            for offset, length in ((0, 0.5), (0.5, 0.25), (0.75, 0.25)):
                accent = 124 if beat == 0 and offset == 0 else 104
                chord_at(bar, beat + offset, length, chord, accent, 0.6)
                bass_at(bar, beat + offset, length, ROOT[chord], accent - 4, 0.75)
    # Припев: аккорд звенит на первую долю, дальше прямые восьмые.
    for bar in range(10, 18):
        chord = epic_chord(bar, 0)
        chord_at(bar, 0, 1, chord, 124, 0.95)
        bass_at(bar, 0, 1, ROOT[chord], 120)
        for k in range(2, 8):
            chord_at(bar, k * 0.5, 0.5, chord, 102, 0.8)
            bass_at(bar, k * 0.5, 0.5, ROOT[chord], 106)
    # Сбивка: пробежка вниз и вверх, затем си-аккорд перед возвратом к началу.
    run(18, scale_run(n("E5"), 8, step=-1) + scale_run(n("E4"), 8))
    chord_at(19, 0, 2, "B5", 126, 0.97)
    bass_at(19, 0, 2, B1, 120, 0.95)
    for k in range(4):
        chord_at(19, 2 + k * 0.5, 0.5, "B5", 110, 0.7)
        bass_at(19, 2 + k * 0.5, 0.5, B1, 110)


def epic_leads(song, start):
    lead_line(song, 2, [(start + bar * 4 + beat, length, n(name)) for bar, beat, length, name in VERSE_MELODY],
              116, bend_in=True)
    chorus = [(start + bar * 4 + beat, length, n(name)) for bar, beat, length, name in CHORUS_MELODY]
    lead_line(song, 2, chorus, 120, bend_in=True)
    harmony = [(t + 0.01, length, chord_tone_below(pitch, epic_chord(bar, beat)))
               for (t, length, pitch), (bar, beat, _, _) in zip(chorus, CHORUS_MELODY)]
    lead_line(song, 3, harmony, 112)
    # Последний такт: соло тянет си, как гитара на обратной связи.
    song.note(2, start + 19 * 4, 3.9, n("B5"), 118)
    vibrato(song, 2, start + 19 * 4, 3.9, depth=0.5, rate=6.0, delay=0.4)
    song.note(3, start + 19 * 4 + 0.01, 3.9, n("F#5"), 110)


def epic_orchestra(song, start):
    def pad(bar, beat, length, chord, choir=True, strings=True, velocity=92):
        for pitch in triad_voicing(chord):
            if choir:
                song.note(5, start + bar * 4 + beat, length * 0.98, pitch, velocity)
            if strings:
                song.note(6, start + bar * 4 + beat, length * 0.98, pitch - 12, velocity - 10)

    pad(0, 0, 2, "E5")
    for bar in range(2, 10):
        for beat in (0, 2) if isinstance(EPIC_CHORDS[bar], tuple) else (0,):
            length = 2 if isinstance(EPIC_CHORDS[bar], tuple) else 4
            pad(bar, beat, length, epic_chord(bar, beat), choir=False, velocity=70)
    for bar in range(10, 18):
        pad(bar, 0, 4, epic_chord(bar, 0), velocity=96)
    pad(19, 0, 4, "B5", choir=False, velocity=90)
    for bar, chord in ((0, "E5"), (10, "E5"), (19, "B5")):
        for pitch in triad_voicing(chord, 60, 76):
            song.note(7, start + bar * 4, 0.5, pitch, 120)
        song.note(8, start + bar * 4, 1.0, ROOT[chord] + 12, 124)


def combo_epic(soundfont):
    """Пауэр-метал на «неудержимую» серию: 180 ударов в минуту, 20 тактов, бесшовная петля."""
    song = Song(EPIC_BPM)
    song.setup(0, DISTORTION_GUITAR, volume=106, pan=6, reverb=20)
    song.setup(1, DISTORTION_GUITAR, volume=106, pan=122, reverb=20)
    song.setup(2, OVERDRIVE_GUITAR, volume=118, pan=44, reverb=40)
    song.setup(3, OVERDRIVE_GUITAR, volume=110, pan=84, reverb=40)
    song.setup(4, PICKED_BASS, volume=114, reverb=6)
    song.setup(5, CHOIR, volume=100, pan=64, reverb=70)
    song.setup(6, STRINGS, volume=96, pan=64, reverb=60)
    song.setup(7, ORCHESTRA_HIT, volume=110, reverb=50)
    song.setup(8, TIMPANI, volume=120, reverb=50)
    song.setup(9, POWER_KIT, volume=116, reverb=24)
    song.bend(1, 0.0, 0.06)
    rng = np.random.default_rng(180)
    bars = len(EPIC_CHORDS)
    for cycle in range(3):
        start = cycle * bars * 4
        epic_drums(song, start)
        epic_guitars_and_bass(song, start, rng)
        epic_leads(song, start)
        epic_orchestra(song, start)
    stems = render_stems(song, soundfont, 3 * bars * 4 + 4, {
        "drums": {9}, "rhythm": {0, 1}, "leads": {2, 3}, "bass": {4}, "pads": {5, 6}, "hits": {7, 8}})
    bass = bass_tone(stems["bass"].mean(axis=1))[:, None].repeat(2, axis=1)
    drums = compress(peaking(peaking(stems["drums"], 70, 2.5, 0.9), 4200, 2.0, 1.0), -18, 3.0, 3, 80, 3.0)
    pads = lowpass(highpass(stems["pads"], 170), 8000)
    mix = (drums * 1.0 + guitar_tone(stems["rhythm"], 2.0) * 0.85 + lead_tone(stems["leads"]) * 0.8
           + bass * 0.75 + pads * 0.6 + stems["hits"] * 0.6)
    audio = loop_cycle(mix, song, bars * 4)
    save(master(audio, -13.0), "combo_epic", mono=False)


def combo_end(soundfont):
    """Финальный аккорд, когда серия кончилась: всё вместе бьёт ми, звенит и затихает."""
    song = Song(60)
    song.setup(0, DISTORTION_GUITAR, volume=110, pan=10, reverb=40)
    song.setup(1, DISTORTION_GUITAR, volume=110, pan=118, reverb=40)
    song.setup(2, OVERDRIVE_GUITAR, volume=116, reverb=50)
    song.setup(4, PICKED_BASS, volume=114, reverb=10)
    song.setup(5, CHOIR, volume=96, reverb=80)
    song.setup(7, ORCHESTRA_HIT, volume=110, reverb=60)
    song.setup(9, POWER_KIT, volume=118, reverb=40)
    for channel in (0, 1):
        strum(song, 0.005 * channel, 2.3, (n("E2"), n("B2"), n("E3"), n("G3"), n("B3")), 126, channel)
    song.note(2, 0.0, 2.2, n("E5"), 118)
    vibrato(song, 2, 0.0, 2.2, depth=0.45, rate=6.0, delay=0.5)
    song.note(4, 0.0, 2.3, E1, 124)
    for pitch in triad_voicing("E5", 59, 76):
        song.note(5, 0.0, 2.0, pitch, 92)
        song.note(7, 0.0, 0.6, pitch, 118)
    for pitch in (CRASH, CRASH2, KICK, SNARE):
        song.note(9, 0.0, 0.3, pitch, 127)
    stems = render_stems(song, soundfont, 4.0, {"guitars": {0, 1}, "lead": {2}, "rest": {4, 5, 7, 9}})
    mix = guitar_tone(stems["guitars"], 2.0) * 0.9 + lead_tone(stems["lead"]) * 0.7 + stems["rest"]
    fade = np.clip((3.2 - np.arange(len(mix)) / RATE) / 1.0, 0.0, 1.0)[:, None]
    audio = trim_tail(mix * fade, -55.0, 100)
    save(limit(normalize(audio, -14.0), -1.0), "combo_end", mono=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("soundfont", help="банк звуков, например MuseScore_General_Full.sf3")
    parser.add_argument("--only", help="собрать только эти звуки через запятую (flight, strikes, rock, lead, "
                                       "licks, epic, end)")
    args = parser.parse_args()
    parts = {"flight": flight, "strikes": strikes, "rock": combo_rock, "lead": combo_rock_lead,
             "licks": combo_licks, "epic": combo_epic, "end": combo_end}
    for name in (args.only.split(",") if args.only else parts):
        parts[name](args.soundfont)


if __name__ == "__main__":
    sys.exit(main())
