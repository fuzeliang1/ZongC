#!/usr/bin/env bash
# 把 docs/uml/*.mmd（mermaid 源文件）渲染成 docs/uml/png/*.png，供 Word 报告和 PPT 使用。
# 需要 Node.js 和 mermaid-cli：npm install -g @mermaid-js/mermaid-cli
# 已装 Chromium 时可设置 PUPPETEER_EXECUTABLE_PATH 指向它，免去 puppeteer 下载浏览器。
# 用法（仓库根目录）：tools/render_uml.sh [单个文件名.mmd]
set -e
cd "$(dirname "$0")/../docs/uml"
MMDC=${MMDC:-mmdc}
CFG=$(mktemp -d)
# 中文字体 + 白底，适合打印
cat > "$CFG/config.json" <<'JSON'
{"theme":"neutral","themeVariables":{"fontFamily":"Microsoft YaHei, PingFang SC, WenQuanYi Zen Hei, sans-serif"}}
JSON
EXE=""
[ -n "$PUPPETEER_EXECUTABLE_PATH" ] && EXE=",\"executablePath\":\"$PUPPETEER_EXECUTABLE_PATH\""
echo "{\"args\":[\"--no-sandbox\"]$EXE}" > "$CFG/puppeteer.json"
mkdir -p png
for f in ${1:-*.mmd}; do
    out="png/${f%.mmd}.png"
    echo "渲染 $f → $out"
    "$MMDC" -q -c "$CFG/config.json" -p "$CFG/puppeteer.json" -b white -s 2 -i "$f" -o "$out"
done
rm -rf "$CFG"
