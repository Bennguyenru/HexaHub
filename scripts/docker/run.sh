#!/usr/bin/env bash
# Copyright 2021 The Defold Foundation
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
# 
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
# 
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.



DIR=$1
if [ "$DIR" == "" ]; then
    DIR=`pwd`
fi

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

if [ ! -z "${DYNAMO_HOME}" ]; then
    USE_DYNAMO_HOME="-v ${DYNAMO_HOME}:/dynamo_home"
fi

docker run --rm --name ubuntu --hostname=ubuntu -t -i -v ${DIR}:/home/builder ${USE_DYNAMO_HOME} -v ${SCRIPT_DIR}/bashrc:/home/builder/.bashrc builder/ubuntu
