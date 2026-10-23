#!/usr/bin/env bash
# 一键演示：启动服务端（运行"期末演示"场景脚本，全程自动）+ 5 辆模拟车（代替 Matlab）+ 1 部模拟手机，
# 并打开浏览器。Ctrl+C 结束全部进程。
set -e
cd "$(dirname "$0")/.."
scripts/start_server.sh --scenario demo "$@" > server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; kill $MOCK 2>/dev/null; exit 0' INT TERM EXIT
echo "等待服务端启动（日志：server.log）..."
for i in $(seq 1 60); do
  if curl -s http://localhost:8080/api/info >/dev/null 2>&1; then break; fi
  sleep 0.5
done
URL=http://localhost:8080
(command -v xdg-open >/dev/null && xdg-open "$URL") || (command -v open >/dev/null && open "$URL") || echo "请在浏览器打开 $URL"
python3 tools/mock_devices.py --cars 5 --phones 1 --kind matlab &
MOCK=$!
wait $MOCK
