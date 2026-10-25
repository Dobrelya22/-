#!/usr/bin/env python3
"""Генерирует текстуры, иконку и звуки мода «Стрела Андрюшки».

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
ASSETS = ROOT / "src/main/resources/assets/andryushka_arrow"

OUTLINE = (46, 4, 12, 255)
DARK = (128, 14, 28, 255)
RED = (212, 34, 46, 255)
LIGHT = (255, 108, 96, 255)
HOT = (255, 232, 220, 255)


# ------------------------------------------------------------------ текстуры

def diagonal(s: int, u: int) -> tuple:
    """Пиксель спрайта по координатам вдоль стрелы (s, к острию) и поперёк неё (u, минус — вверх-влево)."""
    return (s + u + 15) // 2, (u + 15 - s) // 2


def item_texture() -> Image.Image:
    """Иконка 16×16: тонкая красная стрела Яка, остриё вправо-вверх, светлый наконечник и оперение."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for s in range(-11, 6, 2):
        px[diagonal(s, 0)] = RED
    for s in (-1, 1):
        px[diagonal(s, 0)] = LIGHT
    # Вытянутый наконечник: светлый верхний край, тёмный нижний, раскалённое остриё.
    for s, color in {7: RED, 9: LIGHT, 11: LIGHT, 13: HOT}.items():
        px[diagonal(s, 0)] = color
    for s, (upper, lower) in {6: (LIGHT, DARK), 8: (LIGHT, RED), 10: (LIGHT, RED)}.items():
        px[diagonal(s, -1)] = upper
        px[diagonal(s, 1)] = lower
    # Оперение: по штриху в каждую сторону, отогнутому назад, и хвостовик.
    for s, u, color in [(-6, 1, LIGHT), (-7, 2, RED), (-8, 3, RED), (-9, 4, DARK)]:
        px[diagonal(s, -u)] = color
        px[diagonal(s, u)] = color
    px[diagonal(-13, 0)] = DARK
    add_outline(px, 16)
    return img


def add_outline(px, size: int) -> None:
    filled = {(x, y) for x in range(size) for y in range(size) if px[x, y][3] > 0}
    for x, y in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < size and 0 <= ny < size and (nx, ny) not in filled:
                px[nx, ny] = OUTLINE


def model_texture() -> Image.Image:
    """Текстура 64×32 для объёмной модели: каждой детали — своя область однотонного цвета.

    Области совпадают с uv(...) в AndryushkaArrowRenderer.getTexturedModelData().
    """
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    regions = [
        ((0, 0, 26, 13), RED),                  # древко
        ((12, 0, 13, 12), (238, 64, 66, 255)),  # верхняя грань древка светлее
        ((0, 14, 8, 18), (255, 90, 78, 255)),   # основание наконечника
        ((8, 14, 15, 18), (255, 122, 102, 255)),
        ((16, 14, 22, 17), (255, 168, 146, 255)),
        ((24, 14, 28, 16), HOT),                # остриё
        ((28, 0, 34, 6), (196, 28, 42, 255)),   # оперение сзади
        ((36, 0, 47, 3), (196, 28, 42, 255)),
        ((28, 8, 33, 12), (226, 52, 60, 255)),  # оперение ближе к середине
        ((36, 5, 44, 8), (226, 52, 60, 255)),
        ((50, 0, 54, 2), (255, 122, 102, 255)),  # хвостовик
    ]
    for (x0, y0, x1, y1), color in regions:
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=color)
    return img


def glow_texture() -> Image.Image:
    """Мягкий светящийся диск с лучиками-бликами. Слой свечения складывает цвета: чёрное — прозрачно."""
    size = 32
    c = (size - 1) / 2
    y, x = np.mgrid[0:size, 0:size]
    dx, dy = (x - c) / c, (y - c) / c
    r = np.sqrt(dx * dx + dy * dy)
    glow = np.exp(-(r / 0.42) ** 2 * 2.2)
    rays = (np.exp(-np.abs(dx) * 3.0) * np.exp(-(dy * 14) ** 2) + np.exp(-np.abs(dy) * 3.0) * np.exp(-(dx * 14) ** 2))
    value = np.clip(glow + 0.45 * rays, 0, 1) * np.clip(1.2 - r, 0, 1)
    return grayscale_rgba(value)


