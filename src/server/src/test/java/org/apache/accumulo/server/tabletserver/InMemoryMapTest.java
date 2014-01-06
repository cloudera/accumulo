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
package org.apache.accumulo.server.tabletserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.system.ColumnFamilySkippingIterator;
import org.apache.accumulo.core.util.LocalityGroupUtil;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ZooConfiguration;
import org.apache.accumulo.server.tabletserver.InMemoryMap.MemoryIterator;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Before;

public class InMemoryMapTest extends TestCase {
  
  @Before
  public void setUp() throws Exception {
    // suppress log messages having to do with not having an instance
    Logger.getLogger(ZooConfiguration.class).setLevel(Level.OFF);
    Logger.getLogger(HdfsZooInstance.class).setLevel(Level.OFF);
  }
  
  public void mutate(InMemoryMap imm, String row, String column, long ts) {
    Mutation m = new Mutation(new Text(row));
    String[] sa = column.split(":");
    m.putDelete(new Text(sa[0]), new Text(sa[1]), ts);
    
    imm.mutate(Collections.singletonList(m));
  }
  
  public void mutate(InMemoryMap imm, String row, String column, long ts, String value) {
    Mutation m = new Mutation(new Text(row));
    String[] sa = column.split(":");
    m.put(new Text(sa[0]), new Text(sa[1]), ts, new Value(value.getBytes()));
    
    imm.mutate(Collections.singletonList(m));
  }
  
  static Key nk(String row, String column, long ts) {
    String[] sa = column.split(":");
    Key k = new Key(new Text(row), new Text(sa[0]), new Text(sa[1]), ts);
    return k;
  }
  
  static void ae(SortedKeyValueIterator<Key,Value> dc, String row, String column, int ts, String val) throws IOException {
    assertTrue(dc.hasTop());
    assertEquals(nk(row, column, ts), dc.getTopKey());
    assertEquals(new Value(val.getBytes()), dc.getTopValue());
    dc.next();
    
  }
  
  public void test2() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    MemoryIterator ski1 = imm.skvIterator();
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    MemoryIterator ski2 = imm.skvIterator();
    
    ski1.seek(new Range(), LocalityGroupUtil.EMPTY_CF_SET, false);
    assertFalse(ski1.hasTop());
    
