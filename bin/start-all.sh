#! /usr/bin/env bash

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


bin=`dirname "$0"`
bin=`cd "$bin"; pwd`

. "$bin"/config.sh
unset DISPLAY

if [ ! -f $ACCUMULO_CONF_DIR/accumulo-env.sh ] ; then
  echo "${ACCUMULO_CONF_DIR}/accumulo-env.sh does not exist. Please make sure you configure Accumulo before you run anything"
  echo "We provide examples you can copy in ${ACCUMULO_HOME}/conf/examples/ which are set up for your memory footprint"
  exit 1
fi


if [ -z $ZOOKEEPER_HOME ] ; then
    echo "ZOOKEEPER_HOME is not set.  Please make sure it's set globally or in conf/accumulo-env.sh"
    exit 1
fi
if [ ! -d $ZOOKEEPER_HOME ]; then
   echo "ZOOKEEPER_HOME is not a directory: $ZOOKEEPER_HOME"
   echo "Please check the setting, either globally or in accumulo-env.sh."
   exit 1
fi

ZOOKEEPER_VERSION=$(find $ZOOKEEPER_HOME -maxdepth 1 -name "zookeeper-[0-9]*.jar" | head -1)
if [ -z "$ZOOKEEPER_VERSION" ]; then
   echo "A Zookeeper JAR was not found in $ZOOKEEPER_HOME."
   echo "Please check ZOOKEEPER_HOME, either globally or in accumulo-env.sh."
   exit 1
fi
ZOOKEEPER_VERSION=${ZOOKEEPER_VERSION##$ZOOKEEPER_HOME/zookeeper-}
ZOOKEEPER_VERSION=${ZOOKEEPER_VERSION%%.jar}

if [ "$ZOOKEEPER_VERSION" '<' "3.3.0" ] ; then 
	echo "WARN : Using Zookeeper $ZOOKEEPER_VERSION.  Use version 3.3.0 or greater to avoid zookeeper deadlock bug.";
fi

if [ "$1" != "--notSlaves" ] ; then
	${bin}/tup.sh
fi

${bin}/accumulo org.apache.accumulo.server.master.state.SetGoalState NORMAL
for master in `grep -v '^#' "$ACCUMULO_CONF_DIR/masters"`
do
    ${bin}/start-server.sh $master master
done

for gc in `grep -v '^#' "$ACCUMULO_CONF_DIR/gc"`
do
    ${bin}/start-server.sh $gc gc "garbage collector"
done

${bin}/start-server.sh $MONITOR monitor 

for tracer in `grep -v '^#' "$ACCUMULO_CONF_DIR/tracers"`
do
   ${bin}/start-server.sh $tracer tracer
done
