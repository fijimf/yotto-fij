#!/bin/sh
# Rotates the nginx logs in the shared nginx_logs volume. Runs as the `logrotate`
# compose sidecar (plain alpine, no packages needed).
#
# Copy-truncate instead of rename+reopen: nginx keeps its open fd (no USR1 needed),
# and goaccess/netdata both tail these files and handle truncation. A few lines can
# be lost in the copy->truncate window; fine for this use.
#
# Note: goaccess rebuilds its dashboard from goaccess.log on restart, so rotation
# caps its history at roughly MAX_BYTES of traffic (archives are not re-read).
#
# Usage: rotate-logs.sh        -> daemon mode, checks once a day
#        rotate-logs.sh once   -> single pass (for testing)

MAX_BYTES=$((50 * 1024 * 1024))
KEEP=3
LOGS="/var/log/nginx/goaccess.log /var/log/nginx/access_timed.log"

rotate_pass() {
    for f in $LOGS; do
        [ -f "$f" ] || continue
        size=$(stat -c %s "$f" 2>/dev/null || echo 0)
        if [ "$size" -gt "$MAX_BYTES" ]; then
            echo "rotating $f ($size bytes)"
            i=$KEEP
            while [ "$i" -gt 1 ]; do
                prev=$((i - 1))
                [ -f "$f.$prev.gz" ] && mv "$f.$prev.gz" "$f.$i.gz"
                i=$prev
            done
            cp "$f" "$f.1" && : > "$f" && gzip -f "$f.1"
        fi
    done
}

if [ "$1" = "once" ]; then
    rotate_pass
    exit 0
fi

while true; do
    rotate_pass
    sleep 86400
done
