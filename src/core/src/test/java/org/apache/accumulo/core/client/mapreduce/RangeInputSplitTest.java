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
package org.apache.accumulo.core.client.mapreduce;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.Pair;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

public class RangeInputSplitTest {

  @Test
  public void testSimpleWritable() throws IOException {
    RangeInputSplit split = new RangeInputSplit(new Range(new Key("a"), new Key("b")), new String[]{"localhost"});
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    split.write(dos);
    
    RangeInputSplit newSplit = new RangeInputSplit();
    
    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    DataInputStream dis = new DataInputStream(bais);
    newSplit.readFields(dis);
    
    Assert.assertEquals(split.getRange(), newSplit.getRange());
    Assert.assertTrue(Arrays.equals(split.getLocations(), newSplit.getLocations()));
  }



  @Test
  public void testAllFieldsWritable() throws IOException {
    RangeInputSplit split = new RangeInputSplit(new Range(new Key("a"), new Key("b")), new String[]{"localhost"});
    
    Set<Pair<Text,Text>> fetchedColumns = new HashSet<Pair<Text,Text>>();
    
    fetchedColumns.add(new Pair<Text,Text>(new Text("colf1"), new Text("colq1")));
    fetchedColumns.add(new Pair<Text,Text>(new Text("colf2"), new Text("colq2")));
    
    split.setAuths(new Authorizations("foo"));
    split.setOffline(true);
    split.setIsolatedScan(true);
    split.setUsesLocalIterators(true);
    split.setMaxVersions(5);
    split.setRowRegex("row");
    split.setColfamRegex("colf");
    split.setColqualRegex("colq");
    split.setValueRegex("value");
    split.setFetchedColumns(fetchedColumns);
    split.setPassword("password".getBytes());
    split.setUsername("root");
    split.setInstanceName("instance");
    split.setMockInstance(true);
    split.setZooKeepers("localhost");
    split.setLogLevel(Level.WARN);
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    split.write(dos);
    
    RangeInputSplit newSplit = new RangeInputSplit();
    
    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    DataInputStream dis = new DataInputStream(bais);
    newSplit.readFields(dis);
    
    Assert.assertEquals(split.getRange(), newSplit.getRange());
    Assert.assertArrayEquals(split.getLocations(), newSplit.getLocations());
    
    Assert.assertEquals(split.getAuths(), newSplit.getAuths());
    Assert.assertEquals(split.isOffline(), newSplit.isOffline());
    Assert.assertEquals(split.isIsolatedScan(), newSplit.isOffline());
    Assert.assertEquals(split.usesLocalIterators(), newSplit.usesLocalIterators());
    Assert.assertEquals(split.getMaxVersions(), newSplit.getMaxVersions());
    Assert.assertEquals(split.getRowRegex(), newSplit.getRowRegex());
    Assert.assertEquals(split.getColfamRegex(), newSplit.getColfamRegex());
    Assert.assertEquals(split.getColqualRegex(), newSplit.getColqualRegex());
    Assert.assertEquals(split.getValueRegex(), newSplit.getValueRegex());
    Assert.assertEquals(split.getFetchedColumns(), newSplit.getFetchedColumns());
    Assert.assertEquals(new String(split.getPassword()), new String(newSplit.getPassword()));
    Assert.assertEquals(split.getUsername(), newSplit.getUsername());
    Assert.assertEquals(split.getInstanceName(), newSplit.getInstanceName());
    Assert.assertEquals(split.isMockInstance(), newSplit.isMockInstance());
    Assert.assertEquals(split.getZooKeepers(), newSplit.getZooKeepers());
    Assert.assertEquals(split.getLogLevel(), newSplit.getLogLevel());
  }
  
}
