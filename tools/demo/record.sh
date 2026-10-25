#!/usr/bin/env bash
# Записывает демонстрационный ролик мода: виртуальный экран Xvfb, программный OpenGL (Mesa)
# и ffmpeg. Запуск из корня репозитория на Linux:
#     tools/demo/record.sh [папка-для-результата] [озвучка.wav] [метки-озвучки.txt]
# Нужны xvfb, pulseaudio, ffmpeg, Mesa, python3 с numpy. Сценарий ролика — src/demo,
# озвучка и метки — tools/demo/narration.py. Без меток сценарий берёт секунды из narration.txt.
# DEMO_SCENARIO=update снимает дубли апдейт-ролика (tools/demo/update-takes.txt) и, если задан
# DEMO_OLD_FLIGHT (звук полёта 1.2.1), пишет ещё дорожку sfx-before.wav со звуками той версии.
#
# Программный OpenGL рисует лишь несколько кадров в секунду, поэтому игра идёт в DEMO_DILATION
# раз медленнее (/tick rate), а запись потом ускоряется до 60 кадров в секунду. Звук собирается
# из журнала звуков игры (tools/demo/sound_track.py) уже в масштабе ролика.
set -euo pipefail

OUT=${1:-build/demo}
SCENARIO=${DEMO_SCENARIO:-review}
LINES=tools/demo/narration.txt
[ "$SCENARIO" = update ] && LINES=tools/demo/update-takes.txt
export ANDRYUSHKA_DEMO_SCENARIO=$SCENARIO
NARRATION=${2:-}
CUES=${3:-}
DILATION=${DEMO_DILATION:-3}
# По умолчанию ровно 60 / замедление: тогда каждый кадр ролика — отдельный кадр игры, без
# пропусков и повторов при пересчёте в 60 кадров.
CAPTURE_FPS=${DEMO_CAPTURE_FPS:-$(python3 -c "print(round(60 / $DILATION, 4))")}
mkdir -p "$OUT" run-demo
rm -f run-demo/demo-markers.txt run-demo/demo-sounds.txt
rm -rf run-demo/saves/andryushka-demo
# Блеск зачарования анимируется по настоящим часам, а не по тикам — замедляем его вместе с игрой.
{
  grep -v '^glintSpeed:' tools/demo/options.txt
  echo "glintSpeed:$(python3 -c "print(round(0.5 / $DILATION, 5))")"
} > run-demo/options.txt

# Скачиваем ресурсы игры и компилируем заранее, чтобы запись не начиналась с долгой сборки.
./gradlew --no-daemon downloadAssets demoClasses

export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
ANDRYUSHKA_DEMO_TICK_RATE=$(python3 -c "print(20 / $DILATION)")
export ANDRYUSHKA_DEMO_TICK_RATE
if [ -n "$CUES" ] && [ -f "$CUES" ]; then
  ANDRYUSHKA_DEMO_CUES=$(realpath "$CUES")
  export ANDRYUSHKA_DEMO_CUES
fi
# Сколько ждать игру: длина сценария × замедление плюс запас на загрузку.
SCRIPT_SECONDS=$(python3 - "${ANDRYUSHKA_DEMO_CUES:-}" "$LINES" <<'PY'
import sys
path, lines = sys.argv[1], sys.argv[2]
if path:
    ends = [float(line.split()[1]) for line in open(path) if line.startswith("end ")]
else:
    ends = [float(line.split()[0]) for line in open(lines) if line.strip() and not line.startswith("#")]
print(int(max(ends)) + 1)
PY
)
GAME_TIMEOUT=$(( SCRIPT_SECONDS * DILATION + 900 ))
echo "Сценарий ${SCRIPT_SECONDS} с, замедление ×${DILATION}, жду игру до ${GAME_TIMEOUT} с"
Xvfb :99 -screen 0 1280x720x24 -nolisten tcp &
XVFB=$!
# Звуковая карта нужна игре, чтобы звуки «играли» и попадали в журнал; сама запись — без звука.
pulseaudio --start --exit-idle-time=-1 || true
pactl load-module module-null-sink sink_name=demo >/dev/null || true
pactl set-default-sink demo || true
sleep 2

ffmpeg -loglevel info -nostats -y -f x11grab -video_size 1280x720 -framerate "$CAPTURE_FPS" -i :99 \
  -c:v libx264 -preset ultrafast -crf 17 -threads 2 "$OUT/raw.mkv" 2> "$OUT/capture.log" &
FFMPEG=$!

timeout "$GAME_TIMEOUT" ./gradlew --no-daemon runDemo || echo "runDemo завершился с ошибкой"

STOP_TIME=$(date +%s%3N)
kill -INT "$FFMPEG" 2>/dev/null || true
wait "$FFMPEG" || true
kill "$XVFB" 2>/dev/null || true
cp run-demo/logs/latest.log "$OUT/latest.log" 2>/dev/null || true
cp run-demo/demo-markers.txt run-demo/demo-sounds.txt "$OUT/" 2>/dev/null || true

