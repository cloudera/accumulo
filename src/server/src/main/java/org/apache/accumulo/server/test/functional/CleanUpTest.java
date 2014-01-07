/*
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
package org.apache.accumulo.server.test.functional;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.CleanUp;

/**
 * 
 */
public class CleanUpTest extends FunctionalTest {

  @Override
  public Map<String,String> getInitialConfig() {
    return Collections.emptyMap();
  }

  @Override
  public List<TableSetup> getTablesToCreate() {
    return Collections.emptyList();
  }

  @Override
  public void run() throws Exception {


    getConnector().tableOperations().create("test");

    BatchWriter bw = getConnector().createBatchWriter("test", 1000000, 60000, 1);

    Mutation m1 = new Mutation("r1");
    m1.put("cf1", "cq1", 1, "5");

    bw.addMutation(m1);

    bw.flush();

    Scanner scanner = getConnector().createScanner("test", new Authorizations());

    int count = 0;
    for (Entry<Key,Value> entry : scanner) {
      count++;
      if (!entry.getValue().toString().equals("5")) {
        throw new Exception("Unexpected value " + entry.getValue());
      }
    }

    if (count != 1) {
      throw new Exception("Unexpected count " + count);
    }

    if (countThreads() < 2) {
      printThreadNames();
      throw new Exception("Not seeing expected threads");
    }

    CleanUp.shutdownNow();

    Mutation m2 = new Mutation("r2");
    m2.put("cf1", "cq1", 1, "6");

    try {
      bw.addMutation(m1);
      bw.flush();
      throw new Exception("batch writer did not fail");
    } catch (Exception e) {

    }

    try {
      // expect this to fail also, want to clean up batch writer threads
      bw.close();
      throw new Exception("batch writer close not fail");
    } catch (Exception e) {

    }

    try {
      count = 0;
      Iterator<Entry<Key,Value>> iter = scanner.iterator();
      while (iter.hasNext()) {
        iter.next();
        count++;
      }
      throw new Exception("scanner did not fail");
    } catch (Exception e) {

    }

    if (countThreads() > 0) {
      printThreadNames();
      throw new Exception("Threads did not go away");
    }
  }

  private void printThreadNames() {
    Set<Thread> threads = Thread.getAllStackTraces().keySet();
    for (Thread thread : threads) {
      System.out.println("thread name:" + thread.getName());
      thread.getStackTrace();

    }
  }

  /**
   * count threads that should be cleaned up
   * 
   */
  private int countThreads() {
    int count = 0;
    Set<Thread> threads = Thread.getAllStackTraces().keySet();
    for (Thread thread : threads) {

      if (thread.getName().toLowerCase().contains("sendthread") || thread.getName().toLowerCase().contains("eventthread"))
        count++;

      if (thread.getName().toLowerCase().contains("thrift") && thread.getName().toLowerCase().contains("pool"))
        count++;
    }

    return count;
  }

  @Override
  public void cleanup() throws Exception {}

}
