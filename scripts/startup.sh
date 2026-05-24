#!/usr/bin/env bash
# ============================================================
#  startup.sh  —  Java APM Dashboard v1.6.0  백그라운드 시작
#
#  사용법:
#    ./startup.sh              기본 포트 9090 으로 시작
#    ./startup.sh 8080         포트 지정
#
#  동작 순서:
#    1. 이중 기동 방지 (PID 파일 + kill -0 확인)
#    2. target/ 에서 최신 JAR 자동 탐색
#    3. nohup 으로 백그라운드 시작, logs/monitor.log 에 출력
#    4. PID 를 logs/monitor.pid 에 저장
#    5. 헬스 체크 통과 시 브라우저 자동 오픈
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

# ── 컬러 출력 ─────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[정보]${NC} $*"; }
warn()  { echo -e "${YELLOW}[경고]${NC} $*"; }
error() { echo -e "${RED}[오류]${NC} $*" >&2; }

echo ""
echo " ╔════════════════════════════════════════════╗"
echo " ║   Java APM Dashboard v1.6.0  |  시작      ║"
echo " ║   Dashboard : $DASHBOARD"
echo " ╚════════════════════════════════════════════╝"
echo ""

# ── [1] logs 디렉토리 생성 ────────────────────────────────────
mkdir -p "$LOG_DIR"

# ── [2] Java 확인 ─────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    error "Java 를 찾을 수 없습니다. JAVA_HOME 또는 PATH 를 확인하세요."
    exit 1
fi
info "Java  : $(java -version 2>&1 | head -1)"

# ── [3] 이중 기동 방지 ────────────────────────────────────────
if [[ -f "$PID_FILE" ]]; then
    EXISTING_PID=$(cat "$PID_FILE")
    if kill -0 "$EXISTING_PID" 2>/dev/null; then
        warn "이미 실행 중입니다. PID: $EXISTING_PID"
        warn "종료하려면: ./shutdown.sh"
        exit 0
    fi
    info "오래된 PID 파일 삭제 (PID: $EXISTING_PID 는 종료됨)"
    rm -f "$PID_FILE"
fi

# ── [4] JAR 탐색 (original / agent / integration 제외) ───────
JAR=""
for f in "$PROJECT_DIR"/target/java-monitor-*.jar; do
    [[ "$f" == *original* || "$f" == *agent* || "$f" == *integration* ]] && continue
    JAR="$f"
done

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    error "빌드된 JAR 파일이 없습니다."
    error "먼저 빌드하세요: cd '$PROJECT_DIR' && mvn package -DskipTests"
    exit 1
fi
info "JAR   : $JAR"

# ── [5] nohup 백그라운드 시작 ─────────────────────────────────
info "서버를 시작합니다 (nohup → $LOG_FILE)..."
nohup java "${JVM_OPTS[@]}" -jar "$JAR" >> "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
info "PID   : $PID   저장됨: $PID_FILE"
info "로그  : $LOG_FILE"

# ── [6] 헬스 체크 대기 (최대 30초) ───────────────────────────
info "서버 기동 대기 중..."
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
    info "서버가 정상 기동되었습니다."
    info "대시보드 : $DASHBOARD"
    # 브라우저 자동 오픈 (Linux: xdg-open, macOS: open)
    if command -v xdg-open &>/dev/null; then
        xdg-open "$DASHBOARD" &>/dev/null & disown
    elif command -v open &>/dev/null; then
        open "$DASHBOARD" &>/dev/null & disown
    fi
    exit 0
else
    warn "헬스 체크 응답 없음 (30초 경과). 로그를 확인하세요:"
    warn "  tail -f $LOG_FILE"
    exit 1
fi
