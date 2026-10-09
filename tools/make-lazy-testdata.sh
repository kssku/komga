#!/usr/bin/env bash
# 造 Komga LAZY 缩略图测试数据
# 混合：几本真实可读 CBZ + 一批空壳 zip
# 目标：/opt/clouddrive2/115open/test/
set -euo pipefail

ROOT="/opt/clouddrive2/115open/test"
REAL_DIR="$ROOT/real-cbz"
SHELL_DIR="$ROOT/shell-zip"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$REAL_DIR" "$SHELL_DIR"

# ---------- 1. 几本真实可读 CBZ ----------
# 每本 3~6 页，页数不同，方便观察抽页/生成缩略图行为
make_real_cbz() {
  local name="$1" pages="$2" color="$3"
  local d="$WORK/$name"
  mkdir -p "$d"
  for i in $(seq 1 "$pages"); do
    python3 - "$d/$(printf '%03d' "$i").png" "$name p$i" "$color" <<'PY'
import sys
from PIL import Image, ImageDraw
out, label, color = sys.argv[1], sys.argv[2], sys.argv[3]
img = Image.new("RGB", (800, 1200), color)
d = ImageDraw.Draw(img)
d.rectangle([40, 40, 760, 1160], outline="white", width=6)
d.text((60, 60), label, fill="white")
img.save(out, "PNG")
PY
  done
  ( cd "$d" && zip -q -X "$REAL_DIR/$name.cbz" ./*.png )
  echo "  real: $name.cbz ($pages 页)"
}

echo "[1/2] 造真实 CBZ ..."
make_real_cbz "Real-Alpha-001" 3 "#2E4057"
make_real_cbz "Real-Beta-002"  5 "#8B4513"
make_real_cbz "Real-Gamma-003" 6 "#355E3B"
make_real_cbz "Real-Delta-004" 4 "#7B2D8E"

# ---------- 2. 一批空壳 zip ----------
# 空壳 = 含一张极小 png 的 zip，能通过 zip 探测但内容极简
# 用途：压全库扫描数量，观察 LAZY 下是否真的不开包
echo "[2/2] 造空壳 zip ..."
SHELL_PNG="$WORK/shell.png"
python3 - "$SHELL_PNG" <<'PY'
import sys
from PIL import Image
Image.new("RGB", (4, 6), "black").save(sys.argv[1], "PNG")
PY

for n in $(seq -w 1 30); do
  d="$WORK/shell-$n"
  mkdir -p "$d"
  cp "$SHELL_PNG" "$d/001.png"
  ( cd "$d" && zip -q -X "$SHELL_DIR/Shell-$(printf '%03d' "$((10#$n))" ).cbz" ./001.png )
done
echo "  shell: 30 个空壳 cbz"

echo
echo "完成。"
echo "  真实: $REAL_DIR  ($(ls -1 "$REAL_DIR" | wc -l) 个)"
echo "  空壳: $SHELL_DIR ($(ls -1 "$SHELL_DIR" | wc -l) 个)"
du -sh "$REAL_DIR" "$SHELL_DIR"
