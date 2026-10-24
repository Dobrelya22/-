#!/usr/bin/env python3
"""Создаёт пустую структуру-арену для игровых тестов.

Стандартная пустая структура Fabric крошечная, поэтому тесты стоят вплотную и их мобы
попадают в служебные блоки соседей. Арена 24×12×24 без блоков даёт каждому тесту свой
расчищенный участок. Запуск из корня репозитория:
    python3 tools/generate_test_arena.py
"""

import gzip
import struct
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "src/gametest/resources/data/andryushka_arrow_gametest/structure/arena.nbt"
SIZE = (24, 12, 24)
DATA_VERSION = 3955  # Minecraft 1.21.1

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(text: str) -> bytes:
    raw = text.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def int_tag(key: str, value: int) -> bytes:
    return bytes([TAG_INT]) + name(key) + struct.pack(">i", value)


def list_tag(key: str, element_type: int, payloads: list) -> bytes:
    return bytes([TAG_LIST]) + name(key) + bytes([element_type]) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def compound_payload(*entries: bytes) -> bytes:
    return b"".join(entries) + bytes([TAG_END])


def main() -> None:
    air = compound_payload(bytes([TAG_STRING]) + name("Name") + name("minecraft:air"))
    root = bytes([TAG_COMPOUND]) + name("") + compound_payload(
        list_tag("size", TAG_INT, [struct.pack(">i", v) for v in SIZE]),
        list_tag("palette", TAG_COMPOUND, [air]),
        list_tag("blocks", TAG_COMPOUND, []),
        list_tag("entities", TAG_COMPOUND, []),
        int_tag("DataVersion", DATA_VERSION),
    )
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    # mtime=0 — чтобы файл не менялся от запуска к запуску.
    with open(TARGET, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as out:
        out.write(root)
    print("Арена записана в", TARGET)


if __name__ == "__main__":
    main()
