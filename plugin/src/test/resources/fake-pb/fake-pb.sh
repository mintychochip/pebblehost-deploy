#!/usr/bin/env bash
# Fake pb for functional tests. Emits canned responses for the commands the
# plugin issues. Real pb would talk to the PebbleHost API.
set -euo pipefail

# The plugin passes a global --base-url <url> before the subcommand. Strip it
# (and its value) so $1 is the actual subcommand.
args=("$@")
while [[ "${args[0]:-}" == "--base-url" ]]; do
  args=("${args[@]:2}")
done

case "${args[0]:-}" in
  --version) echo "pb 0.0.0-test"; exit 0 ;;
  files)
    # pb files <server> --directory <dir>
    echo '{"data":[{"attributes":{"name":"a.jar","is_file":true}}]}'
    ;;
  resources)
    # pb resources <server>
    echo '{"attributes":{"current_state":"running"}}'
    ;;
  api-call)
    # pb api-call PUT .../files/rename --body '...'
    echo '{}'
    ;;
  file)
    # pb file push <local> --server <id> --directory <dir>
    echo "uploaded"
    ;;
  power)
    # pb power <server> --action restart
    echo '{}'
    ;;
  *) echo "unexpected args: $*" >&2; exit 2 ;;
esac
