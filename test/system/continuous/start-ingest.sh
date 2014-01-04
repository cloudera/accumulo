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


CONTINUOUS_CONF_DIR=${CONTINUOUS_CONF_DIR:-$ACCUMULO_HOME/test/system/continuous/}
. $CONTINUOUS_CONF_DIR/continuous-env.sh

DEBUG_OPT="";
VIS_OPT="";

if [ "$DEBUG_INGEST" = "on" ] ; then
	DEBUG_OPT="--debug $CONTINUOUS_LOG_DIR/\`date +%Y%m%d%H%M%S\`_\`hostname\`_ingest.log";
fi

#do this check so that script will work w/ 1.4.0 config file
if [ -z $NUM ]; then
	NUM=9223372036854775807
fi

if [ -n "$VISIBILITIES" ] ; then
	VIS_OPT="--visibilities \"$VISIBILITIES\"";
fi

pssh -h $CONTINUOUS_CONF_DIR/ingesters.txt "mkdir -p $CONTINUOUS_LOG_DIR; nohup $ACCUMULO_HOME/bin/accumulo org.apache.accumulo.server.test.continuous.ContinuousIngest $DEBUG_OPT $VIS_OPT $INSTANCE_NAME $ZOO_KEEPERS $USER $PASS $TABLE $NUM $MIN $MAX $MAX_CF $MAX_CQ $MAX_MEM $MAX_LATENCY $NUM_THREADS $CHECKSUM >$CONTINUOUS_LOG_DIR/\`date +%Y%m%d%H%M%S\`_\`hostname\`_ingest.out 2>$CONTINUOUS_LOG_DIR/\`date +%Y%m%d%H%M%S\`_\`hostname\`_ingest.err &" < /dev/null

