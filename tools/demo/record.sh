#!/usr/bin/env bash
# Записывает демонстрационный ролик мода: виртуальный экран Xvfb, программный OpenGL (Mesa),
# виртуальная звуковая карта PulseAudio и ffmpeg. Запуск из корня репозитория на Linux:
#     tools/demo/record.sh [папка-для-результата]
# Нужны xvfb, pulseaudio, ffmpeg и Mesa. Сценарий ролика — src/demo.
set -euo pipefail

OUT=${1:-build/demo}
mkdir -p "$OUT" run-demo
cp tools/demo/options.txt run-demo/options.txt
rm -f run-demo/demo-markers.txt
rm -rf run-demo/saves/olegus-demo

# Скачиваем ресурсы игры и компилируем заранее, чтобы запись не начиналась с долгой сборки.
./gradlew --no-daemon downloadAssets demoClasses

export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
Xvfb :99 -screen 0 1280x720x24 -nolisten tcp &
XVFB=$!
pulseaudio --start --exit-idle-time=-1 || true
pactl load-module module-null-sink sink_name=demo >/dev/null || true
pactl set-default-sink demo || true
sleep 2

AUDIO=()
if pactl info >/dev/null 2>&1; then
  AUDIO=(-f pulse -i demo.monitor)
fi

RECORD_START=$(date +%s%3N)
ffmpeg -loglevel error -y -f x11grab -video_size 1280x720 -framerate 30 -i :99 "${AUDIO[@]}" \
  -c:v libx264 -preset ultrafast -crf 18 -c:a aac -b:a 160k "$OUT/raw.mkv" &
FFMPEG=$!

timeout 1200 ./gradlew --no-daemon runDemo || echo "runDemo завершился с ошибкой"

kill -INT "$FFMPEG" 2>/dev/null || true
wait "$FFMPEG" || true
kill "$XVFB" 2>/dev/null || true
cp run-demo/logs/latest.log "$OUT/latest.log" 2>/dev/null || true

if ! grep -q '^end' run-demo/demo-markers.txt 2>/dev/null; then
  echo "Сценарий не дошёл до конца — сохраняю сырую запись целиком"
  ffmpeg -loglevel error -y -i "$OUT/raw.mkv" -c:v libx264 -preset medium -crf 26 -pix_fmt yuv420p \
    -c:a aac -b:a 128k -movflags +faststart "$OUT/olegus-arrow-demo.mp4" || true
  exit 1
fi

# Обрезаем загрузку игры: оставляем ровно сценарий (по отметкам, которые он пишет).
read -r FROM LEN < <(python3 - "$RECORD_START" <<'PY'
import sys
record_start = int(sys.argv[1])
marks = dict(line.split() for line in open("run-demo/demo-markers.txt"))
start, end = int(marks["start"]), int(marks["end"])
print(max(0.0, (start - record_start) / 1000 - 0.5), (end - start) / 1000 + 1.0)
PY
)
ffmpeg -loglevel error -y -ss "$FROM" -i "$OUT/raw.mkv" -t "$LEN" \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -movflags +faststart \
  "$OUT/olegus-arrow-demo.mp4"
rm -f "$OUT/raw.mkv"
echo "Ролик: $OUT/olegus-arrow-demo.mp4"
