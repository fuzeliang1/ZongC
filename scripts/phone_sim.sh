#!/usr/bin/env bash
# 在电脑上模拟"拿着手机走路"，用手机 App 的同一套核心代码接入正在运行的服务端做端到端检查。
# 先启动服务端（scripts/start_server.sh），再运行：
#   scripts/phone_sim.sh [--host 127.0.0.1] [--seconds 30] [--id phone-1]
set -e
cd "$(dirname "$0")/.."
command -v javac >/dev/null || { echo "找不到 javac：请安装 JDK 17 或更新版本"; exit 1; }
OUT=phone/build/phonesim
mkdir -p "$OUT"
javac -encoding UTF-8 -nowarn -d "$OUT" \
  -sourcepath phone/app/src/main/java:phone/app/src/test/java \
  phone/app/src/test/java/com/zongc/phone/tools/PhoneSim.java
exec java -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$OUT" com.zongc.phone.tools.PhoneSim "$@"
