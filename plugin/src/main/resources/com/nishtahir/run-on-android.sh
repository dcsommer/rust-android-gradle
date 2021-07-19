#!/bin/sh

set -euo pipefail

EXE="$1"
EXE_NAME=$(basename "$EXE")
TMP_OUT=$(mktemp)
adb push "$EXE" "/data/local/tmp/$EXE_NAME" &> $TMP_OUT || (cat $TMP_OUT; exit 1)
adb shell "chmod 755 /data/local/tmp/$EXE_NAME"
MARK=$(head -c 64 /dev/urandom | base64 -w 0)
RET=1
while IFS= read -r line; do
    if [ "$line" = "$MARK" ]; then
        RET=0
        break
    else
        echo "$line"
    fi
done < <(adb shell "RUST_LOG=debug /data/local/tmp/$EXE_NAME && echo $MARK")
exit $RET
