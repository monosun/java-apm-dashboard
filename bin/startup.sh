#!/usr/bin/env bash
# ============================================================
#  startup.sh  —  Java APM Dashboard v1.11.0  Background Start
#
#  Usage:
#    ./startup.sh              Start with default port 9090
#    ./startup.sh 8080         Specify port
#
#  Steps:
#    1. Prevent duplicate launch (PID file + kill -0 check)
#    2. Auto-locate latest JAR under target/
#    3. Start in background with nohup, output to logs/monitor.log
#    4. Save PID to logs/monitor.pid
#    5. Open browser automatically after health check passes
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$LOG_DIR/monitor.pid"
LOG_FILE="$LOG_DIR/monitor.log"
PORT="${1:-9090}"
DASHBOARD="http://localhost:$PORT/dashboard"

JVM_OPTS=(
    -Xms256m
    -Xmx512m
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+HeapDumpOnOutOfMemoryError
    "-XX:HeapDumpPath=$LOG_DIR/heapdump.hprof"
    "-Xlog:gc*:file=$LOG_DIR/gc.log:time:filecount=3,filesize=10m"
    "-Djava.util.logging.config.file=$SCRIPT_DIR/logging.properties"
    "-Dserver.http.port=$PORT"
)

# ── Color output ──────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

echo ""
echo " ╔════════════════════════════════════════════╗"
echo " ║  Java APM Dashboard v1.11.0  |  Start     ║"
echo " ║  Dashboard : $DASHBOARD"
echo " ╚════════════════════════════════════════════╝"
echo ""

# ── [1] Create logs directory ─────────────────────────────────
mkdir -p "$LOG_DIR"

# ── [2] Check Java ────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    error "Java not found. Check JAVA_HOME or PATH."
    exit 1
fi
info "Java  : $(java -version 2>&1 | head -1)"

# ── [3] Prevent duplicate launch ──────────────────────────────
if [[ -f "$PID_FILE" ]]; then
    EXISTING_PID=$(cat "$PID_FILE")
    if kill -0 "$EXISTING_PID" 2>/dev/null; then
        warn "Already running. PID: $EXISTING_PID"
        warn "To stop: ./bin/shutdown.sh"
        exit 0
    fi
    info "Removing stale PID file (PID: $EXISTING_PID is no longer running)"
    rm -f "$PID_FILE"
fi

# ── [4] Find JAR (exclude original / agent / integration) ─────
JAR=""
for f in "$PROJECT_DIR"/target/java-monitor-*.jar; do
    [[ "$f" == *original* || "$f" == *agent* || "$f" == *integration* ]] && continue
    JAR="$f"
done

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    error "No built JAR file found."
    error "Run build first: cd '$PROJECT_DIR' && mvn package -DskipTests"
    exit 1
fi
info "JAR   : $JAR"

# ── [5] Start in background with nohup ────────────────────────
info "Starting server (nohup -> $LOG_FILE)..."
nohup java "${JVM_OPTS[@]}" -jar "$JAR" >> "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
info "PID   : $PID   saved: $PID_FILE"
info "Log   : $LOG_FILE"

# ── [6] Wait for health check (up to 30s) ─────────────────────
info "Waiting for server to be ready..."
READY=0
for i in $(seq 1 30); do
    sleep 1
    if curl -sf "http://localhost:$PORT/health" &>/dev/null; then
        READY=1
        break
    fi
    printf "."
done
echo ""

if [[ $READY -eq 1 ]]; then
    info "Server started successfully."
    info "Dashboard : $DASHBOARD"
    # Auto-open browser (Linux: xdg-open, macOS: open)
    if command -v xdg-open &>/dev/null; then
        xdg-open "$DASHBOARD" &>/dev/null & disown
    elif command -v open &>/dev/null; then
        open "$DASHBOARD" &>/dev/null & disown
    fi
    exit 0
else
    warn "Health check timed out (30s). Check the logs:"
    warn "  tail -f $LOG_FILE"
    exit 1
fi
