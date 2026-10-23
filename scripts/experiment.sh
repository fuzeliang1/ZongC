#!/usr/bin/env bash
# 离线对比实验（不需要启动服务端）。参数示例：
#   scripts/experiment.sh --minutes 30 --seeds 1,2,3 --strategies GREEDY,HUNGARIAN --taxis 12 --rate 12 --csv results/cmp.csv
set -e
cd "$(dirname "$0")/.."
scripts/build.sh >/dev/null
mkdir -p results
exec java -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
  -cp server/build/classes:server/lib/gson-2.13.1.jar \
  com.zongc.dw.tools.Experiment --config server/config/default.json "$@"
