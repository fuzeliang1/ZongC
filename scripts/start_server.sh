#!/usr/bin/env bash
# 编译并启动数字世界服务端。启动后浏览器打开 http://localhost:8080
# 额外参数会传给服务端，例如：scripts/start_server.sh --config server/config/my.json
set -e
cd "$(dirname "$0")/.."
scripts/build.sh
CONFIG=server/config/default.json
for arg in "$@"; do [ "$arg" = "--config" ] && CONFIG=""; done
exec java -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
  -cp server/build/classes:server/lib/gson-2.13.1.jar \
  com.zongc.dw.DwServer ${CONFIG:+--config "$CONFIG"} "$@"
