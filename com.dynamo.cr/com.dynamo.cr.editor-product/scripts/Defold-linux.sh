#!/usr/bin/env bash
set -eu

# ----------------------------------------------------------------------------
# Environment
# ----------------------------------------------------------------------------
SCRIPT_NAME="$(basename "${0}")"
SCRIPT_PATH="$(cd "$(dirname "${0}")"; pwd)"
trap 'terminate 1 "Launch error: An error occurred."' SIGINT SIGTERM EXIT


# ----------------------------------------------------------------------------
# Functions
# ----------------------------------------------------------------------------
function terminate() {
  trap - SIGINT SIGTERM EXIT
  echo "${2:-Terminated.}"
  exit ${1:-1}
}

  function setup_xulrunner() {
    _CONFIG_PATH="${SCRIPT_PATH}/Defold.ini"
    _KEY="-Dorg.eclipse.swt.browser.XULRunnerPath"
    _VAL="${SCRIPT_PATH}/xulrunner"
    if grep -- "${_KEY}" "${_CONFIG_PATH}" > /dev/null 2>&1; then
      sed -ir "s#${_KEY}=.*#${_KEY}=${_VAL}#g" "${_CONFIG_PATH}"
    fi
  }

  function setup_ubuntu_unity() {
    # This is unnecessary but harmless on unaffected distributions.
    export UBUNTU_MENUPROXY=0
  }

  function setup_library_path() {
    if [ -z ${LD_LIBRARY_PATH+x} ]; then
      LD_LIBRARY_PATH=""
    fi

    _BOB_PATH="$(find "${SCRIPT_PATH}/plugins" -type d \
        -name "com.dynamo.cr.bob_*" | head -n1)"
    if [ ! -d "${_BOB_PATH}" ]; then
      terminate 1 "Launch error: Unable to locate library directory"
    fi

    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:${_BOB_PATH}/libexec/x86_64-linux"
  }

  function execute_defold() {
    _EXECUTABLE_PATH="${SCRIPT_PATH}/Defold"
    if [ -f "${_EXECUTABLE_PATH}" ]; then
      trap - SIGINT SIGTERM EXIT
      "${_EXECUTABLE_PATH}"
    fi
  }

# ----------------------------------------------------------------------------
# Script
# ----------------------------------------------------------------------------
setup_xulrunner
setup_ubuntu_unity
setup_library_path
execute_defold
terminate 0 "Done"