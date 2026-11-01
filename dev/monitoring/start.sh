#!/bin/sh
set -eu
umask 077
printf '%s' "$SPORTCOURT_MONITOR_TOKEN" > /tmp/monitor-token
exec /bin/prometheus --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus --storage.tsdb.retention.time=7d
