#!/usr/bin/env python3
"""Монтаж апдейт-ролика «Стрела Андрюшки» 1.3.0: «как было — как стало», около двух минут.

Берёт дубли из игры — видео без звука и две дорожки к нему, со звуками 1.3.0 и 1.2.1 (см.
tools/demo/update-takes.txt и sound_track.py --before), — закадровый голос (narration.py по
narration.txt из этой папки) и собирает ролик в духе блогеров: заставка под рок, титры разделов,
«БЫЛО» и «СТАЛО» на одних и тех же кадрах, удары камеры в такт и на каждый удар гитары, вспышки,
глитч-переходы, субтитры и эквалайзер. Запуск:
    montage.py ПАПКА-ДУБЛЕЙ ПАПКА-ГОЛОСА ролик.mp4
В папке дублей — raw-video.mp4, raw-sfx.wav, raw-sfx-before.wav, raw-sounds.txt, raw-markers.txt и
raw-dilation.txt (их кладёт в релиз «update» workflow update-video), в папке голоса —
narration.wav и narration-timing.txt. Нужны numpy, scipy, Pillow, opencv-python и ffmpeg.
"""
import argparse
import bisect
import math
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import signal

W, H, FPS = 1280, 720, 60
SR = 48000
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
FONT = HERE / "RussoOne-Regular.ttf"
LINES = HERE / "narration.txt"
TAKES = ROOT / "tools/demo/update-takes.txt"
ROCK_LOOP = ROOT / "src/main/resources/assets/andryushka_arrow/sounds/combo_rock.ogg"
# Темп партий (tools/rock_sounds.py): бас полёта — 120 ударов в минуту, рок серии — 150.
BASS_BEAT = 0.5
ROCK_BEAT = 0.4
# Где в кадре дубля с ударами стоит танк (камера там неподвижна).
HITS_FOCUS = (770, 300)
LOUDNESS = -15.0
# Громкость звуков игры в сценах сравнения (LUFS) — до общей нормализации ролика.
GAME_LEVEL = -18.0
VOICE_GAIN = 1.45

# Цвета — RGB.
FIRE = ((255, 226, 90), (255, 84, 36))
GOLD = ((255, 240, 160), (255, 170, 40))
WHITE = (255, 255, 255)
INK = (18, 12, 24)
OLD = (168, 174, 184)
RED = (226, 44, 52)


# ---------------------------------------------------------------- данные

def cue_times(path):
    """Метки файла «секунда метка текст» → {метка: (секунда, текст)}."""
    cues = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            start, cue, text = line.split(maxsplit=2)
            cues[cue] = (float(start), text)
    return cues


class SoundLog:
    """Когда в дублях прозвучал какой звук — во времени ролика дублей."""

    def __init__(self, raw):
        marks = dict(line.split() for line in (raw / "raw-markers.txt").read_text().splitlines() if line.strip())
        start = int(marks["start"])
        dilation = float((raw / "raw-dilation.txt").read_text())
        self.events = []
        for line in (raw / "raw-sounds.txt").read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if parts and parts[0] == "sound":
                self.events.append(((int(parts[1]) - start) / 1000.0 / dilation, parts[2], float(parts[4])))

    def times(self, needle, after, before):
        return [t for t, location, _ in self.events if needle in location and after <= t < before]


def first(values, default):
    return values[0] if values else default


def read_audio(path, channels=2):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ac", str(channels),
                          "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).reshape(-1, channels).astype(np.float64)


# ---------------------------------------------------------------- картинка

def font(size):
    return ImageFont.truetype(str(FONT), size)


def to_bgra(image):
    rgba = np.array(image)
    return np.dstack([rgba[..., 2], rgba[..., 1], rgba[..., 0], rgba[..., 3]])


def gradient(width, height, top, bottom):
    ramp = np.linspace(0.0, 1.0, height)[:, None, None]
    colors = np.array(top)[None, None, :] * (1 - ramp) + np.array(bottom)[None, None, :] * ramp
    rgb = np.repeat(colors, width, axis=1).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb, np.full((height, width), 255, np.uint8)]), "RGBA")


def text_sprite(text, size, fill=WHITE, colors=None, stroke=0, stroke_fill=INK, shadow=0):
    """Надпись RGBA: заливка цветом или градиентом сверху вниз, обводка и тень."""
    face = font(size)
    left, top, right, bottom = face.getbbox(text, stroke_width=stroke)
    pad = 4 + shadow
    width, height = right - left + 2 * pad, bottom - top + 2 * pad
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    origin = (pad - left, pad - top)
    draw = ImageDraw.Draw(image)
    if shadow:
        draw.text((origin[0] + shadow, origin[1] + shadow), text, font=face, fill=(0, 0, 0, 150),
                  stroke_width=stroke, stroke_fill=(0, 0, 0, 150))
    if colors:
        draw.text(origin, text, font=face, fill=stroke_fill, stroke_width=stroke, stroke_fill=stroke_fill)
        mask = Image.new("L", (width, height), 0)
        ImageDraw.Draw(mask).text(origin, text, font=face, fill=255)
        image.paste(gradient(width, height, *colors), (0, 0), mask)
    else:
        draw.text(origin, text, font=face, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)
    return to_bgra(image)


