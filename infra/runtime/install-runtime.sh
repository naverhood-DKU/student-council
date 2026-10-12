#!/bin/bash
# Run as root with a private staging directory containing app.jar,
# runtime.env (0600) and council-flow.service. Never print environment contents.
set -euo pipefail
staging="${1:?Private staging directory required}"
test -f "$staging/app.jar"
test -f "$staging/runtime.env"
test -f "$staging/council-flow.service"
/usr/lib/jvm/jre-21-openjdk/bin/java -version
id councilflow >/dev/null 2>&1 || useradd --system --home-dir /var/lib/council-flow --shell /sbin/nologin councilflow
install -d -m 755 /opt/council-flow
install -d -m 700 /etc/council-flow
install -d -m 700 -o councilflow -g councilflow /var/lib/council-flow
install -m 644 "$staging/app.jar" /opt/council-flow/app.jar
install -m 600 "$staging/runtime.env" /etc/council-flow/runtime.env
install -m 644 "$staging/council-flow.service" /etc/systemd/system/council-flow.service
rm -f "$staging/runtime.env"
systemd-analyze verify /etc/systemd/system/council-flow.service
systemctl daemon-reload
systemctl enable council-flow.service
systemctl restart council-flow.service
systemctl is-active --quiet council-flow.service
