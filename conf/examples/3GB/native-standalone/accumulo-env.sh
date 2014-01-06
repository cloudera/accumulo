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

###
### Configure these environment variables to point to your local installations.
###
### The functional tests require conditional values, so keep this style:
###
### test -z "$JAVA_HOME" && export JAVA_HOME=/usr/local/lib/jdk-1.6.0
###
###
### Note that the -Xmx -Xms settings below require substantial free memory: 
### you may want to use smaller values, especially when running everything
### on a single machine.
###
if [ -z "$HADOOP_HOME" ]
then
   test -z "$HADOOP_PREFIX"      && export HADOOP_PREFIX=/path/to/hadoop
else
   HADOOP_PREFIX="$HADOOP_HOME"
   unset HADOOP_HOME
fi
test -z "$HADOOP_CONF_DIR"       && export HADOOP_CONF_DIR="$HADOOP_PREFIX/conf"
# hadoop-2.0:
# test -z "$HADOOP_CONF_DIR"     && export HADOOP_CONF_DIR="$HADOOP_PREFIX/etc/hadoop"

test -z "$JAVA_HOME"             && export JAVA_HOME=/path/to/java
test -z "$ZOOKEEPER_HOME"        && export ZOOKEEPER_HOME=/path/to/zookeeper
test -z "$ACCUMULO_LOG_DIR"      && export ACCUMULO_LOG_DIR=$ACCUMULO_HOME/logs
if [ -f ${ACCUMULO_CONF_DIR}/accumulo.policy ]
then
   POLICY="-Djava.security.manager -Djava.security.policy=${ACCUMULO_CONF_DIR}/accumulo.policy"
fi
test -z "$ACCUMULO_TSERVER_OPTS" && export ACCUMULO_TSERVER_OPTS="${POLICY} -Xmx1g -Xms1g -XX:NewSize=500m -XX:MaxNewSize=500m "
test -z "$ACCUMULO_MASTER_OPTS"  && export ACCUMULO_MASTER_OPTS="${POLICY} -Xmx1g -Xms1g"
test -z "$ACCUMULO_MONITOR_OPTS" && export ACCUMULO_MONITOR_OPTS="${POLICY} -Xmx1g -Xms256m" 
test -z "$ACCUMULO_GC_OPTS"      && export ACCUMULO_GC_OPTS="-Xmx256m -Xms256m"
test -z "$ACCUMULO_LOGGER_OPTS"  && export ACCUMULO_LOGGER_OPTS="-Xmx1g -Xms256m"
test -z "$ACCUMULO_GENERAL_OPTS" && export ACCUMULO_GENERAL_OPTS="-XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=75"
test -z "$ACCUMULO_OTHER_OPTS"   && export ACCUMULO_OTHER_OPTS="-Xmx1g -Xms256m"
export ACCUMULO_LOG_HOST=`(grep -v '^#' $ACCUMULO_CONF_DIR/monitor | egrep -v '^[[:space:]]*$' ; echo localhost ) 2>/dev/null | head -1`
# what do when the JVM runs out of heap memory
export ACCUMULO_KILL_CMD='kill -9 %p'
