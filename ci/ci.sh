#!/bin/bash
./scripts/build.py --save-env-path=ci/env.sh save_env
source ci/env.sh
pythonn ./ci/ci.py "$@"
