#!/usr/bin/env bash
# Starts the sensor pipeline with the notification settings from pipeline/.env.
#
# The settings live in an env file rather than in sensor_pipeline.py because one of them
# is the Lambda's shared secret and that file is tracked in git. Missing file is fine ,
# the pipeline just runs with notifications switched off.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f pipeline/.env ]; then
  set -a                      # export everything defined below
  # shellcheck disable=SC1091
  source pipeline/.env
  set +a
else
  echo "No pipeline/.env found, running without notifications." >&2
fi

exec python3 pipeline/sensor_pipeline.py "$@"
