#!/usr/bin/env bash
set -euo pipefail

EMBEDDING_MODEL="nomic-embed-text"

check_docker() {
  if ! docker info &>/dev/null; then
    echo "ERROR: Docker is not running. Start Docker Desktop and try again."
    exit 1
  fi
}

ensure_ollama() {
  if ! pgrep -x ollama &>/dev/null; then
    echo "Starting Ollama..."
    ollama serve &>/dev/null &
    sleep 2
  fi

  if ! ollama list 2>/dev/null | grep -q "$EMBEDDING_MODEL"; then
    echo "Pulling embedding model: $EMBEDDING_MODEL"
    ollama pull "$EMBEDDING_MODEL"
  fi

  echo "Ollama ready ($EMBEDDING_MODEL)"
}

build() {
  echo "Building..."
  ./gradlew compileKotlin --quiet
  echo "Build OK"
}

run() {
  echo "Starting Lore (Postgres will start via Docker Compose, migrations run automatically)..."
  ./gradlew bootRun
}

case "${1:-run}" in
  build) build ;;
  run)
    check_docker
    ensure_ollama
    run
    ;;
  *)
    echo "Usage: ./start.sh [build|run]"
    exit 1
    ;;
esac
