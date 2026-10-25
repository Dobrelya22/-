#!/usr/bin/env python3
"""Звуковая дорожка демо-ролика из журнала звуков игры.

Игра при записи замедлена, поэтому звук с колонок не годится. Демо-сценарий пишет
в demo-sounds.txt, какой звук прозвучал, когда, где и с какой громкостью, а этот скрипт
собирает из тех же файлов звуков дорожку в масштабе ролика: с затуханием по расстоянию,
стереопанорамой относительно камеры и высотой тона, как в игре. Запуск:
    sound_track.py demo-sounds.txt demo-markers.txt ЗАМЕДЛЕНИЕ sfx.wav
С --before --old-flight flight-1.2.1.ogg та же запись звучит как в версии 1.2.1 — для роликов
«как было — как стало»: флейта вместо баса, стук стрелы вместо гитары, звон вместо гитарных фраз
и без музыки серии.
Нужны numpy и ffmpeg.
"""
import argparse
import bisect
import json
import math
import os
import subprocess
import sys
import wave
from collections import Counter
from pathlib import Path

import numpy as np

RATE = 48000
# Насколько разводить звук по каналам: 1 — полностью влево/вправо.
PAN_WIDTH = 0.6
MOD_RESOURCES = Path("src/main/resources/assets")
FLIGHT = "andryushka_arrow:sounds/flight.ogg"
# Музыка серии появилась после 1.2.1 — в «как было» её нет.
NEW_MUSIC = ("combo_rock", "combo_epic", "combo_end")


def find_assets(explicit):
    candidates = [explicit] if explicit else []
    home = os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
    candidates.append(str(Path(home) / "caches" / "fabric-loom" / "assets"))
    for candidate in candidates:
        indexes = sorted(Path(candidate).glob("indexes/*.json"), key=lambda p: p.stat().st_size)
        if indexes:
            objects = json.loads(indexes[-1].read_text())["objects"]
            return Path(candidate) / "objects", objects
    sys.exit("Не найдены ресурсы игры (assets/indexes) — сначала ./gradlew downloadAssets")


class Library:
    """Файлы звуков игры и мода, декодированные в моно 48 кГц."""

    def __init__(self, objects_dir, index):
        self.objects_dir = objects_dir
        self.index = index
        self.cache = {}

    def path(self, location):
        namespace, path = location.split(":", 1)
        if namespace == "file":
            return Path(path)
        if namespace == "minecraft":
            entry = self.index.get(f"minecraft/{path}")
            if entry:
                return self.objects_dir / entry["hash"][:2] / entry["hash"]
            return None
        local = MOD_RESOURCES / namespace / path
        return local if local.exists() else None

    def get(self, location):
        if location not in self.cache:
            path = self.path(location)
            pcm = None
            if path is not None:
                raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ac", "1",
                                      "-ar", str(RATE), "-"], check=True, capture_output=True).stdout
                pcm = np.frombuffer(raw, np.float32).copy()
            self.cache[location] = pcm
        return self.cache[location]


class Camera:
    def __init__(self):
        self.times = []
        self.poses = []

    def add(self, t, x, y, z, yaw):
        self.times.append(t)
        self.poses.append((x, y, z, yaw))

    def at(self, t):
        if not self.poses:
            return 0.0, 0.0, 0.0, 0.0
        i = max(0, bisect.bisect_right(self.times, t) - 1)
        return self.poses[i]


def spatial(camera, t, pos, max_distance, relative):
    """Громкость левого и правого канала для звука в точке pos."""
    if relative or max_distance is None:
        return math.sqrt(0.5), math.sqrt(0.5)
    cx, cy, cz, yaw = camera.at(t)
    dx, dy, dz = pos[0] - cx, pos[1] - cy, pos[2] - cz
    distance = math.sqrt(dx * dx + dy * dy + dz * dz)
    attenuation = max(0.0, 1.0 - distance / max_distance)
    pan = 0.0
    if distance > 0.5:
        radians = math.radians(yaw)
        # Правая рука игрока: при взгляде на юг (+Z) справа запад (-X).
        pan = (dx * -math.cos(radians) + dz * -math.sin(radians)) / distance * PAN_WIDTH
    angle = (pan + 1.0) * math.pi / 4.0
    return attenuation * math.cos(angle), attenuation * math.sin(angle)


