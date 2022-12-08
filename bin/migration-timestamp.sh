#! /usr/bin/env bash

set -euo pipefail

script_dir=`dirname "${BASH_SOURCE[0]}"`
cd "$script_dir/.."
root=

source "./bin/check-clojure-cli.sh"
check_clojure_cli 1>&2

source "./bin/clear-outdated-cpcaches.sh"
clear_outdated_cpcaches 1>&2

cd bin/lint-migrations-file
clojure -M -m migration-timestamp $@
