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

if [ -z "${ACCUMULO_CONF_DIR}" ]; then
    ACCUMULO_CONF_DIR=${ACCUMULO_HOME}/conf
fi

CONTINUOUS_CONF_DIR=${CONTINUOUS_CONF_DIR:-$ACCUMULO_HOME/test/system/continuous/}
. $CONTINUOUS_CONF_DIR/continuous-env.sh

mkdir -p $CONTINUOUS_LOG_DIR

CONFIG_OUT=$CONTINUOUS_LOG_DIR/`date +%Y%m%d%H%M%S`_`hostname`_config.out

cat $ACCUMULO_CONF_DIR/accumulo-env.sh > $CONFIG_OUT
echo >> $CONFIG_OUT
echo -e "config -np\nconfig -t $TABLE -np\nquit" | $ACCUMULO_HOME/bin/accumulo shell -u $USER -p $PASS >> $CONFIG_OUT
echo >> $CONFIG_OUT
cat $CONTINUOUS_CONF_DIR/continuous-env.sh >> $CONFIG_OUT
echo >> $CONFIG_OUT
echo "`wc -l $CONTINUOUS_CONF_DIR/walkers.txt`" >> $CONFIG_OUT
echo "`wc -l $CONTINUOUS_CONF_DIR/ingesters.txt`" >> $CONFIG_OUT
echo "`wc -l $CONTINUOUS_CONF_DIR/scanners.txt`" >> $CONFIG_OUT
echo "`wc -l $CONTINUOUS_CONF_DIR/batch_walkers.txt`" >> $CONFIG_OUT


nohup $ACCUMULO_HOME/bin/accumulo org.apache.accumulo.server.test.continuous.ContinuousStatsCollector $TABLE $INSTANCE_NAME $ZOO_KEEPERS $USER $PASS >$CONTINUOUS_LOG_DIR/`date +%Y%m%d%H%M%S`_`hostname`_stats.out 2>$CONTINUOUS_LOG_DIR/`date +%Y%m%d%H%M%S`_`hostname`_stats.err &

