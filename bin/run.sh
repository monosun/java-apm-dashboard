#!/usr/bin/env bash
# ============================================================
# Java Performance Monitor — Linux / macOS Run Script
# Usage:
#   ./run.sh              Build and run demo
#   ./run.sh build        Build only (Maven)
#   ./run.sh run          Run existing JAR (skip build)
#   ./run.sh dev          Compile with javac + run (no Maven)
#   ./run.sh clean        Delete build output
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TARGET_JAR="$PROJECT_DIR/target/java-monitor-1.12.2.jar"
MAIN_CLASS="com.monosun.monitor.demo.MonitoringDemo"
SRC_DIR="$PROJECT_DIR/src/main/java"
CLASS_DIR="$PROJECT_DIR/target/classes"
LOG_DIR="$PROJECT_DIR/logs"

# JVM options (recommended for production)
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

# ── Color output ──────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

echo ""
echo " ╔══════════════════════════════════════╗"
echo " ║  Java Performance Monitor v1.12.2   ║"
echo " ╚══════════════════════════════════════╝"
echo ""

# ── Check Java ────────────────────────────────────────────────
check_java() {
    if ! command -v java &>/dev/null; then
        error "Java not found. Check JAVA_HOME or PATH."
        exit 1
    fi
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    info "Java: $JAVA_VERSION"
}

# ── Create logs directory ─────────────────────────────────────
mkdir -p "$LOG_DIR"

# ── Build ─────────────────────────────────────────────────────
do_build() {
    if command -v mvn &>/dev/null; then
        info "Starting Maven build..."
        (cd "$PROJECT_DIR" && mvn package -q -DskipTests)
        info "Build complete: $TARGET_JAR"
    else
        warn "Maven not found. Switching to javac direct compile."
        do_dev_compile
    fi
}

# ── Run JAR ───────────────────────────────────────────────────
do_run_jar() {
    if [[ ! -f "$TARGET_JAR" ]]; then
        error "JAR not found: $TARGET_JAR"
        error "Run build first: ./run.sh build"
        exit 1
    fi
    info "Starting in JAR mode..."
    info "Dashboard: http://localhost:9090/dashboard"
    info "Press Ctrl+C to stop"
    echo ""

    # Handle SIGTERM (for container environments)
    trap 'info "Shutdown signal received. Stopping server..."; kill $PID; wait $PID' SIGTERM SIGINT

    java "${JVM_OPTS[@]}" -jar "$TARGET_JAR" &
    PID=$!
    wait $PID
}

# ── Dev mode: direct compile with javac ───────────────────────
do_dev_compile() {
    info "Direct javac compile mode..."
    mkdir -p "$CLASS_DIR"

    # Exclude servlet/JAX-RS
    mapfile -t SOURCES < <(find "$SRC_DIR/com/monosun/monitor" -name "*.java" \
        | grep -v "servlet\|jaxrs")

    if [[ ${#SOURCES[@]} -eq 0 ]]; then
        error "No source files found to compile."
        exit 1
    fi

    javac --release 21 -encoding UTF-8 \
        -d "$CLASS_DIR" \
        -sourcepath "$SRC_DIR" \
        "${SOURCES[@]}"

    info "Compilation done (${#SOURCES[@]} files)"
}

do_dev() {
    do_dev_compile
    info "Starting in class mode..."
    info "Dashboard: http://localhost:9090/dashboard"
    info "Press Ctrl+C to stop"
    echo ""

    trap 'info "Shutdown signal received."; kill $PID; wait $PID' SIGTERM SIGINT

    java "${JVM_OPTS[@]}" -cp "$CLASS_DIR" "$MAIN_CLASS" &
    PID=$!
    wait $PID
}

# ── Clean ─────────────────────────────────────────────────────
do_clean() {
    info "Removing target directory..."
    rm -rf "$PROJECT_DIR/target"
    info "Clean done."
}

# ── Usage ─────────────────────────────────────────────────────
usage() {
    echo ""
    echo "Usage: $0 [command]"
    echo "  (none)   Build + run"
    echo "  build    Maven build only"
    echo "  run      Run existing JAR (no build)"
    echo "  dev      Compile with javac + run (no Maven)"
    echo "  clean    Delete build output"
    echo ""
    exit 1
}

# ── Entry point ───────────────────────────────────────────────
check_java

case "$CMD" in
    all)   do_build; do_run_jar ;;
    build) do_build ;;
    run)   do_run_jar ;;
    dev)   do_dev ;;
    clean) do_clean ;;
    *)     error "Unknown command: $CMD"; usage ;;
esac
