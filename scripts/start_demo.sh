#!/usr/bin/env bash
# 一键演示（全自主）：启动后不需要任何人工操作。
#   1. 服务端，运行"期末演示"场景脚本（路障、未知障碍、早晚高峰、订单潮按时间自动发生，循环播放）；
#   2. 5 辆模拟车：装了 MATLAB 就启动 Matlab 模拟车（run_fleet），否则用 Python 模拟车代替；
#   3. 1 部模拟手机车（模拟人拿着手机走走停停，走路即油门，驾驶 car-6）；
#   4. 打开浏览器：2D 主界面 + 3D 视图（自动巡游）。
# Ctrl+C 结束全部进程。
#
# 参数：--cars auto|matlab|python|none（默认 auto） --phones N（默认 1）
#       --scenario 名称（默认 demo，none 表示不运行脚本） --no-browser
set -e
cd "$(dirname "$0")/.."
CARS=auto
PHONES=1
SCENARIO=demo
BROWSER=1
while [ $# -gt 0 ]; do
  case "$1" in
    --cars) CARS="$2"; shift 2;;
    --phones) PHONES="$2"; shift 2;;
    --scenario) SCENARIO="$2"; shift 2;;
    --no-browser) BROWSER=0; shift;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done

PIDS=()
cleanup() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  exit 0
}
trap cleanup INT TERM EXIT

scripts/start_server.sh --scenario "$SCENARIO" > server.log 2>&1 &
PIDS+=($!)
echo "等待服务端启动（日志：server.log）..."
for i in $(seq 1 60); do
  if curl -s http://localhost:8080/api/info >/dev/null 2>&1; then break; fi
  sleep 0.5
done

if [ "$CARS" = "auto" ]; then
  if command -v matlab >/dev/null 2>&1; then CARS=matlab; else CARS=python; fi
fi
case "$CARS" in
  matlab)
    echo "启动 Matlab 模拟车（5 辆，MATLAB 启动约需半分钟；在此之前由数字世界内部仿真驾驶）..."
    matlab -batch "cd('$(pwd)/matlab'); run_fleet('127.0.0.1', [], false)" > matlab.log 2>&1 &
    PIDS+=($!)
    ;;
  python)
    echo "没有找到 MATLAB（或指定了 python）：用 Python 模拟车代替 Matlab 模拟车。"
    python3 tools/mock_devices.py --cars 5 --phones 0 --kind matlab > mock_cars.log 2>&1 &
    PIDS+=($!)
    ;;
  none) ;;
  *) echo "--cars 只能是 auto / matlab / python / none"; exit 2;;
esac
if [ "$PHONES" -gt 0 ]; then
  python3 tools/mock_devices.py --cars 0 --phones "$PHONES" > mock_phones.log 2>&1 &
  PIDS+=($!)
fi

if [ "$BROWSER" = 1 ]; then
  for URL in http://localhost:8080 "http://localhost:8080/3d.html?mode=tour"; do
    (command -v xdg-open >/dev/null && xdg-open "$URL") || (command -v open >/dev/null && open "$URL") || echo "请在浏览器打开 $URL"
  done
fi
echo "演示已全部启动：2D http://localhost:8080 ，3D http://localhost:8080/3d.html 。按 Ctrl+C 结束。"
wait "${PIDS[0]}"
