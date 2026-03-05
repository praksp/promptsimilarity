#!/usr/bin/env bash
# Ensure Docker is running. Start Docker Desktop if possible and wait until daemon is ready.
# Usage: source this file or run with . scripts/ensure-docker.sh
# Returns 0 if Docker is ready, 1 otherwise.

ensure_docker() {
  if docker info &>/dev/null; then
    return 0
  fi

  echo "Docker is not running. Attempting to start Docker Desktop..."

  if [[ "$(uname -s)" == "Darwin" ]]; then
    if [ -d "/Applications/Docker.app" ]; then
      open -a Docker
    elif [ -d "/Applications/Docker Desktop.app" ]; then
      open -a "Docker Desktop"
    else
      echo "Docker Desktop not found in /Applications. Please start Docker manually."
      return 1
    fi
  elif [[ "$(uname -s)" == "Linux" ]]; then
    if command -v systemctl &>/dev/null; then
      sudo systemctl start docker 2>/dev/null || true
    elif command -v service &>/dev/null; then
      sudo service docker start 2>/dev/null || true
    else
      echo "Please start the Docker daemon manually."
      return 1
    fi
  else
    echo "Unsupported OS. Please start Docker manually."
    return 1
  fi

  echo -n "Waiting for Docker daemon"
  local max=60
  local n=0
  while [ $n -lt $max ]; do
    if docker info &>/dev/null; then
      echo " done."
      return 0
    fi
    echo -n "."
    sleep 2
    n=$((n + 1))
  done
  echo " timeout."
  echo "Docker did not become ready in time. Start Docker Desktop manually and re-run."
  return 1
}

# If script is executed (not sourced), run ensure_docker
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  ensure_docker
  exit $?
fi
