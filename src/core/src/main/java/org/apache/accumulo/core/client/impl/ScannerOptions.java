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
package org.apache.accumulo.core.client.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.data.Column;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.thrift.IterInfo;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.util.ArgumentChecker;
import org.apache.accumulo.core.util.TextUtil;
import org.apache.hadoop.io.Text;

public class ScannerOptions implements ScannerBase {
  
  protected List<IterInfo> serverSideIteratorList = Collections.emptyList();
  protected Map<String,Map<String,String>> serverSideIteratorOptions = Collections.emptyMap();
  
  protected SortedSet<Column> fetchedColumns = new TreeSet<Column>();
  
  private String regexIterName = null;
  
  protected ScannerOptions() {}
  
  public ScannerOptions(ScannerOptions so) {
    setOptions(this, so);
  }
  
  /**
   * Adds server-side scan iterators.
   * 
   */
  @Override
  public synchronized void addScanIterator(IteratorSetting si) {
    ArgumentChecker.notNull(si);
    if (serverSideIteratorList.size() == 0)
      serverSideIteratorList = new ArrayList<IterInfo>();
    
    for (IterInfo ii : serverSideIteratorList) {
      if (ii.iterName.equals(si.getName()))
        throw new IllegalArgumentException("Iterator name is already in use " + si.getName());
      if (ii.getPriority() == si.getPriority())
        throw new IllegalArgumentException("Iterator priority is already in use " + si.getPriority());
    }
    
    serverSideIteratorList.add(new IterInfo(si.getPriority(), si.getIteratorClass(), si.getName()));
    
    if (serverSideIteratorOptions.size() == 0)
      serverSideIteratorOptions = new HashMap<String,Map<String,String>>();
    
    Map<String,String> opts = serverSideIteratorOptions.get(si.getName());
    
    if (opts == null) {
      opts = new HashMap<String,String>();
      serverSideIteratorOptions.put(si.getName(), opts);
    }
    opts.putAll(si.getOptions());
  }
  
  @Override
  public synchronized void removeScanIterator(String iteratorName) {
    ArgumentChecker.notNull(iteratorName);
    // if no iterators are set, we don't have it, so it is already removed
    if (serverSideIteratorList.size() == 0)
      return;
    
    for (IterInfo ii : serverSideIteratorList) {
      if (ii.iterName.equals(iteratorName)) {
        serverSideIteratorList.remove(ii);
        break;
      }
    }
    
    serverSideIteratorOptions.remove(iteratorName);
  }
  
  /**
   * @deprecated since 1.4, use {@link #addScanIterator(IteratorSetting)}
   */
  @Deprecated
  @Override
  public void setScanIterators(int priority, String iteratorClass, String iteratorName) {
    addScanIterator(new IteratorSetting(priority, iteratorName, iteratorClass));
  }
  
  /**
   * @deprecated since 1.4, use {@link #updateScanIteratorOption(String, String, String)}
   */
  @Deprecated
  @Override
  public synchronized void setScanIteratorOption(String iteratorName, String key, String value) {
    updateScanIteratorOption(iteratorName, key, value);
  }
  
  /**
   * Override any existing options on the given named iterator
   */
  @Override
  public synchronized void updateScanIteratorOption(String iteratorName, String key, String value) {
    ArgumentChecker.notNull(iteratorName, key, value);
    if (serverSideIteratorOptions.size() == 0)
      serverSideIteratorOptions = new HashMap<String,Map<String,String>>();
    
    Map<String,String> opts = serverSideIteratorOptions.get(iteratorName);
    
    if (opts == null) {
      opts = new HashMap<String,String>();
      serverSideIteratorOptions.put(iteratorName, opts);
    }
    opts.put(key, value);
  }
  
  /**
   * Must call this method to initialize regular expresions on a scanner.
   * 
   * @deprecated since 1.4, use {@link #addScanIterator(IteratorSetting)}
   * @see org.apache.accumulo.core.iterators.user.RegExFilter
   */
  @Deprecated
  @Override
  public synchronized void setupRegex(String iteratorName, int iteratorPriority) throws IOException {
    ArgumentChecker.notNull(iteratorName);
    if (regexIterName != null)
      throw new RuntimeException("regex already setup");
    
    addScanIterator(new IteratorSetting(iteratorPriority, iteratorName, RegExFilter.class));
    regexIterName = iteratorName;
  }
  
  private synchronized void setupDefaultRegex() {
    try {
      setupRegex("regExAuto", Integer.MAX_VALUE);
    } catch (Exception e) {
      throw new RuntimeException("Failed to setup default regex");
    }
  }
  
