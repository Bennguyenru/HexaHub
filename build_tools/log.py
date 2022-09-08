# Copyright 2020-2022 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
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

import sys

def log(msg):
    if type(msg) == bytes:
        try:
            print (msg.decode('utf-8'))
        except:
            try:
                print (msg.decode('utf-16'))
            except:
                pass
    else:
        print(msg.encode('utf-8', errors='replace').decode('utf-8'))
    sys.stdout.flush()
    sys.stderr.flush()
