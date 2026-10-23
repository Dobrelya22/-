#!/usr/bin/env python3
"""Генерирует текстуры, иконку и звуки мода «Стрела Олегуса».

Нужны Pillow, numpy и ffmpeg с libvorbis. Запуск из корня репозитория:
    python3 tools/generate_assets.py
Результат детерминирован: шум задаётся фиксированным зерном.
"""

import math
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/olegus_arrow"

OUTLINE = (58, 6, 12, 255)
DARK = (122, 14, 22, 255)
RED = (205, 32, 38, 255)
BRIGHT = (255, 92, 78, 255)
TIP = (255, 214, 196, 255)


# ------------------------------------------------------------------ текстуры

def item_texture() -> Image.Image:
    """Иконка 16×16: красная стрела Яка по диагонали, остриё вправо-вверх."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # Древко: светлая диагональ и тёмная нижняя грань.
    for x in range(2, 12):
        px[x, 15 - x] = RED
    for x in range(3, 11):
        px[x + 1, 15 - x] = DARK
    # Наконечник — треугольник с прямым углом в острие (14, 1).
    for x in range(10, 15):
        for y in range(1, 6):
            if (14 - x) + (y - 1) <= 4:
                edge = y == 1 or x == 14
                px[x, y] = BRIGHT if edge else RED
    px[14, 1] = TIP
    # Оперение, симметричное относительно древка, и хвостовик.
    for x, y in [(1, 11), (2, 11), (1, 10), (4, 14), (4, 13), (5, 14)]:
        px[x, y] = DARK
    px[1, 14] = DARK
    # Контур вокруг всего непрозрачного.
    filled = {(x, y) for x in range(16) for y in range(16) if px[x, y][3] > 0}
    for x, y in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < 16 and 0 <= ny < 16 and (nx, ny) not in filled:
                px[nx, ny] = OUTLINE
    return img


def entity_texture() -> Image.Image:
    """Развёртка 32×32 как у ванильной стрелы: сбоку 16×5 сверху, торец 5×5 под ним."""
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = img.load()
    # Вид сбоку: x=0 — хвост, x=15 — остриё; ось древка — строка 2.
    for x in range(1, 12):
        px[x, 2] = RED
    for x, y in [(0, 0), (1, 0), (1, 1), (2, 1), (0, 4), (1, 4), (1, 3), (2, 3)]:
        px[x, y] = DARK
    px[0, 2] = OUTLINE
    for x, y in [(11, 1), (11, 3)]:
        px[x, y] = DARK
    for x in (12, 13):
        for y in (1, 2, 3):
            px[x, y] = BRIGHT
    px[14, 2] = BRIGHT
    px[15, 2] = TIP
    # Торец — крестик оперения.
    for i in range(5):
        px[2, 5 + i] = DARK
        px[i, 7] = DARK
    px[2, 7] = OUTLINE
    return img


def mod_icon() -> Image.Image:
    """Иконка мода 128×128: космический фон, радужный след и стрела."""
    size = 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    background = Image.new("RGBA", (size, size))
    bg = background.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            bg[x, y] = (int(22 + 30 * t), int(10 + 8 * t), int(48 + 40 * t), 255)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=22, fill=255)
    img.paste(background, (0, 0), mask)

    rng = np.random.default_rng(7)
    draw = ImageDraw.Draw(img)
    for _ in range(26):
        x, y = rng.integers(8, size - 8, size=2)
        a = int(rng.integers(90, 220))
        draw.point((int(x), int(y)), fill=(255, 255, 255, a))

    # Радужный след — дуга, уходящая к стреле.
    trail = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    td = ImageDraw.Draw(trail)
    points = []
    for i in range(60):
        t = i / 59
        x = 14 + 74 * t
        y = 104 - 70 * t - 22 * math.sin(math.pi * t)
        points.append((x, y))
    for i in range(len(points) - 1):
        r, g, b = hsv(i / len(points))
        width = int(3 + 7 * (i / len(points)))
        td.line([points[i], points[i + 1]], fill=(r, g, b, 235), width=width)
    glow = trail.filter(ImageFilter.GaussianBlur(4))
    img.alpha_composite(glow)
    img.alpha_composite(trail)

    arrow = item_texture().resize((64, 64), Image.NEAREST)
    img.alpha_composite(arrow, (60, 4))
    return img


def hsv(hue: float) -> tuple:
    h = (hue % 1.0) * 6.0
    sector = int(h)
    f = h - sector
    q, t = 1.0 - f, f
    r, g, b = [(1, t, 0), (q, 1, 0), (0, 1, t), (0, q, 1), (t, 0, 1), (1, 0, q)][sector % 6]
    return int(r * 255), int(g * 255), int(b * 255)


# ------------------------------------------------------------------ звуки

RATE = 44100


def envelope(n: int, attack: float, release: float) -> np.ndarray:
    env = np.ones(n)
    a, r = int(attack * RATE), int(release * RATE)
    env[:a] = np.linspace(0.0, 1.0, a)
    env[n - r:] *= np.linspace(1.0, 0.0, r) ** 1.5
    return env


def tone(freq: np.ndarray) -> np.ndarray:
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    return np.sin(phase) + 0.12 * np.sin(2 * phase) + 0.04 * np.sin(3 * phase)


def lowpass(signal: np.ndarray, cutoff: np.ndarray) -> np.ndarray:
    """Однополюсный фильтр с меняющейся частотой среза."""
    out = np.empty_like(signal)
    alpha = 1.0 - np.exp(-2 * np.pi * cutoff / RATE)
    acc = 0.0
    for i, (x, a) in enumerate(zip(signal, alpha)):
        acc += a * (x - acc)
        out[i] = acc
    return out


def whistle() -> np.ndarray:
    """Свист в духе Йонду: взлёт, короткий нырок и высокий звонкий хвост с вибрато."""
    duration = 1.05
    n = int(duration * RATE)
    t = np.arange(n) / RATE
    freq = np.interp(t, [0.0, 0.28, 0.40, 0.52, 0.85, 1.05], [1150, 1950, 1500, 2350, 2450, 2300])
    vibrato = np.where(t > 0.5, 38 * np.sin(2 * np.pi * 6.5 * t), 0.0)
    signal = tone(freq + vibrato) * envelope(n, 0.03, 0.18)
    breath = np.random.default_rng(1).normal(0, 1, n)
    breath = lowpass(breath, np.full(n, 3500.0)) * 0.08 * envelope(n, 0.02, 0.3)
    return normalize(signal + breath, 0.85)


def respond() -> np.ndarray:
    """Ответ стрелы: два звонких металлических «дзинь» с призвуками колокола."""
    n = int(0.75 * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for start, f0, f1 in ((0.0, 2400, 3100), (0.14, 3100, 3900)):
        local = np.clip(t - start, 0, None)
        active = t >= start
        freq = f0 + (f1 - f0) * np.clip(local / 0.08, 0, 1)
        phase = 2 * np.pi * np.cumsum(freq * active) / RATE
        decay = np.exp(-local * 9.0) * active
        ring = np.sin(phase) + 0.35 * np.sin(2.76 * phase) + 0.15 * np.sin(5.4 * phase)
        out += ring * decay
    shimmer = np.sin(2 * np.pi * 5200 * t) * np.exp(-t * 6) * 0.08 * (1 + np.sin(2 * np.pi * 30 * t))
    return normalize((out + shimmer) * envelope(n, 0.004, 0.1), 0.8)


def launch() -> np.ndarray:
    """Взлёт: нарастающий свист рассекаемого воздуха и высокий тон."""
    n = int(0.7 * RATE)
    t = np.arange(n) / RATE
    noise = np.random.default_rng(3).normal(0, 1, n)
    cutoff = np.interp(t, [0.0, 0.25, 0.7], [400, 5200, 1800])
    whoosh = lowpass(noise, cutoff) - lowpass(noise, cutoff * 0.35)
    whoosh *= np.interp(t, [0.0, 0.2, 0.7], [0.0, 1.0, 0.0])
    rise = tone(np.interp(t, [0.0, 0.5, 0.7], [700, 2600, 2900])) * np.interp(t, [0.0, 0.3, 0.7], [0.0, 0.35, 0.0])
    return normalize(whoosh * 1.6 + rise, 0.8)


def normalize(signal: np.ndarray, peak: float) -> np.ndarray:
    return signal / max(1e-9, np.max(np.abs(signal))) * peak


def write_ogg(signal: np.ndarray, target: Path) -> None:
    pcm = (np.clip(signal, -1, 1) * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "sound.wav"
        with wave.open(str(wav_path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(RATE)
            wav.writeframes(pcm.tobytes())
        # Minecraft воспроизводит позиционно только моно OGG Vorbis.
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav_path), "-ac", "1",
                        "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", str(target)], check=True)


def main() -> int:
    (ASSETS / "textures/item").mkdir(parents=True, exist_ok=True)
    (ASSETS / "textures/entity").mkdir(parents=True, exist_ok=True)
    (ASSETS / "sounds").mkdir(parents=True, exist_ok=True)

    item_texture().save(ASSETS / "textures/item/olegus_arrow.png")
    entity_texture().save(ASSETS / "textures/entity/olegus_arrow.png")
    mod_icon().save(ASSETS / "icon.png")

    write_ogg(whistle(), ASSETS / "sounds/whistle.ogg")
    write_ogg(respond(), ASSETS / "sounds/respond.ogg")
    write_ogg(launch(), ASSETS / "sounds/launch.ogg")
    print("Ассеты обновлены в", ASSETS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