  /**
   * Set a row regular expression that filters non matching entries server side.
   * 
   * @deprecated since 1.4, use {@link #addScanIterator(IteratorSetting)}
   * @see org.apache.accumulo.core.iterators.user.RegExFilter
   */
  @Deprecated
  @Override
  public synchronized void setRowRegex(String regex) {
    ArgumentChecker.notNull(regex);
    if (regexIterName == null)
      setupDefaultRegex();
    setScanIteratorOption(regexIterName, RegExFilter.ROW_REGEX, regex);
  }
  
  /**
   * Set a column family regular expression that filters non matching entries server side.
   * 
   * @deprecated since 1.4, use{@link #addScanIterator(IteratorSetting)}
   * @see org.apache.accumulo.core.iterators.user.RegExFilter
   */
  @Deprecated
  @Override
  public synchronized void setColumnFamilyRegex(String regex) {
    ArgumentChecker.notNull(regex);
    if (regexIterName == null)
      setupDefaultRegex();
    setScanIteratorOption(regexIterName, RegExFilter.COLF_REGEX, regex);
  }
  
  /**
   * Set a column qualifier regular expression that filters non matching entries server side.
   * 
   * @deprecated since 1.4, use {@link #addScanIterator(IteratorSetting)}
   * @see org.apache.accumulo.core.iterators.user.RegExFilter
   */
  @Deprecated
  @Override
  public synchronized void setColumnQualifierRegex(String regex) {
    ArgumentChecker.notNull(regex);
    if (regexIterName == null)
      setupDefaultRegex();
    setScanIteratorOption(regexIterName, RegExFilter.COLQ_REGEX, regex);
  }
  
  /**
   * Set a value regular expression that filters non matching entries server side.
   * 
   * @deprecated since 1.4, use {@link #addScanIterator(IteratorSetting)}
   * @see org.apache.accumulo.core.iterators.user.RegExFilter
   */
  @Deprecated
  @Override
  public synchronized void setValueRegex(String regex) {
    ArgumentChecker.notNull(regex);
    if (regexIterName == null)
      setupDefaultRegex();
    setScanIteratorOption(regexIterName, RegExFilter.VALUE_REGEX, regex);
  }
  
  /**
   * Limit a scan to the specified column family. This can limit which locality groups are read on the server side.
   * 
   * To fetch multiple column families call this function multiple times.
   */
  
  @Override
  public synchronized void fetchColumnFamily(Text col) {
    ArgumentChecker.notNull(col);
    Column c = new Column(TextUtil.getBytes(col), null, null);
    fetchedColumns.add(c);
  }
  
  @Override
  public synchronized void fetchColumn(Text colFam, Text colQual) {
    ArgumentChecker.notNull(colFam, colQual);
    Column c = new Column(TextUtil.getBytes(colFam), TextUtil.getBytes(colQual), null);
    fetchedColumns.add(c);
  }
  
  public synchronized void fetchColumn(Column column) {
    ArgumentChecker.notNull(column);
    fetchedColumns.add(column);
  }
  
  @Override
  public synchronized void clearColumns() {
    fetchedColumns.clear();
  }
  
  public synchronized SortedSet<Column> getFetchedColumns() {
    return fetchedColumns;
  }
  
  /**
   * Clears scan iterators prior to returning a scanner to the pool.
   */
  @Override
  public synchronized void clearScanIterators() {
    serverSideIteratorList = Collections.emptyList();
    serverSideIteratorOptions = Collections.emptyMap();
    regexIterName = null;
  }
  
  protected static void setOptions(ScannerOptions dst, ScannerOptions src) {
    synchronized (dst) {
      synchronized (src) {
        dst.regexIterName = src.regexIterName;
        dst.fetchedColumns = new TreeSet<Column>(src.fetchedColumns);
        dst.serverSideIteratorList = new ArrayList<IterInfo>(src.serverSideIteratorList);
        
        dst.serverSideIteratorOptions = new HashMap<String,Map<String,String>>();
        Set<Entry<String,Map<String,String>>> es = src.serverSideIteratorOptions.entrySet();
        for (Entry<String,Map<String,String>> entry : es)
          dst.serverSideIteratorOptions.put(entry.getKey(), new HashMap<String,String>(entry.getValue()));
      }
    }
  }
  
  @Override
  public Iterator<Entry<Key,Value>> iterator() {
    throw new UnsupportedOperationException();
  }
}