def pill_sprite(text, size, background, color=WHITE, small=None, small_color=WHITE):
    """Плашка со скруглёнными краями: крупная надпись и, по желанию, мелкая справа."""
    big, little = font(size), font(int(size * 0.62))
    _, top, width, bottom = big.getbbox(text)
    small_width = big.getlength(" ") + little.getlength(small) if small else 0
    pad_x, pad_y = int(size * 0.55), int(size * 0.32)
    image = Image.new("RGBA", (int(width + small_width) + 2 * pad_x, bottom - top + 2 * pad_y), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([0, 0, image.width - 1, image.height - 1], radius=image.height // 2, fill=background)
    draw.text((pad_x, pad_y - top), text, font=big, fill=color)
    if small:
        baseline = pad_y - top + big.getmetrics()[0]
        draw.text((pad_x + width + big.getlength(" "), baseline - little.getmetrics()[0]), small, font=little,
                  fill=small_color)
    return to_bgra(image)


def caption_sprite(text, size=34, width=1000):
    """Субтитр: белый с чёрной обводкой, переносится на две строки."""
    face = font(size)
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if face.getlength(candidate) > width and line:
            lines.append(line)
            line = word
        else:
            line = candidate
    lines.append(line)
    sprites = [text_sprite(part, size, stroke=4, shadow=3) for part in lines]
    out = np.zeros((sum(s.shape[0] for s in sprites) - 6 * (len(sprites) - 1), max(s.shape[1] for s in sprites), 4),
                   np.uint8)
    y = 0
    for sprite in sprites:
        x = (out.shape[1] - sprite.shape[1]) // 2
        region = out[y:y + sprite.shape[0], x:x + sprite.shape[1]]
        np.maximum(region, sprite, out=region)
        y += sprite.shape[0] - 6
    return out


def check_sprite(text, size):
    """Пункт итогов: зелёная галочка в кружке и надпись."""
    label = text_sprite(text, size, stroke=4, shadow=3)
    box = int(size * 1.25)
    image = Image.new("RGBA", (box, box), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse([2, 2, box - 3, box - 3], fill=(60, 200, 90, 255), outline=(10, 60, 20, 255), width=3)
    draw.line([(box * 0.27, box * 0.52), (box * 0.44, box * 0.70), (box * 0.75, box * 0.33)], fill=WHITE,
              width=max(4, box // 9), joint="curve")
    icon = to_bgra(image)
    out = np.zeros((max(box, label.shape[0]), box + 16 + label.shape[1], 4), np.uint8)
    out[(out.shape[0] - box) // 2:(out.shape[0] - box) // 2 + box, :box] = icon
    y = (out.shape[0] - label.shape[0]) // 2
    out[y:y + label.shape[0], box + 16:] = label
    return out


def blend(frame, sprite, cx, cy, scale=1.0, alpha=1.0):
    """Наложить RGBA-спрайт центром в (cx, cy) с масштабом и прозрачностью."""
    if alpha <= 0.004 or scale <= 0.01:
        return
    if abs(scale - 1.0) > 1e-3:
        sprite = cv2.resize(sprite, None, fx=scale, fy=scale, interpolation=cv2.INTER_LINEAR)
    h, w = sprite.shape[:2]
    x0, y0 = int(round(cx - w / 2)), int(round(cy - h / 2))
    fx0, fy0, fx1, fy1 = max(0, x0), max(0, y0), min(W, x0 + w), min(H, y0 + h)
    if fx1 <= fx0 or fy1 <= fy0:
        return
    part = sprite[fy0 - y0:fy1 - y0, fx0 - x0:fx1 - x0]
    a = part[..., 3:4].astype(np.float32) * (alpha / 255.0)
    region = frame[fy0:fy1, fx0:fx1].astype(np.float32)
    frame[fy0:fy1, fx0:fx1] = (region * (1 - a) + part[..., :3].astype(np.float32) * a).astype(np.uint8)


def ease_out_back(x):
    x = min(1.0, max(0.0, x))
    c = 1.9
    return 1 + (c + 1) * (x - 1) ** 3 + c * (x - 1) ** 2


def ease_out(x):
    x = min(1.0, max(0.0, x))
    return 1 - (1 - x) ** 3


def slam(t, start, length=0.28, peak=2.3):
    """Надпись влетает из крупного плана с отскоком: (масштаб, прозрачность)."""
    x = (t - start) / length
    if x < 0:
        return 0.0, 0.0
    return peak + (1.0 - peak) * ease_out_back(x), min(1.0, x * 2.5)


def vignette(strength):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    r = ((x - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2
    return np.clip(1.0 - strength * r ** 1.2, 0.0, 1.0)[..., None]


VIGNETTE_NEW = vignette(0.22)
VIGNETTE_OLD = vignette(0.45)


def channel_lut(contrast, gains, lift=0.0):
    x = np.arange(256, dtype=np.float32) / 255.0
    curve = 0.5 + (x - 0.5) * contrast + lift
    # Мягкая S-кривая поверх контраста.
    curve = curve + 0.06 * np.sin((curve - 0.5) * np.pi) * (contrast - 1.0) * 4
    luts = [np.clip(curve * g * 255.0, 0, 255).astype(np.uint8) for g in gains]
    return np.dstack(luts).reshape(1, 256, 3)


LUT_NEW = channel_lut(1.12, (0.94, 1.0, 1.06))      # BGR: теплее
LUT_OLD = channel_lut(0.86, (1.05, 1.0, 0.94), 0.03)  # холоднее и тусклее
SATURATION_NEW = np.clip(np.arange(256) * 1.3, 0, 255).astype(np.uint8)
GRAIN = [np.random.default_rng(i).normal(0, 9, (H, W, 1)).astype(np.int16) for i in range(6)]


def grade(frame, mode, n):
    if mode == "new":
        hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
        hsv[..., 1] = SATURATION_NEW[hsv[..., 1]]
        frame = cv2.LUT(cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR), LUT_NEW)
        return (frame * VIGNETTE_NEW).astype(np.uint8)
    if mode == "old":
        gray = cv2.cvtColor(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), cv2.COLOR_GRAY2BGR)
        frame = cv2.LUT(cv2.addWeighted(frame, 0.35, gray, 0.65, 0), LUT_OLD)
        frame = np.clip(frame.astype(np.int16) + GRAIN[n % len(GRAIN)], 0, 255).astype(np.uint8)
        return (frame * VIGNETTE_OLD).astype(np.uint8)
    return frame


def move(frame, zoom=1.0, dx=0.0, dy=0.0, angle=0.0, center=(W / 2, H / 2)):
    if abs(zoom - 1.0) < 1e-4 and abs(dx) < 0.05 and abs(dy) < 0.05 and abs(angle) < 0.01:
        return frame
    matrix = cv2.getRotationMatrix2D(center, angle, zoom)
    matrix[0, 2] += dx
    matrix[1, 2] += dy
    return cv2.warpAffine(frame, matrix, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)


def flash(frame, alpha, color=(255, 255, 255)):
    if alpha <= 0.004:
        return frame
    overlay = np.empty_like(frame)
    overlay[:] = color[::-1]
    return cv2.addWeighted(frame, 1 - alpha, overlay, alpha, 0)


def glitch(frame, strength, seed):
    """Цифровой сбой: цветовые каналы разъезжаются, полосы кадра сдвигаются."""
    if strength <= 0.01:
        return frame
    rng = np.random.default_rng(seed)
    shift = int(16 * strength)
    out = frame.copy()
    out[..., 0] = np.roll(frame[..., 0], shift, axis=1)
    out[..., 2] = np.roll(frame[..., 2], -shift, axis=1)
    for _ in range(int(7 * strength) + 1):
        y = rng.integers(0, H - 20)
        h = rng.integers(8, 60)
        out[y:y + h] = np.roll(out[y:y + h], int(rng.integers(-60, 60) * strength), axis=1)
    return out


def blurred(frame, darken=0.5):
    small = cv2.resize(frame, (W // 4, H // 4), interpolation=cv2.INTER_AREA)
    small = cv2.GaussianBlur(small, (0, 0), 5)
    return (cv2.resize(small, (W, H), interpolation=cv2.INTER_LINEAR) * darken).astype(np.uint8)


def envelope(times, t, decay=0.11):
    """Сумма коротких импульсов в моменты times (отсортированы): удар камеры, вспышка."""
    i = bisect.bisect_right(times, t)
    total = 0.0
    for event in times[max(0, i - 6):i]:
        x = t - event
        total += math.exp(-x / decay) * min(1.0, x / 0.025 + 0.35)
    return total


class Clip:
    """Кадры дубля подряд, начиная с секунды start; когда кончились — последний кадр."""

    def __init__(self, video, start, duration):
        self.proc = subprocess.Popen(
            ["ffmpeg", "-loglevel", "error", "-ss", f"{max(0.0, start):.3f}", "-i", str(video),
             "-t", f"{duration + 0.25:.3f}", "-vf", f"scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"],
            stdout=subprocess.PIPE)
        self.last = np.zeros((H, W, 3), np.uint8)

    def next(self):
        data = self.proc.stdout.read(W * H * 3)
        if len(data) == W * H * 3:
            self.last = np.frombuffer(data, np.uint8).reshape(H, W, 3).copy()
        return self.last.copy()

    def close(self):
        self.proc.kill()
        self.proc.wait()


class Equalizer:
    """Столбики спектра звука сцены: 60 замеров в секунду, быстрый подъём, плавный спад."""

    BARS = 18

    def __init__(self, audio):
        mono = audio.mean(axis=1)
        hop, size = SR // FPS, 4096
        edges = np.geomspace(60, 9000, self.BARS + 1)
        freqs = np.fft.rfftfreq(size, 1 / SR)
        bands = [(freqs >= lo) & (freqs < hi) for lo, hi in zip(edges, edges[1:])]
        window = np.hanning(size)
        padded = np.concatenate([np.zeros(size), mono, np.zeros(size)])
        frames = []
        for k in range(len(mono) // hop + 1):
            chunk = padded[k * hop + size // 2:k * hop + size // 2 + size]
            if len(chunk) < size:
                chunk = np.pad(chunk, (0, size - len(chunk)))
            spectrum = np.abs(np.fft.rfft(chunk * window))
            frames.append([20 * np.log10(spectrum[b].mean() + 1e-9) for b in bands])
        db = np.array(frames)
        # Шкала каждой полосы — от её громких мест (95-й перцентиль) на 36 дБ вниз, чтобы столбики жили.
        top = np.percentile(db, 95, axis=0)
        target = np.clip((db - (top - 36)) / 36, 0.0, 1.0)
        levels, current = [], np.zeros(self.BARS)
        for row in target:
            current = np.where(row > current, row, current * 0.86 + row * 0.14)
            levels.append(current.copy())
        self.levels = np.array(levels)

    def draw(self, frame, n, colors, alpha=1.0):
        level = self.levels[min(n, len(self.levels) - 1)]
        right, base, bar, gap = W - 28, H - 30, 12, 5
        left = right - self.BARS * (bar + gap)
        overlay = frame.copy()
        cv2.rectangle(overlay, (left - 14, base - 118), (right + 8, base + 12), (16, 10, 20), -1)
        frame[:] = cv2.addWeighted(frame, 1 - 0.45 * alpha, overlay, 0.45 * alpha, 0)
        for i, value in enumerate(level):
            height = int(6 + value * 100)
            x = left + i * (bar + gap)
            mix = min(1.0, value * 1.2)
            color = [int(colors[0][c] * (1 - mix) + colors[1][c] * mix) for c in (2, 1, 0)]
            cv2.rectangle(frame, (x, base - height), (x + bar, base), color, -1)


# ---------------------------------------------------------------- монтаж

@dataclass
class Segment:
    kind: str                 # hook, title, card, clip, outro
    start: float              # секунда ролика
    length: float
    src: float = 0.0          # секунда дубля
    track: str = "after"      # after — звуки 1.3.0, before — 1.2.1
    grade: str = "new"
    badge: tuple = ()
    beats: list = field(default_factory=list)
    beat_zoom: float = 0.0
    hits: list = field(default_factory=list)
    hit_zoom: float = 0.0
    hit_shake: float = 0.0
    flashes: list = field(default_factory=list)
    flash_alpha: float = 0.15
    glitch_in: bool = False
    number: str = ""
    title: str = ""
    game_gain: float = 1.0
    base_zoom: float = 1.0
    zoom_at: tuple = (W / 2, H / 2)
    music_gain: float = 0.0
    shots: list = field(default_factory=list)


def plan(log, takes):
    """Сцены ролика. Секунды в ролике совпадают с narration.txt; места в дублях — по журналу звуков."""
    flight_launch = first(log.times("launch.ogg", takes["flight"][0] - 1, takes["hits"][0]), takes["flight"][0])
    flight_loop = first(log.times("sounds/flight.ogg", flight_launch - 0.5, takes["hits"][0]), flight_launch)
    strikes_hits = log.times("strike", takes["hits"][0], takes["streak"][0])
    kills_streak = log.times("successful_hit", takes["streak"][0], takes["fire"][0])
    rock_streak = first(log.times("combo_rock", takes["streak"][0], takes["fire"][0]), kills_streak[1] if len(kills_streak) > 1 else 0)
    kills_fire = log.times("successful_hit", takes["fire"][0], takes["orbit"][0])
    rock_fire = first(log.times("combo_rock", takes["fire"][0], takes["orbit"][0]), kills_fire[1] if len(kills_fire) > 1 else 0)
    orbit_launch = first(log.times("launch.ogg", takes["orbit"][0], takes["end"][0]), takes["orbit"][0] + 1.6)

    def beats(origin, step, src, length):
        """Удары в такт внутри [src, src+length) — в секундах сцены."""
        k = math.ceil((src - origin) / step)
        out = []
        while origin + k * step < src + length:
            out.append(origin + k * step - src)
            k += 1
        return out

    def local(times, src, length):
        return [t - src for t in times if src <= t < src + length]

    flight_src = flight_launch + 1.2
    hits_src = first(strikes_hits, takes["hits"][0] + 3.5) - 0.7
    streak_src = first(kills_streak, takes["streak"][0] + 3.0) - 1.0
    fire_src = first(kills_fire, takes["fire"][0] + 3.0) - 0.8
    orbit_src = orbit_launch + 0.3
    mid = lambda kills, k, fallback: kills[min(len(kills) - 1, k)] if kills else fallback
    hook_shots = [mid(kills_fire, 0, fire_src) - 0.3, mid(kills_streak, len(kills_streak) // 2, streak_src) - 0.6,
                  mid(kills_fire, len(kills_fire) // 2, fire_src) - 0.5, mid(kills_streak, len(kills_streak) - 4, streak_src) - 0.4]
    old, new = ("БЫЛО", "1.2.1"), ("СТАЛО", "1.3.0")
    return [
        Segment("hook", 0.0, 6.4, shots=hook_shots, beats=[i * ROCK_BEAT for i in range(16)], beat_zoom=0.045,
                flashes=[0.0, 1.6, 3.2, 4.8], game_gain=0.35, music_gain=1.0),
        Segment("title", 6.4, 8.6, src=orbit_src + 1.5, game_gain=0.0, music_gain=0.55),
        Segment("card", 15.0, 1.6, src=flight_src, number="01", title="ПОЛЁТ", game_gain=0.0),
        Segment("clip", 16.6, 8.0, src=flight_src, track="before", grade="old", badge=old + ("флейта",)),
        Segment("clip", 24.6, 10.0, src=flight_src, badge=new + ("сочный бас",), glitch_in=True,
                beats=beats(flight_loop, BASS_BEAT, flight_src, 10.0), beat_zoom=0.012),
        Segment("card", 34.6, 1.6, src=hits_src, number="02", title="УДАР", game_gain=0.0),
        # Танк стоит поодаль — обе версии чуть приближены к нему.
        Segment("clip", 36.2, 7.0, src=hits_src, track="before", grade="old", badge=old + ("тук-тук",),
                base_zoom=1.35, zoom_at=HITS_FOCUS),
        Segment("clip", 43.2, 10.0, src=hits_src, badge=new + ("гитарный удар",), glitch_in=True,
                base_zoom=1.35, zoom_at=HITS_FOCUS,
                hits=local(strikes_hits, hits_src, 10.0), hit_zoom=0.05, hit_shake=8.0,
                flashes=local(strikes_hits, hits_src, 10.0), flash_alpha=0.1),
        Segment("card", 53.2, 1.6, src=streak_src, number="03", title="СЕРИИ", game_gain=0.0),
        Segment("clip", 54.8, 9.0, src=streak_src, track="before", grade="old", badge=old + ("звон",)),
        Segment("clip", 63.8, 18.0, src=streak_src, badge=new + ("рок на серии",), glitch_in=True,
                beats=beats(rock_streak, ROCK_BEAT, streak_src, 18.0), beat_zoom=0.028,
                hits=local(kills_streak, streak_src, 18.0), hit_zoom=0.02, hit_shake=6.0,
                flashes=local(kills_streak, streak_src, 18.0)),
        Segment("clip", 81.8, 10.2, src=fire_src, badge=("БОНУС", "", "огненный след"),
                beats=beats(rock_fire, ROCK_BEAT, fire_src, 10.2), beat_zoom=0.028,
                hits=local(kills_fire, fire_src, 10.2), hit_zoom=0.02, hit_shake=6.0,
                flashes=local(kills_fire, fire_src, 10.2)),
        # В концовке только рок и голос: бас полёта под роком спорил бы с ним.
        Segment("outro", 92.0, 16.0, src=orbit_src, game_gain=0.0, music_gain=0.8),
    ]


class Montage:
    def __init__(self, raw, narration, segments):
        self.raw = raw
        self.segments = segments
        self.total = segments[-1].start + segments[-1].length
        self.tracks = {"after": read_audio(raw / "raw-sfx.wav"), "before": read_audio(raw / "raw-sfx-before.wav")}
        self.voice = read_audio(narration / "narration.wav")
        # Дубли записаны с разного расстояния: подгоняем громкость каждого под общий уровень по
        # звукам 1.3.0 и тем же множителем двигаем «как было» — разница между версиями остаётся честной.
        levels = {}
        for segment in segments:
            if segment.kind == "clip" and segment.track == "after":
                levels[segment.src] = loudness(self.slice(self.tracks["after"], segment.src, segment.length))
        for segment in segments:
            if segment.kind == "clip" and segment.src in levels:
                segment.game_gain *= float(np.clip(10 ** ((GAME_LEVEL - levels[segment.src]) / 20), 0.5, 4.0))
        lines = cue_times(LINES)
        self.captions = []
        for line in (narration / "narration-timing.txt").read_text().splitlines():
            cue, begin, end = line.split()
            if cue in lines and lines[cue][1] != "-":
                text = lines[cue][1].replace("+", "")
                self.captions.append((float(begin), float(end), caption_sprite(text)))
        self.sprites = {}

    def sprite(self, key, factory):
        if key not in self.sprites:
            self.sprites[key] = factory()
        return self.sprites[key]

    # ------------------------------------------------------------ звук

    def game_audio(self, segment):
        track = self.tracks[segment.track]
        if segment.kind == "hook":
            return np.concatenate([self.slice(track, shot, 1.6) for shot in segment.shots])
        return self.slice(track, segment.src, segment.length)

    @staticmethod
    def slice(track, start, length):
        a, n = int(start * SR), int(length * SR)
        piece = np.zeros((n, 2))
        part = track[max(0, a):max(0, a) + n]
        piece[:len(part)] = part
        fade = int(0.012 * SR)
        ramp = np.linspace(0, 1, fade)[:, None]
        piece[:fade] *= ramp
        piece[-fade:] *= ramp[::-1]
        return piece

    def sound(self, path):
        n = int(self.total * SR) + SR
        game, music, fx = np.zeros((n, 2)), np.zeros((n, 2)), np.zeros((n, 2))
        rock = read_audio(ROCK_LOOP)
        rng = np.random.default_rng(7)
        for segment in self.segments:
            a = int(segment.start * SR)
            piece = self.game_audio(segment) * segment.game_gain
            game[a:a + len(piece)] += piece
            if segment.music_gain:
                length = int(segment.length * SR)
                bed = np.tile(rock, (length // len(rock) + 1, 1))[:length] * segment.music_gain
                fade_in, fade_out = int(0.02 * SR), int(0.6 * SR)
                bed[:fade_in] *= np.linspace(0, 1, fade_in)[:, None]
                if segment.kind != "hook":
                    bed[-fade_out:] *= np.linspace(1, 0, fade_out)[:, None]
                if segment.kind == "outro":
                    bed[:int(0.5 * SR)] *= np.linspace(0, 1, int(0.5 * SR))[:, None]
                music[a:a + length] += bed
            if segment.kind == "card":
                place(fx, whoosh(rng), segment.start - 0.05, 0.5)
                place(fx, impact(), segment.start + 0.2, 0.75)
            if segment.glitch_in:
                place(fx, glitch_sound(rng), segment.start, 0.35)
            elif segment.kind in ("clip", "outro") and segment.badge[:1] != ("БЫЛО",):
                place(fx, whoosh(rng), segment.start - 0.3, 0.35)
        place(fx, impact(), 0.05, 0.6)
        place(fx, impact(), 6.45, 0.7)
        voice = np.zeros((n, 2))
        voice[:min(n, len(self.voice))] = self.voice[:n]
        mix = (game + music * 0.8) * ducking(voice)[:, None] + fx + voice * VOICE_GAIN
        mix = mix[:int(self.total * SR)]
        fade = int(1.2 * SR)
        mix[-fade:] *= np.linspace(1, 0, fade)[:, None]
        write_wav(path, mix)
        measured = measure_loudness(path)
        mix = limit(mix * 10 ** ((LOUDNESS - measured) / 20), -1.5)
        write_wav(path, mix)
        print(f"Звук: {measured:.1f} LUFS → {LOUDNESS} LUFS")

    # ------------------------------------------------------------ картинка

    def render(self, out_path, audio_path):
        encoder = subprocess.Popen(
            ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}",
             "-r", str(FPS), "-i", "-", "-i", str(audio_path), "-c:v", "libx264", "-preset", "medium", "-crf", "18",
             "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart",
             str(out_path)], stdin=subprocess.PIPE)
        video = self.raw / "raw-video.mp4"
        frame_no = 0
        for index, segment in enumerate(self.segments):
            frames = round((segment.start + segment.length) * FPS) - round(segment.start * FPS)
            equalizer = Equalizer(self.game_audio(segment)) if segment.kind == "clip" else None
            clips = [Clip(video, shot, 1.6) for shot in segment.shots] if segment.kind == "hook" \
                else [Clip(video, segment.src, segment.length)]
            for n in range(frames):
                t = n / FPS
                if segment.kind == "hook":
                    frame = clips[min(len(clips) - 1, int(t / 1.6))].next()
                else:
                    frame = clips[0].next()
                frame = getattr(self, "frame_" + segment.kind)(segment, frame, t, n, frame_no, equalizer)
                frame = self.overlay_captions(frame, segment, segment.start + t)
                encoder.stdin.write(frame.tobytes())
                frame_no += 1
            for clip in clips:
                clip.close()
            print(f"Сцена {index + 1}/{len(self.segments)}: {segment.kind} {segment.start:.1f}–"
                  f"{segment.start + segment.length:.1f} с", flush=True)
        encoder.stdin.close()
        encoder.wait()

    def camera(self, segment, frame, t, n):
        zoom = segment.base_zoom * (1.0 + segment.beat_zoom * envelope(segment.beats, t)
                                    + segment.hit_zoom * envelope(segment.hits, t, 0.09))
        shake = segment.hit_shake * envelope(segment.hits, t, 0.08)
        dx = shake * math.sin(t * 2 * math.pi * 23.0)
        dy = shake * math.cos(t * 2 * math.pi * 19.0)
        return move(frame, zoom, dx, dy, center=segment.zoom_at)

    def frame_hook(self, segment, frame, t, n, frame_no, _):
        frame = self.camera(segment, grade(frame, "new", n), t, n)
        frame = flash(frame, 0.75 * envelope(segment.flashes, t, 0.07))
        texts = [(0.05, 1.5, "АПДЕЙТ", 150, FIRE), (1.65, 3.1, "1.3.0", 170, GOLD),
                 (3.25, 4.0, "ТЕПЕРЬ", 120, None), (4.0, 6.35, "ЭТО РОК!", 170, FIRE)]
        for begin, end, text, size, colors in texts:
            if begin <= t < end:
                sprite = self.sprite(("hook", text), lambda: text_sprite(text, size, colors=colors, stroke=8, shadow=6))
                scale, alpha = slam(t, begin)
                alpha *= min(1.0, (end - t) / 0.12)
                wobble = 6 * math.exp(-(t - begin) / 0.2) * math.sin((t - begin) * 70)
                blend(frame, sprite, W / 2 + wobble, H / 2 - 20, scale, alpha)
        return frame

    def frame_title(self, segment, frame, t, n, frame_no, _):
        frame = blurred(grade(frame, "new", n), 0.55)
        logo = self.sprite("logo", lambda: text_sprite("СТРЕЛА АНДРЮШКИ", 104, colors=FIRE, stroke=8, shadow=7))
        scale, alpha = slam(t, 0.05, 0.32, 2.0)
        blend(frame, logo, W / 2, 210, scale * (1 + 0.01 * math.sin(t * 3)), alpha)
        tag = self.sprite("tag", lambda: pill_sprite("АПДЕЙТ 1.3.0", 44, (*RED, 255), small="· РОК-ЗВУК"))
        x = ease_out((t - 0.7) / 0.35)
        blend(frame, tag, W / 2 - (1 - x) * 700, 320, 1.0, x)
        chips = ["БАС В ПОЛЁТЕ", "ГИТАРНЫЙ УДАР", "РОК НА СЕРИЯХ"]
        for i, chip in enumerate(chips):
            sprite = self.sprite(("chip", chip), lambda c=chip: pill_sprite(c, 34, (24, 18, 30, 230), color=(255, 214, 90)))
            scale, alpha = slam(t, 3.2 + i * 0.7, 0.25, 1.6)
            blend(frame, sprite, W / 2 + (i - 1) * 380, 440, scale, alpha)
        versus = self.sprite("versus", lambda: text_sprite("БЫЛО  vs  СТАЛО", 64, stroke=6, shadow=5))
        scale, alpha = slam(t, 5.5, 0.3, 1.8)
        blend(frame, versus, W / 2, 560, scale, alpha)
        return flash(frame, 0.8 * envelope([0.05], t, 0.1))

    def frame_card(self, segment, frame, t, n, frame_no, _):
        frame = blurred(grade(frame, "new", n), 0.45)
        offset = (1 - ease_out(t / 0.2)) * -1.5 * W
        band = np.array([[-200 + offset, 300], [W + 200 + offset, 210], [W + 200 + offset, 470], [-200 + offset, 560]],
                        np.int32)
        cv2.fillPoly(frame, [band], (40, 50, 220))
        # Тонкая жёлтая полоса над красной.
        stripe = np.array([band[0] + [0, -22], band[1] + [0, -22], band[1] + [0, -8], band[0] + [0, -8]], np.int32)
        cv2.fillPoly(frame, [stripe], (60, 200, 255))
        number = self.sprite(("num", segment.number), lambda: text_sprite(segment.number, 170, stroke=8, shadow=6))
        title = self.sprite(("title", segment.title), lambda: text_sprite(segment.title, 132, colors=FIRE, stroke=8, shadow=6))
        scale, alpha = slam(t, 0.08, 0.25, 2.2)
        blend(frame, number, W / 2 - 330, 385, scale, alpha)
        scale, alpha = slam(t, 0.2, 0.28, 2.2)
        blend(frame, title, W / 2 + 120, 385, scale, alpha)
        sub = self.sprite("sub", lambda: text_sprite("как было — как стало", 36, stroke=4, shadow=3))
        blend(frame, sub, W / 2 + 120, 500, 1.0, ease_out((t - 0.5) / 0.3))
        shake = 10 * math.exp(-max(0.0, t - 0.2) / 0.12) if t >= 0.2 else 0.0
        frame = move(frame, 1.0 + 0.04 * max(0.0, (t - 1.3) / 0.3) ** 2, shake * math.sin(t * 90), shake * math.cos(t * 77))
        return flash(frame, max(0.9 * envelope([0.2], t, 0.08), (t - 1.42) / 0.18 if t > 1.42 else 0.0))

    def frame_clip(self, segment, frame, t, n, frame_no, equalizer):
        frame = self.camera(segment, grade(frame, segment.grade, n), t, n)
        if segment.flashes:
            frame = flash(frame, segment.flash_alpha * min(1.0, envelope(segment.flashes, t, 0.06)))
        if segment.glitch_in and t < 0.35:
            frame = glitch(frame, 1 - t / 0.35, n)
            frame = flash(frame, 0.7 * max(0.0, 1 - t / 0.12))
        if not segment.glitch_in and t < 0.15:
            frame = flash(frame, 1 - t / 0.15)
        old = segment.grade == "old"
        word, version, note = segment.badge
        badge = self.sprite(("badge", word, version), lambda: pill_sprite(
            word, 46, (90, 94, 104, 235) if old else (*RED, 245), small=version or None,
            small_color=(220, 220, 225) if old else (255, 220, 120)))
        scale, alpha = slam(t, 0.1, 0.25, 1.7)
        blend(frame, badge, 30 + badge.shape[1] / 2, 34 + badge.shape[0] / 2, scale, alpha)
        note_sprite = self.sprite(("note", note), lambda: text_sprite(note, 34, fill=OLD if old else (255, 214, 90),
                                                                      stroke=4, shadow=3))
        x = ease_out((t - 0.35) / 0.3)
        blend(frame, note_sprite, 34 + note_sprite.shape[1] / 2 - (1 - x) * 60, 34 + badge.shape[0] + 26, 1.0, x)
        equalizer.draw(frame, n, (OLD, (230, 232, 240)) if old else FIRE, min(1.0, t / 0.3))
        return frame

    def frame_outro(self, segment, frame, t, n, frame_no, _):
        frame = flash(grade(frame, "new", n), max(0.0, 1 - t / 0.15))
        timeline_t = segment.start + t
        checks = [("БАС В ПОЛЁТЕ", 0.6), ("ГИТАРНЫЙ УДАР", 1.6), ("РОК НА СЕРИЯХ", 2.6)]
        outro_start = next((begin for begin, _, _ in self.captions if abs(begin - 92.4) < 1.5), 92.4)
        for i, (text, delay) in enumerate(checks):
            sprite = self.sprite(("check", text), lambda c=text: check_sprite(c, 40))
            appear = timeline_t - (outro_start + delay)
            if appear >= 0 and t < 11.8:
                scale, alpha = slam(appear, 0.0, 0.25, 1.6)
                alpha *= min(1.0, (11.8 - t) / 0.3)
                blend(frame, sprite, 60 + sprite.shape[1] / 2 + (1 - ease_out(appear / 0.25)) * -80, 150 + i * 78,
                      scale, alpha)
        if t >= 12.0:
            x = t - 12.0
            frame = (frame * (1 - 0.7 * ease_out(x / 0.5))).astype(np.uint8)
            big = self.sprite("download", lambda: text_sprite("КАЧАЙ 1.3.0", 120, colors=FIRE, stroke=8, shadow=6))
            link = self.sprite("link", lambda: pill_sprite("github.com/Dobrelya22/-", 40, (24, 18, 30, 240),
                                                           small="· Releases", small_color=(255, 214, 90)))
            scale, alpha = slam(x, 0.1, 0.3, 2.0)
            blend(frame, big, W / 2, 270, scale, alpha)
            blend(frame, link, W / 2, 420, 1.0, ease_out((x - 0.5) / 0.3))
            thanks = self.sprite("thanks", lambda: text_sprite("Даник · Жана · Алексей — рок!", 40, stroke=4, shadow=3))
            blend(frame, thanks, W / 2, 520, 1.0, ease_out((x - 0.9) / 0.3))
            frame = flash(frame, 0.7 * envelope([0.1], x, 0.1))
        if t > segment.length - 0.9:
            frame = (frame * max(0.0, (segment.length - t) / 0.9)).astype(np.uint8)
        return frame

    def overlay_captions(self, frame, segment, now):
        if segment.kind in ("hook", "title", "card") or (segment.kind == "outro" and now > segment.start + 12.0):
            return frame
        for begin, end, sprite in self.captions:
            if begin - 0.05 <= now < end + 0.25:
                scale = 0.92 + 0.08 * ease_out((now - begin) / 0.12)
                alpha = min(1.0, (now - begin + 0.05) / 0.1, (end + 0.25 - now) / 0.15)
                blend(frame, sprite, W / 2, H - 175 - sprite.shape[0] / 2 + 20, scale, alpha)
        return frame


# ---------------------------------------------------------------- звуковые эффекты

def ducking(voice, depth_db=-7.0, attack=0.03, release=0.35):
    """Насколько приглушить игру и музыку, пока говорит голос: по блокам в 10 мс."""
    block = SR // 100
    mono = voice.mean(axis=1)
    blocks = len(mono) // block + 1
    level = np.sqrt(np.array([np.mean(mono[i * block:(i + 1) * block] ** 2) if i * block < len(mono) else 0.0
                              for i in range(blocks)]))
    target = np.where(level > 0.01, 10 ** (depth_db / 20), 1.0)
    gain, current = np.empty(blocks), 1.0
    up, down = math.exp(-0.01 / release), math.exp(-0.01 / attack)
    for i, value in enumerate(target):
        coeff = down if value < current else up
        current = coeff * current + (1 - coeff) * value
        gain[i] = current
    return np.interp(np.arange(len(mono)), np.arange(blocks) * block + block / 2, gain)


def place(track, sound, at, gain):
    a = int(at * SR)
    if a < 0:
        sound, a = sound[-a:], 0
    n = min(len(sound), len(track) - a)
    if n > 0:
        track[a:a + n] += sound[:n] * gain


def whoosh(rng, length=0.45):
    """Шорох пролёта: шум, полоса которого уезжает вверх, нарастает и обрывается."""
    n = int(length * SR)
    noise = rng.normal(0, 1, n)
    t = np.linspace(0, 1, n)
    low = signal.sosfilt(signal.butter(2, [300, 1500], "bandpass", fs=SR, output="sos"), noise)
    high = signal.sosfilt(signal.butter(2, [1500, 7000], "bandpass", fs=SR, output="sos"), noise)
    mono = (low * (1 - t) + high * t) * np.sin(np.pi * t ** 1.4) ** 2
    mono /= np.abs(mono).max() + 1e-9
    pan = np.linspace(-0.7, 0.7, n)
    return np.stack([mono * (1 - pan) / 2 * 1.4, mono * (1 + pan) / 2 * 1.4], axis=1)


def impact():
    """Глухой удар титров: низкий «бум» с падающей высотой и щелчок."""
    n = int(0.9 * SR)
    t = np.arange(n) / SR
    freq = 40 + 70 * np.exp(-t / 0.08)
    boom = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-t / 0.28)
    click = np.random.default_rng(3).normal(0, 1, n) * np.exp(-t / 0.012)
    click = signal.sosfilt(signal.butter(2, 3500, "lowpass", fs=SR, output="sos"), click)
    mono = np.tanh((boom * 1.2 + click * 0.5) * 1.5)
    return np.stack([mono, mono], axis=1)


def glitch_sound(rng):
    """Цифровой сбой: пара коротких писков и треск."""
    n = int(0.3 * SR)
    out = np.zeros(n)
    for start, freq in ((0.0, 1800), (0.07, 1200), (0.13, 2600)):
        a, b = int(start * SR), int((start + 0.045) * SR)
        t = np.arange(b - a) / SR
        out[a:b] += np.sign(np.sin(2 * np.pi * freq * t)) * 0.35
    crackle = rng.normal(0, 1, n) * (rng.random(n) > 0.97) * np.exp(-np.arange(n) / (0.12 * SR))
    mono = out + crackle * 0.6
    return np.stack([mono, mono], axis=1)


# ---------------------------------------------------------------- вывод

def write_wav(path, audio):
    with wave.open(str(path), "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(SR)
        out.writeframes((np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16).tobytes())


def loudness(audio):
    """Громкость на слух (LUFS): K-фильтр из ITU-R BS.1770 для 48 кГц, медиана по секундам. Медиана —
    чтобы сцену мерили её звуки (удары, рок), а не один громкий хвост вроде свиста в конце."""
    shelf = signal.lfilter([1.53512485958697, -2.69169618940638, 1.19839281085285],
                           [1.0, -1.69065929318241, 0.73248077421585], audio, axis=0)
    weighted = signal.lfilter([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621], shelf, axis=0)
    blocks = [weighted[i:i + SR] for i in range(0, len(weighted) - SR + 1, SR // 2)] or [weighted]
    values = [-0.691 + 10 * math.log10(float(np.sum(np.mean(b ** 2, axis=0))) + 1e-12) for b in blocks]
    loud = [v for v in values if v > -60]
    return float(np.median(loud)) if loud else -60.0


def measure_loudness(path):
    result = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path), "-af", "ebur128", "-f", "null", "-"],
                            capture_output=True, text=True)
    values = [line.split(":")[1].split()[0] for line in result.stderr.splitlines() if line.strip().startswith("I:")]
    return float(values[-1]) if values else LOUDNESS


def limit(x, ceiling_db):
    """Ограничитель с упреждением 2 мс и отпуском 60 мс."""
    ceiling = 10 ** (ceiling_db / 20)
    need = np.minimum(1.0, ceiling / np.maximum(np.abs(x).max(axis=1), 1e-9))
    look = SR // 500
    ahead = np.lib.stride_tricks.sliding_window_view(np.concatenate([need, np.ones(look)]), look + 1).min(axis=1)
    smooth = np.convolve(np.concatenate([np.ones(look), ahead]), np.ones(look + 1) / (look + 1), "valid")
    pole = math.exp(-1 / (0.06 * SR))
    released = signal.lfilter([1 - pole], [1, -pole], smooth - 1.0) + 1.0
    return x * np.minimum(smooth, released)[:, None]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("raw", type=Path, help="папка дублей (raw-*)")
    parser.add_argument("narration", type=Path, help="папка с narration.wav и narration-timing.txt")
    parser.add_argument("out", type=Path)
    parser.add_argument("--only", help="отрендерить только отрезок «начало-конец» в секундах — для проверки")
    args = parser.parse_args()

    segments = plan(SoundLog(args.raw), cue_times(TAKES))
    for s in segments:
        print(f"{s.start:6.1f}–{s.start + s.length:6.1f}  {s.kind:<6} дубль с {s.src:6.2f} с  {s.track}"
              f"  ударов {len(s.hits)}, долей {len(s.beats)}")
    montage = Montage(args.raw, args.narration, segments)
    if args.only:
        begin, end = map(float, args.only.split("-"))
        montage.segments = [s for s in segments if s.start + s.length > begin and s.start < end]
    with tempfile.TemporaryDirectory() as tmp:
        audio = Path(tmp) / "audio.wav"
        montage.sound(audio)
        if args.only:
            # Звук всего ролика обрезаем до выбранных сцен.
            first_start = montage.segments[0].start
            trimmed = Path(tmp) / "trimmed.wav"
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(first_start), "-i", str(audio),
                            str(trimmed)], check=True)
            audio = trimmed
        montage.render(args.out, audio)
    print(f"Готово: {args.out}")


if __name__ == "__main__":
    sys.exit(main())
