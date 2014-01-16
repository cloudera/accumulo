/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.test.randomwalk.concurrent;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.server.test.randomwalk.State;
import org.apache.accumulo.server.test.randomwalk.Test;

/**
 * 
 */
public class CheckBalance extends Test {
  
  static final String LAST_UNBALANCED_TIME = "lastUnbalancedTime";
  static final String UNBALANCED_COUNT = "unbalancedCount";

  /* (non-Javadoc)
   * @see org.apache.accumulo.server.test.randomwalk.Node#visit(org.apache.accumulo.server.test.randomwalk.State, java.util.Properties)
   */
  @Override
  public void visit(State state, Properties props) throws Exception {
    log.debug("checking balance");
    Map<String,Long> counts = new HashMap<String,Long>();
    Scanner scanner = state.getConnector().createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS);
    scanner.fetchColumnFamily(Constants.METADATA_CURRENT_LOCATION_COLUMN_FAMILY);
    for (Entry<Key,Value> entry : scanner) {
      String location = entry.getKey().getColumnQualifier().toString();
      Long count = counts.get(location);
      if (count == null)
        count = new Long(0);
      counts.put(location, count + 1);
    }
    double total = 0.;
    for (Long count : counts.values()) {
      total += count.longValue();
    }
    final double average = total / counts.size();
    
    // Check for even # of tablets on each node
    double maxDifference = Math.max(1, average / 5);
    String unbalancedLocation = null;
    long lastCount = 0L;
    boolean balanced = true;
    for (Entry<String,Long> entry : counts.entrySet()) {
      lastCount = entry.getValue().longValue();
      if (Math.abs(lastCount - average) > maxDifference) {
        balanced = false;
        unbalancedLocation = entry.getKey();
        break;
      }
    }
    
    // It is expected that the number of tablets will be uneven for short
    // periods of time. Don't complain unless we've seen it only unbalanced
    // over a 15 minute period and it's been at least three checks.
    if (!balanced) {
      Long last = state.getLong(LAST_UNBALANCED_TIME);
      if (last != null && System.currentTimeMillis() - last > 15 * 60 * 1000) {
        Integer count = state.getInteger(UNBALANCED_COUNT);
        if (count == null)
          count = Integer.valueOf(0);
        if (count > 3)
          throw new Exception("servers are unbalanced! location " + unbalancedLocation + " count " + lastCount + " too far from average " + average);
        count++;
        state.set(UNBALANCED_COUNT, count);
      } else if (last == null) {
        state.set(LAST_UNBALANCED_TIME, System.currentTimeMillis());
      }
    } else {
      state.remove(LAST_UNBALANCED_TIME);
      state.remove(UNBALANCED_COUNT);
    }
  }
  
}