def render_one_shot(track, start, pcm, pitch, left, right):
    length = int(len(pcm) / pitch)
    begin = int(start * RATE)
    if length <= 0 or begin >= track.shape[0] or begin + length <= 0:
        return
    shifted = np.interp(np.arange(length) * pitch, np.arange(len(pcm)), pcm) if pitch != 1.0 else pcm
    lo, hi = max(0, begin), min(track.shape[0], begin + length)
    piece = shifted[lo - begin:hi - begin]
    track[lo:hi, 0] += piece * left
    track[lo:hi, 1] += piece * right


def render_loop(track, pcm, points, once=False):
    """Звук, громкость и высота которого менялись каждый тик: points = [(t, left, right, pitch)].
    once — играет один раз и не повторяется, даже если остановили позже, чем он кончился."""
    if len(points) < 2:
        return
    times = np.array([p[0] for p in points])
    begin = max(0, int(times[0] * RATE))
    end = min(track.shape[0], int(times[-1] * RATE))
    if end <= begin:
        return
    sample_times = np.arange(begin, end) / RATE
    pitch = np.interp(sample_times, times, [p[3] for p in points])
    left = np.interp(sample_times, times, [p[1] for p in points])
    right = np.interp(sample_times, times, [p[2] for p in points])
    positions = np.cumsum(pitch)
    if once:
        keep = int(np.searchsorted(positions, len(pcm) - 1))
        positions, left, right, end = positions[:keep], left[:keep], right[:keep], begin + keep
        if keep < 2:
            return
    signal = np.interp(positions % len(pcm), np.arange(len(pcm)), pcm, period=len(pcm))
    # Короткие нарастание и затухание по краям, чтобы не щёлкало.
    fade = min(len(signal) // 2, int(0.03 * RATE))
    if fade > 0:
        ramp = np.linspace(0.0, 1.0, fade)
        signal[:fade] *= ramp
        signal[-fade:] *= ramp[::-1]
    track[begin:end, 0] += signal * left
    track[begin:end, 1] += signal * right


def before_sound(location, gain, pitch, old_flight):
    """Каким был бы этот звук в 1.2.1: (файл, громкость, высота) или None, если его тогда не было."""
    name = location.rsplit("/", 1)[-1]
    if location == FLIGHT:
        return "file:" + str(old_flight), gain, pitch
    if location.startswith("andryushka_arrow:sounds/strike"):
        # Был стук попадания стрелы с высотой 1,1–1,4. Тогда он звучал у моба, а гитара теперь звучит
        # у владельца (громкость 0,55): берём половину громкости — примерно столько оставалось на
        # обычном расстоянии до цели.
        number = (int(name[len("strike"):-len(".ogg")]) - 1) % 4 + 1
        return f"minecraft:sounds/random/bowhit{number}.ogg", gain / 0.55 * 0.5, 1.1 + (pitch - 0.97) / 0.06 * 0.3
    if location.startswith("andryushka_arrow:sounds/combo_lick"):
        # Был звон нотного блока: выше с каждой победой серии, 0,85 + 0,18 × серия.
        combo = int(name[len("combo_lick"):-len(".ogg")])
        return "minecraft:sounds/note/pling.ogg", gain / 0.6, min(2.0, 0.85 + 0.18 * combo)
    if any(music in location for music in NEW_MUSIC):
        return None
    return location, gain, pitch


def old_streak_chimes(epic_spans, kills):
    """В 1.2.1 звон был на каждой победе серии, а теперь с шестой победы вместо фраз играет
    пауэр-метал. Возвращаем звон на эти победы: на шестой 0,85 + 0,18 × 6, дальше — потолок 2."""
    chimes = []
    for begin, end in epic_spans:
        for i, t in enumerate(k for k in kills if begin - 0.05 <= k < (end if end is not None else 1e9)):
            chimes.append(dict(t=t, location="minecraft:sounds/note/pling.ogg", gain=1.0,
                               pitch=min(2.0, 0.85 + 0.18 * (6 + i)), pos=(0.0, 0.0, 0.0), relative=True,
                               distance=None, once=True))
    return chimes


def flight_gain_by_speed(points):
    """Громкость полёта по скорости стрелы, как в 1.2.1, — без приглушения на время рока серии."""
    gains = []
    for i, (t, gain, pitch, pos) in enumerate(points[:-1]):
        prev = points[i - 1][3] if i else pos
        speed = math.dist(pos, prev)
        gains.append((t, min(0.6, max(0.12, 0.18 + speed * 0.45)), pitch, pos))
    # Последняя точка — остановка звука, её громкость оставляем.
    return gains + points[-1:]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sounds")
    parser.add_argument("markers")
    parser.add_argument("dilation", type=float)
    parser.add_argument("out")
    parser.add_argument("--assets", help="папка ресурсов игры (с indexes/ и objects/)")
    parser.add_argument("--before", action="store_true", help="звуки версии 1.2.1 вместо нынешних")
    parser.add_argument("--old-flight", type=Path, help="звук полёта 1.2.1 (флейта) — для --before")
    args = parser.parse_args()
    if args.before and not args.old_flight:
        parser.error("для --before нужен --old-flight")

    marks = dict(line.split() for line in open(args.markers))
    start_ms, end_ms = int(marks["start"]), int(marks["end"])

    def video_time(ms):
        return (int(ms) - start_ms) / 1000.0 / args.dilation

    library = Library(*find_assets(args.assets))
    camera = Camera()
    shots = []
    loops = {}
    # Для «как было»: где шёл пауэр-метал серии и когда были победы.
    epic_spans, kills = [], []
    for line in open(args.sounds, encoding="utf-8"):
        parts = line.split()
        if not parts:
            continue
        kind, t = parts[0], video_time(parts[1])
        if kind == "camera":
            camera.add(t, *map(float, parts[2:6]))
        elif kind == "sound":
            location, gain, pitch, distance = parts[2], float(parts[3]), float(parts[4]), parts[5]
            pos = tuple(map(float, parts[6:9]))
            loop, relative = int(parts[9]), parts[10] == "rel"
            once = len(parts) > 11 and parts[11] == "once"
            if "combo_epic" in location:
                epic_spans.append([t, None])
            elif "combo_end" in location and epic_spans and epic_spans[-1][1] is None:
                epic_spans[-1][1] = t
            elif "successful_hit" in location:
                kills.append(t)
            if args.before:
                old = before_sound(location, gain, pitch, args.old_flight.resolve())
                if old is None:
                    continue
                location, gain, pitch = old
            sound = dict(t=t, location=location, gain=gain, pitch=pitch, pos=pos, relative=relative,
                         distance=None if distance == "inf" else float(distance), once=once)
            if loop:
                loops[loop] = dict(sound, points=[(t, gain, pitch, pos)])
            else:
                shots.append(sound)
        elif kind == "loop" and int(parts[2]) in loops:
            loops[int(parts[2])]["points"].append((t, float(parts[3]), float(parts[4]), tuple(map(float, parts[5:8]))))
        elif kind == "stop" and int(parts[2]) in loops:
            last = loops[int(parts[2])]["points"][-1]
            loops[int(parts[2])]["points"].append((t, 0.0, last[2], last[3]))

    if args.before:
        shots += old_streak_chimes(epic_spans, kills)

    track = np.zeros((int(video_time(end_ms) * RATE) + RATE, 2), np.float32)
    used = Counter()
    missing = set()
    for sound in shots:
        pcm = library.get(sound["location"])
        if pcm is None:
            missing.add(sound["location"])
            continue
        left, right = spatial(camera, sound["t"], sound["pos"], sound["distance"], sound["relative"])
        if left + right > 1e-4:
            render_one_shot(track, sound["t"], pcm, sound["pitch"], left * sound["gain"], right * sound["gain"])
            used[sound["location"]] += 1
    for loop in loops.values():
        pcm = library.get(loop["location"])
        if pcm is None:
            missing.add(loop["location"])
            continue
        if args.before and loop["location"].startswith("file:"):
            loop["points"] = flight_gain_by_speed(loop["points"])
        points = []
        for t, gain, pitch, pos in loop["points"]:
            left, right = spatial(camera, t, pos, loop["distance"], loop["relative"])
            points.append((t, left * gain, right * gain, pitch))
        render_loop(track, pcm, points, loop["once"])
        used[loop["location"]] += 1

    # Мягкое ограничение вместо обрезки пиков, если несколько громких звуков совпали.
    track = np.tanh(track * 1.1) / math.tanh(1.1)
    with wave.open(args.out, "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((np.clip(track, -1.0, 1.0) * 32767).astype(np.int16).tobytes())

    print(f"Звуков: {sum(used.values())}, повторяющихся: {len(loops)}, длительность {len(track) / RATE:.1f} с")
    for location, count in used.most_common(12):
        print(f"  {count:4d}  {location}")
    for location in sorted(missing):
        print(f"  нет файла: {location}")


if __name__ == "__main__":
    sys.exit(main())
