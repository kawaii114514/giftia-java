#!/usr/bin/env bash
set -euo pipefail

GIFTIA_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$GIFTIA_ROOT"
echo "Giftia — Spring Boot 后端与记忆花园"

if [[ ! -f .env ]]; then
    echo "[错误] 请先执行 cp .env.example .env，再填写模型密钥。" >&2
    exit 1
fi
for tool in java javac node npm; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[错误] 缺少 $tool；需要完整 JDK 17+ 和 Node.js 18+（含 npm）。" >&2
        exit 1
    fi
done
GIFTIA_JAVA_VERSION="$(java -version 2>&1)"
if [[ ! $GIFTIA_JAVA_VERSION =~ version[^0-9]*([0-9]+) ]] || (( BASH_REMATCH[1] < 17 )); then
    echo "[错误] 需要 JDK 17 或更新版本。" >&2
    exit 1
fi
node -e "if (Number(process.versions.node.split('.')[0]) < 18) { console.error('[错误] 需要 Node.js 18 或更新版本。'); process.exit(1); }"
if [[ ! -f backend-java/mvnw ]]; then
    echo "[错误] 缺少 backend-java/mvnw，请恢复 Maven wrapper。" >&2
    exit 1
fi

echo "[1/4] 检查本地端口 8000 和 3000…"
node <<'NODE'
const net = require('node:net');
Promise.all([8000, 3000].map(port => new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', () => reject(new Error(`端口 ${port} 已被占用或无法使用；请先确认并停止原服务。`)));
  server.listen(port, '127.0.0.1', () => server.close(resolve));
}))).catch(error => { console.error(`[错误] ${error.message}`); process.exitCode = 1; });
NODE

echo "[2/4] 打包 Spring Boot 后端…"
(cd backend-java && sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package)
echo "[3/4] 检查前端依赖…"
if [[ ! -d frontend/node_modules ]]; then
    (cd frontend && npm install)
fi
mkdir -p .run

GIFTIA_BACKEND_PID=""
GIFTIA_FRONTEND_PID=""
cleanup() {
    trap - EXIT
    for pid in "$GIFTIA_BACKEND_PID" "$GIFTIA_FRONTEND_PID"; do
        if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

echo "[4/4] 启动后端与前端…"
(exec java -jar backend-java/target/giftia-backend-1.0.0.jar --server.address=127.0.0.1 --server.port=8000) >.run/backend.log 2>&1 &
GIFTIA_BACKEND_PID=$!
(cd frontend && exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 3000 --strictPort) >.run/frontend.log 2>&1 &
GIFTIA_FRONTEND_PID=$!
printf '%s\n' "$GIFTIA_BACKEND_PID" >.run/backend.pid
printf '%s\n' "$GIFTIA_FRONTEND_PID" >.run/frontend.pid
echo "服务正在启动，日志位于 $GIFTIA_ROOT/.run/"
echo "前端：http://127.0.0.1:3000"
echo "后端：http://127.0.0.1:8000；健康检查：/api/health"
echo "按 Ctrl+C 停止本次启动的两个进程。"
while kill -0 "$GIFTIA_BACKEND_PID" 2>/dev/null && kill -0 "$GIFTIA_FRONTEND_PID" 2>/dev/null; do
    sleep 1
done
echo "[错误] 一个服务已经退出，请检查 .run 中的日志。" >&2
exit 1