if ! grep -q '^end' run-demo/demo-markers.txt 2>/dev/null; then
  echo "Сценарий не дошёл до конца — сохраняю сырую запись целиком"
  ffmpeg -loglevel error -y -i "$OUT/raw.mkv" -c:v libx264 -preset medium -crf 26 -pix_fmt yuv420p \
    -movflags +faststart "$OUT/andryushka-arrow-demo.mp4" || true
  exit 1
fi

# Обрезаем загрузку игры: оставляем ровно сценарий (по отметкам, которые он пишет). Кадры x11grab
# помечены настоящим временем, и ffmpeg печатает время первого кадра («start:»); если его нет —
# считаем начало записи от её остановки назад на длительность файла.
RAW_DURATION=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/raw.mkv")
FIRST_FRAME=$(grep -m1 -oP 'start: \K[0-9.]+' "$OUT/capture.log" || echo 0)
read -r FROM LEN < <(python3 - "$STOP_TIME" "$RAW_DURATION" "$FIRST_FRAME" <<'PY'
import sys
stop, duration, first = int(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
capture_start = first * 1000 if first > 1e9 else stop - duration * 1000
marks = dict(line.split() for line in open("run-demo/demo-markers.txt"))
start, end = int(marks["start"]), int(marks["end"])
print(max(0.0, (start - capture_start) / 1000), (end - start) / 1000)
PY
)
echo "Запись: начало сценария на ${FROM} с, длина ${LEN} с, замедление ×${DILATION}"
# Замедление нужно, чтобы перевести время из журнала звуков во время ролика (монтаж апдейт-ролика).
echo "$DILATION" > "$OUT/dilation.txt"
ffmpeg -loglevel error -y -ss "$FROM" -t "$LEN" -i "$OUT/raw.mkv" \
  -vf "setpts=(PTS-STARTPTS)/$DILATION,fps=60,format=yuv420p" -an \
  -c:v libx264 -preset medium -crf 20 "$OUT/video.mp4"
# Сколько разных кадров получилось на самом деле (на первых двух минутах) — для проверки плавности.
DISTINCT=$(ffmpeg -hide_banner -t 120 -i "$OUT/video.mp4" -vf mpdecimate,showinfo -f null - 2>&1 | grep -c 'pts_time' || true)
echo "Неповторяющихся кадров за первые 2 минуты: $DISTINCT из 7200"

python3 tools/demo/sound_track.py run-demo/demo-sounds.txt run-demo/demo-markers.txt "$DILATION" "$OUT/sfx.wav"
if [ -n "${DEMO_OLD_FLIGHT:-}" ]; then
  python3 tools/demo/sound_track.py run-demo/demo-sounds.txt run-demo/demo-markers.txt "$DILATION" "$OUT/sfx-before.wav" \
    --before --old-flight "$DEMO_OLD_FLIGHT"
fi

if [ -n "$NARRATION" ] && [ -f "$NARRATION" ]; then
  # Голос поверх звуков игры; звуки приглушаются, пока говорит голос, а громкие (свист)
  # поджимаются компрессором, чтобы не перекрикивать голос.
  ffmpeg -loglevel error -y -i "$OUT/sfx.wav" -i "$NARRATION" -filter_complex \
    "[1:a]aformat=sample_rates=48000:channel_layouts=stereo,asplit=2[voice][key];\
     [0:a]acompressor=threshold=0.05:ratio=4:attack=5:release=250,volume=1.4[game];[game][key]sidechaincompress=threshold=0.03:ratio=4:attack=20:release=400[ducked];\
     [ducked][voice]amix=inputs=2:duration=first:normalize=0[audio]" \
    -map "[audio]" -c:a pcm_f32le "$OUT/mix.wav"
else
  cp "$OUT/sfx.wav" "$OUT/mix.wav"
fi
# Громкость как у роликов в сети: −16 LUFS, пики ограничителем не выше −1 дБ.
LOUDNESS=$(ffmpeg -hide_banner -i "$OUT/mix.wav" -af ebur128 -f null - 2>&1 | grep -oP '^\s*I:\s+\K-?[0-9.]+' | tail -1 || true)
GAIN=$(python3 -c "print(round(-16 - float('${LOUDNESS:--16}'), 2))")
echo "Громкость смеси ${LOUDNESS} LUFS, усиление ${GAIN} дБ"
ffmpeg -loglevel error -y -i "$OUT/video.mp4" -i "$OUT/mix.wav" -map 0:v -map 1:a \
  -af "volume=${GAIN}dB,alimiter=limit=0.89:attack=2:release=60:level=0" \
  -c:v copy -c:a aac -b:a 160k -shortest -movflags +faststart "$OUT/andryushka-arrow-demo.mp4"
rm -f "$OUT/raw.mkv" "$OUT/mix.wav"
echo "Ролик: $OUT/andryushka-arrow-demo.mp4"
