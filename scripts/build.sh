#!/usr/bin/env bash
# 编译服务端（只需要 JDK 17 或更新版本，不需要 Maven）。输出到 server/build/classes。
set -e
cd "$(dirname "$0")/.."
command -v javac >/dev/null || { echo "找不到 javac：请安装 JDK 17 或更新版本"; exit 1; }
mkdir -p server/build/classes
javac -encoding UTF-8 --release 17 -nowarn \
  -cp server/lib/gson-2.13.1.jar \
  -sourcepath server/src/main/java \
  -d server/build/classes \
  server/src/main/java/com/zongc/dw/DwServer.java \
  server/src/main/java/com/zongc/dw/tools/Experiment.java \
  server/src/main/java/com/zongc/dw/tools/VisionBench.java
echo "编译完成：server/build/classes"