def trail_texture() -> Image.Image:
    """Поперечный профиль ленты следа: яркая сердцевина и мягкие края."""
    size = 16
    v = (np.arange(size) + 0.5) / size - 0.5
    profile = np.clip(np.exp(-(v / 0.24) ** 2) * 0.75 + np.exp(-(v / 0.08) ** 2) * 0.5, 0, 1)
    return grayscale_rgba(np.tile(profile[:, None], (1, size)))


def grayscale_rgba(value: np.ndarray) -> Image.Image:
    level = (value * 255).astype(np.uint8)
    rgba = np.stack([level, level, level, np.full_like(level, 255)], axis=-1)
    return Image.fromarray(rgba, "RGBA")


def mod_icon() -> Image.Image:
    """Иконка мода 128×128: ночное небо, светящаяся стрела и радужный хвост за ней."""
    size = 128
    y, x = np.mgrid[0:size, 0:size]
    r = np.sqrt((x - 80) ** 2 + (y - 44) ** 2) / size
    sky = np.stack([28 + 40 * np.exp(-r * 4), 10 + 12 * np.exp(-r * 5), 46 + 50 * np.exp(-r * 3),
                    np.full_like(r, 255)], axis=-1).clip(0, 255).astype(np.uint8)
    background = Image.fromarray(sky, "RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=22, fill=255)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.paste(background, (0, 0), mask)

    rng = np.random.default_rng(7)
    draw = ImageDraw.Draw(img)
    for _ in range(30):
        sx, sy = rng.integers(8, size - 8, size=2)
        draw.point((int(sx), int(sy)), fill=(255, 255, 255, int(rng.integers(80, 220))))

    # Радужный хвост: изогнутая лента, сужающаяся к началу, с мягким ореолом.
    trail = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    td = ImageDraw.Draw(trail)
    points = []
    for i in range(80):
        t = i / 79
        points.append((8 + 46 * t + 10 * math.sin(math.pi * t), 122 - 50 * t + 12 * math.sin(math.pi * t)))
    for i in range(len(points) - 1):
        t = i / len(points)
        cr, cg, cb = hsv(0.75 - t * 0.85)
        td.line([points[i], points[i + 1]], fill=(cr, cg, cb, int(80 + 175 * t)), width=int(2 + 9 * t))
    img.alpha_composite(trail.filter(ImageFilter.GaussianBlur(5)))
    img.alpha_composite(trail)

    arrow = item_texture().resize((80, 80), Image.NEAREST)
    halo = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    silhouette = Image.new("RGBA", arrow.size, (255, 50, 70, 255))
    halo.paste(silhouette, (44, 0), arrow.split()[3])
    img.alpha_composite(halo.filter(ImageFilter.GaussianBlur(6)))
    img.alpha_composite(arrow, (44, 0))
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
                        "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", "-fflags", "+bitexact",
                        "-flags:a", "+bitexact", "-serial_offset", "1", str(target)], check=True)


def main() -> int:
    (ASSETS / "textures/item").mkdir(parents=True, exist_ok=True)
    (ASSETS / "textures/entity").mkdir(parents=True, exist_ok=True)
    (ASSETS / "sounds").mkdir(parents=True, exist_ok=True)

    item_texture().save(ASSETS / "textures/item/andryushka_arrow.png")
    model_texture().save(ASSETS / "textures/entity/andryushka_arrow.png")
    glow_texture().save(ASSETS / "textures/entity/andryushka_arrow_glow.png")
    trail_texture().save(ASSETS / "textures/entity/andryushka_arrow_trail.png")
    mod_icon().save(ASSETS / "icon.png")

    write_ogg(whistle(), ASSETS / "sounds/whistle.ogg")
    write_ogg(respond(), ASSETS / "sounds/respond.ogg")
    write_ogg(launch(), ASSETS / "sounds/launch.ogg")
    # Рок-звуки — бас полёта, гитарные удары и рок серии — собирает tools/rock_sounds.py.
    print("Ассеты обновлены в", ASSETS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
