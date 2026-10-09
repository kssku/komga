#!/bin/bash
# 隔离测试实例：验证 LAZY 缩略图行为
# 库根 = test/pika-format（14 个测试 cbz），独立数据目录，独立端口 25601
set -e

JAR=/root/GitHub/komga/komga/build/libs/komga-1.27.0.jar
CONFIG_DIR=/root/GitHub/komga/.test-komga
LIB_ROOT=/opt/clouddrive2/115open/test/pika-format
PORT=25601

if [ ! -f "$JAR" ]; then echo "jar 不存在: $JAR"; exit 1; fi
if [ ! -d "$LIB_ROOT" ]; then echo "库根不存在: $LIB_ROOT"; exit 1; fi

mkdir -p "$CONFIG_DIR"

export KOMGA_CONFIGDIR="$CONFIG_DIR"
export KOMGA_THUMBNAIL_MODE=lazy
export SERVER_PORT=$PORT
export KOMGA_LIBRARY_SCAN_STARTUP=false

cd "$CONFIG_DIR"
exec java -jar "$JAR"
