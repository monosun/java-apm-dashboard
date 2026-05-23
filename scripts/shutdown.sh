#!/usr/bin/env bash
# ============================================================
#  shutdown.sh  —  Java APM Dashboard v1.6.0  종료
#
#  사용법:
#    ./shutdown.sh              기본 포트 9090
#    ./shutdown.sh 8080         포트 지정
#
#  동작 순서:
#    1. 헬스 체크 — 이미 꺼져 있으면 즉시 종료
#    2. logs/monitor.pid 에서 PID 읽기
#    3. pgrep 으로 JAR 이름 기반 폴백
#    4. fuser 로 포트 기반 폴백
#    5. SIGTERM 전송 후 최대 15초 대기 → 미종료 시 SIGKILL
#    6. PID 파일 삭제
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$LOG_DIR/monitor.pid"
PORT="${1:-9090}"

# ── 컬러 출력 ─────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[정보]${NC} $*"; }
warn()  { echo -e "${YELLOW}[경고]${NC} $*"; }
error() { echo -e "${RED}[오류]${NC} $*" >&2; }

echo ""
echo " ╔════════════════════════════════════════════╗"
echo " ║   Java APM Dashboard v1.6.0  |  종료      ║"
echo " ╚════════════════════════════════════════════╝"
echo ""

# ── [1] 헬스 체크 — 이미 꺼져 있으면 종료 ────────────────────
if ! curl -sf "http://localhost:$PORT/health" &>/dev/null; then
    info "서버가 응답하지 않습니다. 이미 종료된 것 같습니다."
    rm -f "$PID_FILE"
    exit 0
fi
info "서버 응답 확인 (port $PORT). 종료를 진행합니다..."

# ── [2] PID 파일에서 읽기 ─────────────────────────────────────
FOUND_PID=""
if [[ -f "$PID_FILE" ]]; then
    CANDIDATE=$(cat "$PID_FILE")
    if kill -0 "$CANDIDATE" 2>/dev/null; then
        FOUND_PID="$CANDIDATE"
    else
        info "PID 파일의 프로세스($CANDIDATE)가 이미 없습니다."
        rm -f "$PID_FILE"
    fi
fi

# ── [3] pgrep 폴백 — JAR 이름 기반 ───────────────────────────
if [[ -z "$FOUND_PID" ]]; then
    info "PID 파일 없음. java-monitor 프로세스를 검색합니다..."
    FOUND_PID=$(pgrep -f "java-monitor" 2>/dev/null | head -1 || true)
fi

# ── [4] fuser 폴백 — 포트 기반 ───────────────────────────────
if [[ -z "$FOUND_PID" ]]; then
    info "포트 $PORT 로 재검색합니다..."
    FOUND_PID=$(fuser "${PORT}/tcp" 2>/dev/null | awk '{print $1}' || true)
fi

if [[ -z "$FOUND_PID" ]]; then
    warn "실행 중인 프로세스를 찾을 수 없습니다."
    exit 1
fi

# ── [5] SIGTERM 전송 (Graceful Shutdown) ──────────────────────
info "SIGTERM 전송 중... (PID: $FOUND_PID)"
kill -TERM "$FOUND_PID" 2>/dev/null || true

# 최대 15초 대기
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
    info "정상 종료되었습니다. (PID: $FOUND_PID)"
    rm -f "$PID_FILE"
    exit 0
fi

# ── [6] SIGKILL (강제 종료) ───────────────────────────────────
warn "15초 내 종료되지 않았습니다. SIGKILL 전송..."
kill -KILL "$FOUND_PID" 2>/dev/null || true
sleep 2

if ! kill -0 "$FOUND_PID" 2>/dev/null; then
    info "강제 종료되었습니다. (PID: $FOUND_PID)"
    rm -f "$PID_FILE"
    exit 0
else
    error "종료에 실패했습니다."
    error "수동으로 종료하세요: kill -9 $FOUND_PID"
    exit 1
fi
