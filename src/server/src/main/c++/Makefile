# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# ya... I know, this is a bad recursive makefile...

# g++ is required, as well as the glibc-devel and libstdc++ libraries.
# On intel 64bit linux, the 32-bit versions of the i686 libs are also required.
#
# centos6:
#   yum install glibc-devel.i686 libstdc++-devel.i686
# ubuntu:
#   apt-get install libc6-dev-i686 g++-multilib

SHELL=/bin/bash

all: check nm ml

check:
	if [ -z `which g++` ] ; then echo "g++ and 32bit glibc-devel and libstdc++-devel is required to build native map and mlock modules" ;  exit 1 ; fi

nm: 
	cd nativeMap ; $(MAKE)

ml:
	cd mlock ; $(MAKE)

clean:
	cd nativeMap ; $(MAKE) $@
	cd mlock ; $(MAKE) $@
