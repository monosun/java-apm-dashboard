#!/usr/bin/env bash
# ============================================================
#  shutdown.sh  —  Java APM Dashboard v1.11.0  Stop
#
#  Usage:
#    ./shutdown.sh              Default port 9090
#    ./shutdown.sh 8080         Specify port
#
#  Steps:
#    1. Health check — exit immediately if server already down
#    2. Read PID from logs/monitor.pid
#    3. Fallback: pgrep by JAR name
#    4. Fallback: fuser by port
#    5. Send SIGTERM, wait up to 15s -> SIGKILL if not stopped
#    6. Delete PID file
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$LOG_DIR/monitor.pid"
PORT="${1:-9090}"

# ── Color output ──────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

echo ""
echo " ╔════════════════════════════════════════════╗"
echo " ║  Java APM Dashboard v1.11.0  |  Stop      ║"
echo " ╚════════════════════════════════════════════╝"
echo ""

# ── [1] Health check — exit if already down ───────────────────
if ! curl -sf "http://localhost:$PORT/health" &>/dev/null; then
    info "Server not responding. Already stopped."
    rm -f "$PID_FILE"
    exit 0
fi
info "Server is up (port $PORT). Proceeding with shutdown..."

# ── [2] Read PID from file ────────────────────────────────────
FOUND_PID=""
if [[ -f "$PID_FILE" ]]; then
    CANDIDATE=$(cat "$PID_FILE")
    if kill -0 "$CANDIDATE" 2>/dev/null; then
        FOUND_PID="$CANDIDATE"
    else
        info "Process from PID file ($CANDIDATE) is already gone."
        rm -f "$PID_FILE"
    fi
fi

# ── [3] Fallback: pgrep by JAR name ───────────────────────────
if [[ -z "$FOUND_PID" ]]; then
    info "No PID file. Searching for java-monitor process..."
    FOUND_PID=$(pgrep -f "java-monitor" 2>/dev/null | head -1 || true)
fi

# ── [4] Fallback: fuser by port ───────────────────────────────
if [[ -z "$FOUND_PID" ]]; then
    info "Searching by port $PORT..."
    FOUND_PID=$(fuser "${PORT}/tcp" 2>/dev/null | awk '{print $1}' || true)
fi

if [[ -z "$FOUND_PID" ]]; then
    warn "No running process found."
    exit 1
fi

# ── [5] Send SIGTERM (Graceful Shutdown) ──────────────────────
info "Sending SIGTERM... (PID: $FOUND_PID)"
kill -TERM "$FOUND_PID" 2>/dev/null || true

# Wait up to 15s
STOPPED=0
for i in $(seq 1 15); do
    sleep 1
    if ! kill -0 "$FOUND_PID" 2>/dev/null; then
        STOPPED=1
        break
    fi
    printf "."
done
echo ""

if [[ $STOPPED -eq 1 ]]; then
    info "Server stopped gracefully. (PID: $FOUND_PID)"
    rm -f "$PID_FILE"
    exit 0
fi

# ── [6] SIGKILL (force stop) ──────────────────────────────────
warn "Process did not stop within 15s. Sending SIGKILL..."
kill -KILL "$FOUND_PID" 2>/dev/null || true
sleep 2

if ! kill -0 "$FOUND_PID" 2>/dev/null; then
    info "Process killed. (PID: $FOUND_PID)"
    rm -f "$PID_FILE"
    exit 0
else
    error "Failed to stop the process."
    error "Stop manually: kill -9 $FOUND_PID"
    exit 1
fi
