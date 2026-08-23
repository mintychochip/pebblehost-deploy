#!/usr/bin/env bash
# Fake pb for functional tests. Emits canned responses shaped like the real
# panel payloads the plugin parses. Real pb talks to the PebbleHost API.
#
# Failure injection: set FAKE_PB_FAIL to a comma-separated list of
# <subcommand>:<server> specs ("*" matches any server), e.g.
#   FAKE_PB_FAIL="file:srv-2,power:*"
set -euo pipefail

# The plugin passes a global --base-url <url> before the subcommand. Strip it
# (and its value) so ${args[0]} is the actual subcommand.
args=("$@")
while [[ "${args[0]:-}" == "--base-url" ]]; do
  args=("${args[@]:2}")
done

cmd="${args[0]:-}"

# Server id: the value of an explicit --server flag if present, else the
# positional argument right after the subcommand.
server=""
prev=""
for a in "${args[@]:1}"; do
  if [[ "$prev" == "--server" ]]; then server="$a"; break; fi
  prev="$a"
done
if [[ -z "$server" && ${#args[@]} -ge 2 ]]; then
  server="${args[1]}"
fi

should_fail() {
  [[ -n "${FAKE_PB_FAIL:-}" ]] || return 1
  local spec c s
  for spec in ${FAKE_PB_FAIL//,/ }; do
    c="${spec%%:*}"
    s="${spec#*:}"
    if [[ "$c" == "$cmd" && ( "$s" == "*" || "$s" == "$server" ) ]]; then
      echo "fake-pb: simulated '$cmd' failure for server '${server:-?}'" >&2
      return 0
    fi
  done
  return 1
}

case "$cmd" in
  --version)
    if should_fail; then exit 3; fi
    echo "pb 0.0.0-test"
    ;;
  files)
    # pb files <server> --directory <dir>
    if should_fail; then exit 3; fi
    cat <<'JSON'
{"object":"list","data":[{"object":"ptero_file","attributes":{"name":"a.jar","mode":"0644","size":10240,"is_file":true,"mimetype":"application/java-archive","created_at":"2026-08-21T10:00:00+00:00","modified_at":"2026-08-21T11:30:00+00:00"}}],"meta":{"pagination":{"total":1,"count":1,"per_page":50,"current_page":1,"total_pages":1}}}
JSON
    ;;
  resources)
    # pb resources <server>
    if should_fail; then exit 3; fi
    cat <<'JSON'
{"object":"stats","attributes":{"current_state":"running","is_suspended":false,"resources":{"memory_bytes":1073741824,"cpu_absolute":12.5,"disk_bytes":2147483648,"uptime_ms":86400000}}}
JSON
    ;;
  api-call)
    # pb api-call PUT/POST <path> --body '...'
    if should_fail; then exit 3; fi
    echo '{}'
    ;;
  file)
    # pb file push <local> --server <id> --directory <dir>
    if should_fail; then exit 3; fi
    echo '{"success":true}'
    ;;
  power)
    # pb power <server> --action restart
    if should_fail; then exit 3; fi
    echo '{}'
    ;;
  *) echo "unexpected args: $*" >&2; exit 2 ;;
esac
