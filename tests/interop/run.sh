#!/usr/bin/env bash
# End-to-end interop test: real C# SyncServer <-> real Kotlin SyncClient over localhost TCP.
# Requires: .NET 8 SDK, JDK 17, Gradle 8.x.   Usage: tests/interop/run.sh
set -euo pipefail
cd "$(dirname "$0")"

PORT=${PORT:-45799}
SERVER_ID=0123456789abcdef
KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
WRONG=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')

echo "== building"
dotnet build server -c Release -v q -nologo >/dev/null
gradle -q -p client installDist
SERVER="dotnet server/bin/Release/net8.0/InteropServer.dll"
CLIENT="client/build/install/interop-client/bin/interop-client"

run_case() { # name serverMode clientKey clientMode
  local name=$1 smode=$2 ckey=$3 cmode=$4
  echo "== $name"
  $SERVER "$PORT" "$KEY" "$SERVER_ID" "$smode" > "/tmp/interop-server-$name.log" 2>&1 &
  local spid=$!
  for _ in $(seq 50); do grep -q READY "/tmp/interop-server-$name.log" 2>/dev/null && break; sleep 0.2; done
  local crc=0; "$CLIENT" 127.0.0.1 "$PORT" "$ckey" "$SERVER_ID" "$cmode" > "/tmp/interop-client-$name.log" 2>&1 || crc=$?
  local src=0; wait $spid || src=$?
  grep -E '^\[(server|client)\]' "/tmp/interop-server-$name.log" "/tmp/interop-client-$name.log" | sed 's#^/tmp/interop-##'
  if [[ $src -ne 0 || $crc -ne 0 ]]; then
    echo "!! $name FAILED (server=$src client=$crc)"; tail -30 "/tmp/interop-server-$name.log" "/tmp/interop-client-$name.log"; exit 1
  fi
}

run_case roundtrip roundtrip "$KEY"   roundtrip
run_case reject    reject    "$WRONG" reject
run_case abuse     abuse     "$KEY"   connect
echo "== ALL INTEROP TESTS PASSED"