    ski2.seek(new Range(), LocalityGroupUtil.EMPTY_CF_SET, false);
    assertTrue(ski2.hasTop());
    ae(ski2, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski2.hasTop());
    
  }
  
  public void test3() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    mutate(imm, "r1", "foo:cq1", 3, "bar2");
    MemoryIterator ski1 = imm.skvIterator();
    mutate(imm, "r1", "foo:cq1", 3, "bar3");
    
    mutate(imm, "r3", "foo:cq1", 3, "bar9");
    mutate(imm, "r3", "foo:cq1", 3, "bara");
    
    MemoryIterator ski2 = imm.skvIterator();
    
    ski1.seek(new Range(new Text("r1")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar2");
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski1.hasTop());
    
    ski2.seek(new Range(new Text("r3")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski2, "r3", "foo:cq1", 3, "bara");
    ae(ski2, "r3", "foo:cq1", 3, "bar9");
    assertFalse(ski1.hasTop());
    
  }
  
  public void test4() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    mutate(imm, "r1", "foo:cq1", 3, "bar2");
    MemoryIterator ski1 = imm.skvIterator();
    mutate(imm, "r1", "foo:cq1", 3, "bar3");
    
    imm.delete(0);
    
    ski1.seek(new Range(new Text("r1")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar2");
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski1.hasTop());
    
    ski1.seek(new Range(new Text("r1")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar2");
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski1.hasTop());
    
    ski1.seek(new Range(new Text("r2")), LocalityGroupUtil.EMPTY_CF_SET, false);
    assertFalse(ski1.hasTop());
    
    ski1.seek(new Range(nk("r1", "foo:cq1", 3), null), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar2");
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski1.hasTop());
    
    ski1.close();
  }
  
  public void test5() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    mutate(imm, "r1", "foo:cq1", 3, "bar2");
    mutate(imm, "r1", "foo:cq1", 3, "bar3");
    
    MemoryIterator ski1 = imm.skvIterator();
    ski1.seek(new Range(new Text("r1")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar3");
    
    imm.delete(0);
    
    ae(ski1, "r1", "foo:cq1", 3, "bar2");
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    assertFalse(ski1.hasTop());
    
    ski1.close();
    
    imm = new InMemoryMap(false, "/tmp");
    
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    mutate(imm, "r1", "foo:cq2", 3, "bar2");
    mutate(imm, "r1", "foo:cq3", 3, "bar3");
    
    ski1 = imm.skvIterator();
    ski1.seek(new Range(new Text("r1")), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    
    imm.delete(0);
    
    ae(ski1, "r1", "foo:cq2", 3, "bar2");
    ae(ski1, "r1", "foo:cq3", 3, "bar3");
    assertFalse(ski1.hasTop());
    
    ski1.close();
  }
  
  public void test6() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    mutate(imm, "r1", "foo:cq1", 3, "bar1");
    mutate(imm, "r1", "foo:cq2", 3, "bar2");
    mutate(imm, "r1", "foo:cq3", 3, "bar3");
    mutate(imm, "r1", "foo:cq4", 3, "bar4");
    
    MemoryIterator ski1 = imm.skvIterator();
    
    mutate(imm, "r1", "foo:cq5", 3, "bar5");
    
    SortedKeyValueIterator<Key,Value> dc = ski1.deepCopy(null);
    
    ski1.seek(new Range(nk("r1", "foo:cq1", 3), null), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(ski1, "r1", "foo:cq1", 3, "bar1");
    
    dc.seek(new Range(nk("r1", "foo:cq2", 3), null), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(dc, "r1", "foo:cq2", 3, "bar2");
    
    imm.delete(0);
    
    ae(ski1, "r1", "foo:cq2", 3, "bar2");
    ae(dc, "r1", "foo:cq3", 3, "bar3");
    ae(ski1, "r1", "foo:cq3", 3, "bar3");
    ae(dc, "r1", "foo:cq4", 3, "bar4");
    ae(ski1, "r1", "foo:cq4", 3, "bar4");
    assertFalse(ski1.hasTop());
    assertFalse(dc.hasTop());
    
    ski1.seek(new Range(nk("r1", "foo:cq3", 3), null), LocalityGroupUtil.EMPTY_CF_SET, false);
    
    dc.seek(new Range(nk("r1", "foo:cq4", 3), null), LocalityGroupUtil.EMPTY_CF_SET, false);
    ae(dc, "r1", "foo:cq4", 3, "bar4");
    assertFalse(dc.hasTop());
    
    ae(ski1, "r1", "foo:cq3", 3, "bar3");
    ae(ski1, "r1", "foo:cq4", 3, "bar4");
    assertFalse(ski1.hasTop());
    assertFalse(dc.hasTop());
    
    ski1.close();
  }
  
  public void testBug1() throws Exception {
    InMemoryMap imm = new InMemoryMap(false, "/tmp");
    
    for (int i = 0; i < 20; i++) {
      mutate(imm, "r1", "foo:cq" + i, 3, "bar" + i);
    }
    
    for (int i = 0; i < 20; i++) {
      mutate(imm, "r2", "foo:cq" + i, 3, "bar" + i);
    }
    
    MemoryIterator ski1 = imm.skvIterator();
    ColumnFamilySkippingIterator cfsi = new ColumnFamilySkippingIterator(ski1);
    
    imm.delete(0);
    
    ArrayList<ByteSequence> columns = new ArrayList<ByteSequence>();
    columns.add(new ArrayByteSequence("bar"));
    
    // this seek resulted in an infinite loop before a bug was fixed
    cfsi.seek(new Range("r1"), columns, true);
    
    assertFalse(cfsi.hasTop());
    
    ski1.close();
  }
  
  private static final Logger log = Logger.getLogger(InMemoryMapTest.class);

  static long sum(long[] counts) {
    long result = 0;
    for (int i = 0; i < counts.length; i++) 
      result  += counts[i];
    return result;
  }
  
  // @Test - hard to get this timing test to run well on apache build machines
  public void parallelWriteSpeed() throws InterruptedException {
    List<Double> timings = new ArrayList<Double>();
    for (int threads: new int[]{1, 2, 16, 64, 256} ) {
      final long now = System.currentTimeMillis();
      final long counts[] = new long[threads];
      final InMemoryMap imm = new InMemoryMap(false, "/tmp");
      ExecutorService e = Executors.newFixedThreadPool(threads);
      for (int j = 0; j < threads; j++) {
        final int threadId = j;
        e.execute(new Runnable() {
          @Override
          public void run() {
            while (System.currentTimeMillis() - now < 1000) {
              for (int k = 0; k < 1000; k++) {
                Mutation m = new Mutation("row");
                m.put("cf", "cq", new Value("v".getBytes()));
                List<Mutation> mutations = Collections.singletonList(m);
                imm.mutate(mutations);
                counts[threadId]++;
              }
            }
          }
        });
      }
      e.shutdown();
      e.awaitTermination(10, TimeUnit.SECONDS);
      imm.delete(10000);
      double mutationsPerSecond = sum(counts)/((System.currentTimeMillis() - now)/1000.);
      timings.add(mutationsPerSecond);
      log.info(String.format("%.1f mutations per second with %d threads", mutationsPerSecond, threads));
    }
    // verify that more threads doesn't go a lot faster, or a lot slower than one thread
    for (int i = 0; i < timings.size(); i++) {
      double ratioFirst = timings.get(0) / timings.get(i); 
      assertTrue(ratioFirst < 2);
      assertTrue(ratioFirst > 0.5);
    }
  }

}
