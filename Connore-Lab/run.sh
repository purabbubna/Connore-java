#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
java Build.java
java -cp 'out:lib/*' connore.Main
