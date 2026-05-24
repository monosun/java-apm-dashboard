#!/usr/bin/env bash
# ============================================================
# Java Performance Monitor — Linux / macOS 실행 스크립트
# 사용법:
#   ./run.sh              빌드 후 데모 실행
#   ./run.sh build        빌드만 수행
#   ./run.sh run          빌드 없이 실행 (jar 필요)
#   ./run.sh dev          소스 직접 컴파일 후 실행 (Maven 없이)
#   ./run.sh clean        빌드 결과물 삭제
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TARGET_JAR="$PROJECT_DIR/target/java-monitor-1.11.0.jar"
MAIN_CLASS="com.monosun.monitor.demo.MonitoringDemo"
SRC_DIR="$PROJECT_DIR/src/main/java"
CLASS_DIR="$PROJECT_DIR/target/classes"
LOG_DIR="$PROJECT_DIR/logs"

# JVM 옵션 (운영 환경 권장 설정)
JVM_OPTS=(
    -Xms256m
    -Xmx512m
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+HeapDumpOnOutOfMemoryError
    "-XX:HeapDumpPath=$LOG_DIR/heapdump.hprof"
    "-Xlog:gc*:file=$LOG_DIR/gc.log:time:filecount=3,filesize=10m"
    "-Djava.util.logging.config.file=$SCRIPT_DIR/logging.properties"
)

CMD="${1:-all}"

# ── 컬러 출력 ────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[정보]${NC} $*"; }
warn()  { echo -e "${YELLOW}[경고]${NC} $*"; }
error() { echo -e "${RED}[오류]${NC} $*" >&2; }

echo ""
echo " ╔══════════════════════════════════════╗"
echo " ║  Java Performance Monitor v1.11.0   ║"
echo " ╚══════════════════════════════════════╝"
echo ""

# ── Java 확인 ─────────────────────────────────────────────────
check_java() {
    if ! command -v java &>/dev/null; then
        error "Java 를 찾을 수 없습니다. JAVA_HOME 또는 PATH 를 확인하세요."
        exit 1
    fi
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    info "Java: $JAVA_VERSION"
}

# ── logs 디렉토리 ─────────────────────────────────────────────
mkdir -p "$LOG_DIR"

# ── 빌드 ──────────────────────────────────────────────────────
do_build() {
    if command -v mvn &>/dev/null; then
        info "Maven 빌드를 시작합니다..."
        (cd "$PROJECT_DIR" && mvn package -q -DskipTests)
        info "빌드 완료: $TARGET_JAR"
    else
        warn "Maven 이 없습니다. javac 직접 컴파일로 전환합니다."
        do_dev_compile
    fi
}

# ── JAR 실행 ──────────────────────────────────────────────────
do_run_jar() {
    if [[ ! -f "$TARGET_JAR" ]]; then
        error "JAR 파일이 없습니다: $TARGET_JAR"
        error "먼저 빌드하세요: ./run.sh build"
        exit 1
    fi
    info "JAR 모드로 시작합니다..."
    info "대시보드: http://localhost:9090/dashboard"
    info "Ctrl+C 로 종료"
    echo ""

    # SIGTERM 처리 (컨테이너 환경 대응)
    trap 'info "종료 신호 수신. 서버를 내립니다..."; kill $PID; wait $PID' SIGTERM SIGINT

    java "${JVM_OPTS[@]}" -jar "$TARGET_JAR" &
    PID=$!
    wait $PID
}

# ── Dev 모드 직접 컴파일 ────────────────────────────────────────
do_dev_compile() {
    info "javac 직접 컴파일 모드..."
    mkdir -p "$CLASS_DIR"

    # Servlet/JAX-RS 제외
    mapfile -t SOURCES < <(find "$SRC_DIR/com/monosun/monitor" -name "*.java" \
        | grep -v "servlet\|jaxrs")

    if [[ ${#SOURCES[@]} -eq 0 ]]; then
        error "컴파일할 소스 파일이 없습니다."
        exit 1
    fi

    javac --release 21 -encoding UTF-8 \
        -d "$CLASS_DIR" \
        -sourcepath "$SRC_DIR" \
        "${SOURCES[@]}"

    info "컴파일 완료 (${#SOURCES[@]} 파일)"
}

do_dev() {
    do_dev_compile
    info "클래스 모드로 시작합니다..."
    info "대시보드: http://localhost:9090/dashboard"
    info "Ctrl+C 로 종료"
    echo ""

    trap 'info "종료 신호 수신."; kill $PID; wait $PID' SIGTERM SIGINT

    java "${JVM_OPTS[@]}" -cp "$CLASS_DIR" "$MAIN_CLASS" &
    PID=$!
    wait $PID
}

# ── 정리 ──────────────────────────────────────────────────────
do_clean() {
    info "target 디렉토리를 삭제합니다..."
    rm -rf "$PROJECT_DIR/target"
    info "정리 완료"
}

# ── 사용법 ────────────────────────────────────────────────────
usage() {
    echo ""
    echo "사용법: $0 [명령]"
    echo "  (없음)   빌드 + 실행"
    echo "  build    Maven 빌드만"
    echo "  run      JAR 실행 (빌드 불필요)"
    echo "  dev      javac 직접 컴파일 + 실행 (Maven 없이)"
    echo "  clean    빌드 결과물 삭제"
    echo ""
    exit 1
}

# ── 진입점 ────────────────────────────────────────────────────
check_java

case "$CMD" in
    all)   do_build; do_run_jar ;;
    build) do_build ;;
    run)   do_run_jar ;;
    dev)   do_dev ;;
    clean) do_clean ;;
    *)     error "알 수 없는 명령: $CMD"; usage ;;
esac
