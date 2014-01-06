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
package org.apache.accumulo.proxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.admin.ActiveScan;
import org.apache.accumulo.core.client.admin.TimeType;
import org.apache.accumulo.core.client.impl.thrift.TableOperationExceptionType;
import org.apache.accumulo.core.client.impl.thrift.ThriftTableOperationException;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Column;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.security.thrift.AuthInfo;
import org.apache.accumulo.core.security.thrift.SecurityErrorCode;
import org.apache.accumulo.core.util.ByteBufferUtil;
import org.apache.accumulo.core.util.TextUtil;
import org.apache.accumulo.proxy.thrift.AccumuloProxy;
import org.apache.accumulo.proxy.thrift.BatchScanOptions;
import org.apache.accumulo.proxy.thrift.ColumnUpdate;
import org.apache.accumulo.proxy.thrift.KeyValue;
import org.apache.accumulo.proxy.thrift.KeyValueAndPeek;
import org.apache.accumulo.proxy.thrift.NoMoreEntriesException;
import org.apache.accumulo.proxy.thrift.ScanColumn;
import org.apache.accumulo.proxy.thrift.ScanOptions;
import org.apache.accumulo.proxy.thrift.ScanResult;
import org.apache.accumulo.proxy.thrift.ScanState;
import org.apache.accumulo.proxy.thrift.ScanType;
import org.apache.accumulo.proxy.thrift.UnknownScanner;
import org.apache.accumulo.proxy.thrift.UnknownWriter;
import org.apache.accumulo.proxy.thrift.WriterOptions;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

/**
 * Proxy Server exposing the Accumulo API via Thrift..
 * 
 * @since 1.5, backported to 1.4.4
 */
public class ProxyServer implements AccumuloProxy.Iface {
  
  private static final Long DEFAULT_MAX_MEMORY = 50 * 1024 * 1024l;
  private static final Long DEFAULT_MAX_LATENCY = 2 * 60 * 1000l;
  private static final Long DEFAULT_TIMEOUT = Long.MAX_VALUE;
  private static final Integer DEFAULT_MAX_WRITE_THREADS = 3;
  
  public static final Logger logger = Logger.getLogger(ProxyServer.class);
  protected Instance instance;
  
  static protected class ScannerPlusIterator {
    public ScannerBase scanner;
    public Iterator<Map.Entry<Key,Value>> iterator;
  }
  
  static protected class BatchWriterPlusException {
    public BatchWriter writer;
    public MutationsRejectedException exception = null;
  }
  
  static class CloseWriter implements RemovalListener<UUID,BatchWriterPlusException> {
    @Override
    public void onRemoval(RemovalNotification<UUID,BatchWriterPlusException> notification) {
      try {
        BatchWriterPlusException value = notification.getValue();
        if (value.exception != null)
          throw value.exception;
        notification.getValue().writer.close();
      } catch (MutationsRejectedException e) {
        logger.warn(e, e);
      }
    }
    
    public CloseWriter() {}
  }
  
  static class CloseScanner implements RemovalListener<UUID,ScannerPlusIterator> {
    @Override
    public void onRemoval(RemovalNotification<UUID,ScannerPlusIterator> notification) {
      final ScannerBase base = notification.getValue().scanner;
      if (base instanceof BatchScanner) {
        final BatchScanner scanner = (BatchScanner) base;
        scanner.close();
      }
    }
    
    public CloseScanner() {}
  }
  
  protected Cache<UUID,ScannerPlusIterator> scannerCache;
  protected Cache<UUID,BatchWriterPlusException> writerCache;
  
  protected final String PASSWORD_PROP = "password";
  
  public ProxyServer(Properties props) {
    
    String useMock = props.getProperty("useMockInstance");
    if (useMock != null && Boolean.parseBoolean(useMock))
      instance = new MockInstance();
    else
      instance = new ZooKeeperInstance(props.getProperty("instance"), props.getProperty("zookeepers"));
    
    scannerCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(1000).removalListener(new CloseScanner()).build();
    
    writerCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(1000).removalListener(new CloseWriter()).build();
  }
  
  protected Connector getConnector(ByteBuffer login) throws Exception {
    AuthInfo user = CredentialHelper.fromByteArray(ByteBufferUtil.toBytes(login));
    if (user == null)
      throw new org.apache.accumulo.proxy.thrift.AccumuloSecurityException("unknown user");
    Connector connector = instance.getConnector(user.getUser(), user.getPassword());
    return connector;
  }
  
  private void handleAccumuloException(AccumuloException e) throws org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.AccumuloException {
    if (e.getCause() instanceof ThriftTableOperationException) {
      ThriftTableOperationException ttoe = (ThriftTableOperationException) e.getCause();
      if (ttoe.type == TableOperationExceptionType.NOTFOUND) {
        throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(e.toString());
      }
    }
    throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
  }
  
  private void handleAccumuloSecurityException(AccumuloSecurityException e) throws org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException {
    if (e.getErrorCode().equals(SecurityErrorCode.TABLE_DOESNT_EXIST))
      throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(e.toString());
    throw new org.apache.accumulo.proxy.thrift.AccumuloSecurityException(e.toString());
  }

  private void handleExceptionTNF(Exception ex) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      throw ex;
    } catch (AccumuloException e) {
      handleAccumuloException(e);
    } catch (AccumuloSecurityException e) {
      handleAccumuloSecurityException(e);
    } catch (TableNotFoundException e) {
      throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(ex.toString());
    } catch (Exception e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
    }
  }

  private void handleExceptionTEE(Exception ex) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.TableExistsException, TException {
    try {
      throw ex;
    } catch (AccumuloException e) {
      handleAccumuloException(e);
    } catch (AccumuloSecurityException e) {
      handleAccumuloSecurityException(e);
    } catch (TableNotFoundException e) {
      throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(ex.toString());
    } catch (TableExistsException e) {
      throw new org.apache.accumulo.proxy.thrift.TableExistsException(e.toString());
    } catch (Exception e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
    }
  }
  
  private void handleExceptionMRE(Exception ex) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.MutationsRejectedException, TException {
    try {
      throw ex;
    } catch (MutationsRejectedException e) {
      throw new org.apache.accumulo.proxy.thrift.MutationsRejectedException(ex.toString());
    } catch (AccumuloException e) {
      handleAccumuloException(e);
    } catch (AccumuloSecurityException e) {
      handleAccumuloSecurityException(e);
    } catch (TableNotFoundException e) {
      throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(ex.toString());
    } catch (Exception e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
    }
  }
  
  private void handleException(Exception ex) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      throw ex;
    } catch (AccumuloException e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
    } catch (AccumuloSecurityException e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloSecurityException(e.toString());
    } catch (Exception e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloException(e.toString());
    }
  }

  @Override
  public int addConstraint(ByteBuffer login, String tableName, String constraintClassName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Connector connector = getConnector(login);
      
      checkTableExists(connector, tableName);
      
      TreeSet<Integer> constraintNumbers = new TreeSet<Integer>();
      TreeMap<String,Integer> constraintClasses = new TreeMap<String,Integer>();
      int i;
      for (Map.Entry<String,String> property : connector.tableOperations().getProperties(tableName)) {
        if (property.getKey().startsWith(Property.TABLE_CONSTRAINT_PREFIX.toString())) {
          try {
            i = Integer.parseInt(property.getKey().substring(Property.TABLE_CONSTRAINT_PREFIX.toString().length()));
          } catch (NumberFormatException e) {
            throw new org.apache.accumulo.proxy.thrift.AccumuloException("Bad key for existing constraint: " + property.toString());
          }
          constraintNumbers.add(i);
          constraintClasses.put(property.getValue(), i);
        }
      }
      
      i = 1;
      while (constraintNumbers.contains(i))
        i++;
      if (constraintClasses.containsKey(constraintClassName))
        throw new AccumuloException("Constraint " + constraintClassName + " already exists for table " + tableName + " with number "
            + constraintClasses.get(constraintClassName));
      connector.tableOperations().setProperty(tableName, Property.TABLE_CONSTRAINT_PREFIX.toString() + i, constraintClassName);
      return i;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return -1;
    }
  }
  
  @Override
  public void addSplits(ByteBuffer login, String tableName, Set<ByteBuffer> splits) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    
    try {
      SortedSet<Text> sorted = new TreeSet<Text>();
      for (ByteBuffer split : splits) {
        sorted.add(ByteBufferUtil.toText(split));
      }
      getConnector(login).tableOperations().addSplits(tableName, sorted);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void clearLocatorCache(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().clearLocatorCache(tableName);
    } catch (TableNotFoundException e) {
      throw new org.apache.accumulo.proxy.thrift.TableNotFoundException(e.toString());
    } catch (Exception e) {
      throw new TException(e.toString());
    }
  }
  
  @Override
  public void compactTable(ByteBuffer login, String tableName, ByteBuffer startRow, ByteBuffer endRow,
      List<org.apache.accumulo.proxy.thrift.IteratorSetting> iterators, boolean flush, boolean wait)
      throws org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.AccumuloException, TException {
    try {
      if (iterators != null && iterators.size() > 0)
        throw new UnsupportedOperationException("compactTable does not support passing iterators until Accumulo 1.5.0");
      getConnector(login).tableOperations().compact(tableName, ByteBufferUtil.toText(startRow), ByteBufferUtil.toText(endRow), flush, wait);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void createTable(ByteBuffer login, String tableName, boolean versioningIter, org.apache.accumulo.proxy.thrift.TimeType type)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableExistsException, TException {
    try {
      if (type == null)
        type = org.apache.accumulo.proxy.thrift.TimeType.MILLIS;
      
      getConnector(login).tableOperations().create(tableName, versioningIter, TimeType.valueOf(type.toString()));
    } catch (TableExistsException e) {
      throw new org.apache.accumulo.proxy.thrift.TableExistsException(e.toString());
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void deleteTable(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().delete(tableName);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void deleteRows(ByteBuffer login, String tableName, ByteBuffer startRow, ByteBuffer endRow) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().deleteRows(tableName, ByteBufferUtil.toText(startRow), ByteBufferUtil.toText(endRow));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public boolean tableExists(ByteBuffer login, String tableName) throws TException {
    try {
      return getConnector(login).tableOperations().exists(tableName);
    } catch (Exception e) {
      throw new TException(e);
    }
  }
  
  @Override
  public void flushTable(ByteBuffer login, String tableName, ByteBuffer startRow, ByteBuffer endRow, boolean wait)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().flush(tableName, ByteBufferUtil.toText(startRow), ByteBufferUtil.toText(endRow), wait);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public Map<String,Set<String>> getLocalityGroups(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      checkTableExists(getConnector(login), tableName);
      Map<String,Set<Text>> groups = getConnector(login).tableOperations().getLocalityGroups(tableName);
      Map<String,Set<String>> ret = new HashMap<String,Set<String>>();
      for (String key : groups.keySet()) {
        ret.put(key, new HashSet<String>());
        for (Text val : groups.get(key)) {
          ret.get(key).add(val.toString());
        }
      }
      return ret;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public ByteBuffer getMaxRow(ByteBuffer login, String tableName, Set<ByteBuffer> auths, ByteBuffer startRow, boolean startInclusive, ByteBuffer endRow,
      boolean endInclusive) throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Connector connector = getConnector(login);
      Text startText = ByteBufferUtil.toText(startRow);
      Text endText = ByteBufferUtil.toText(endRow);
      Authorizations auth;
      if (auths != null) {
        auth = getAuthorizations(auths);
      } else {
        auth = connector.securityOperations().getUserAuthorizations(connector.whoami());
      }
      Text max = connector.tableOperations().getMaxRow(tableName, auth, startText, startInclusive, endText, endInclusive);
      return TextUtil.getByteBuffer(max);
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public Map<String,String> getTableProperties(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      checkTableExists(getConnector(login), tableName);
      Map<String,String> ret = new HashMap<String,String>();
      
      for (Map.Entry<String,String> entry : getConnector(login).tableOperations().getProperties(tableName)) {
        ret.put(entry.getKey(), entry.getValue());
      }
      return ret;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public List<ByteBuffer> listSplits(ByteBuffer login, String tableName, int maxSplits) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Collection<Text> splits = getConnector(login).tableOperations().getSplits(tableName, maxSplits);
      List<ByteBuffer> ret = new ArrayList<ByteBuffer>();
      for (Text split : splits) {
        ret.add(TextUtil.getByteBuffer(split));
      }
      return ret;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public Set<String> listTables(ByteBuffer login) throws TException {
    try {
      return getConnector(login).tableOperations().list();
    } catch (Exception e) {
      throw new TException(e);
    }
  }
  
  @Override
  public Map<String,Integer> listConstraints(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      checkTableExists(getConnector(login), tableName);
      Connector connector = getConnector(login);
      
      Map<String,Integer> constraints = new TreeMap<String,Integer>();
      for (Map.Entry<String,String> property : connector.tableOperations().getProperties(tableName)) {
        if (property.getKey().startsWith(Property.TABLE_CONSTRAINT_PREFIX.toString())) {
          if (constraints.containsKey(property.getValue()))
            throw new AccumuloException("Same constraint configured twice: " + property.getKey() + "=" + Property.TABLE_CONSTRAINT_PREFIX
                + constraints.get(property.getValue()) + "=" + property.getKey());
          try {
            constraints.put(property.getValue(), Integer.parseInt(property.getKey().substring(Property.TABLE_CONSTRAINT_PREFIX.toString().length())));
          } catch (NumberFormatException e) {
            throw new AccumuloException("Bad key for existing constraint: " + property.toString());
          }
        }
      }
      return constraints;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public void mergeTablets(ByteBuffer login, String tableName, ByteBuffer startRow, ByteBuffer endRow)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().merge(tableName, ByteBufferUtil.toText(startRow), ByteBufferUtil.toText(endRow));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void offlineTable(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().offline(tableName);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void onlineTable(ByteBuffer login, String tableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().online(tableName);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void removeConstraint(ByteBuffer login, String tableName, int constraint) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    
    try {
      getConnector(login).tableOperations().removeProperty(tableName, Property.TABLE_CONSTRAINT_PREFIX.toString() + constraint);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void removeTableProperty(ByteBuffer login, String tableName, String property) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().removeProperty(tableName, property);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void renameTable(ByteBuffer login, String oldTableName, String newTableName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException,
      org.apache.accumulo.proxy.thrift.TableExistsException, TException {
    try {
      getConnector(login).tableOperations().rename(oldTableName, newTableName);
    } catch (Exception e) {
      handleExceptionTEE(e);
    }
  }
  
  @Override
  public void setLocalityGroups(ByteBuffer login, String tableName, Map<String,Set<String>> groupStrings)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Map<String,Set<Text>> groups = new HashMap<String,Set<Text>>();
      for (String key : groupStrings.keySet()) {
        groups.put(key, new HashSet<Text>());
        for (String val : groupStrings.get(key)) {
          groups.get(key).add(new Text(val));
        }
      }
      getConnector(login).tableOperations().setLocalityGroups(tableName, groups);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void setTableProperty(ByteBuffer login, String tableName, String property, String value) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().setProperty(tableName, property, value);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public Map<String,String> tableIdMap(ByteBuffer login) throws TException {
    try {
      return getConnector(login).tableOperations().tableIdMap();
    } catch (Exception e) {
      throw new TException(e);
    }
  }
  
  @Override
  public Map<String,String> getSiteConfiguration(ByteBuffer login) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).instanceOperations().getSiteConfiguration();
    } catch (Exception e) {
      handleException(e);
      return null;
    }
  }
  
  @Override
  public Map<String,String> getSystemConfiguration(ByteBuffer login) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).instanceOperations().getSystemConfiguration();
    } catch (Exception e) {
      handleException(e);
      return null;
    }
  }
  
  @Override
  public List<String> getTabletServers(ByteBuffer login) throws TException {
    try {
      return getConnector(login).instanceOperations().getTabletServers();
    } catch (Exception e) {
      throw new TException(e);
    }
  }
  
  @Override
  public List<org.apache.accumulo.proxy.thrift.ActiveScan> getActiveScans(ByteBuffer login, String tserver)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    List<org.apache.accumulo.proxy.thrift.ActiveScan> result = new ArrayList<org.apache.accumulo.proxy.thrift.ActiveScan>();
    try {
      List<ActiveScan> activeScans = getConnector(login).instanceOperations().getActiveScans(tserver);
      for (ActiveScan scan : activeScans) {
        org.apache.accumulo.proxy.thrift.ActiveScan pscan = new org.apache.accumulo.proxy.thrift.ActiveScan();
        pscan.client = scan.getClient();
        pscan.user = scan.getUser();
        pscan.table = scan.getTable();
        pscan.age = scan.getAge();
        pscan.type = ScanType.valueOf(scan.getType().toString());
        pscan.state = ScanState.valueOf(scan.getState().toString());
        KeyExtent e = scan.getExtent();
        pscan.extent = new org.apache.accumulo.proxy.thrift.KeyExtent(e.getTableId().toString(), TextUtil.getByteBuffer(e.getEndRow()),
            TextUtil.getByteBuffer(e.getPrevEndRow()));
        pscan.columns = new ArrayList<org.apache.accumulo.proxy.thrift.Column>();
        if (scan.getColumns() != null) {
          for (Column c : scan.getColumns()) {
            org.apache.accumulo.proxy.thrift.Column column = new org.apache.accumulo.proxy.thrift.Column();
            column.setColFamily(c.getColumnFamily());
            column.setColQualifier(c.getColumnQualifier());
            column.setColVisibility(c.getColumnVisibility());
            pscan.columns.add(column);
          }
        }
        pscan.iterators = new ArrayList<org.apache.accumulo.proxy.thrift.IteratorSetting>();
        for (String iteratorString : scan.getSsiList()) {
          String[] parts = iteratorString.split("[=,]");
          if (parts.length == 3) {
            String name = parts[0];
            int priority = Integer.parseInt(parts[1]);
            String classname = parts[2];
            org.apache.accumulo.proxy.thrift.IteratorSetting settings = new org.apache.accumulo.proxy.thrift.IteratorSetting(priority, name, classname, scan
                .getSsio().get(name));
            pscan.iterators.add(settings);
          }
        }
        
        result.add(pscan);
      }
      return result;
    } catch (Exception e) {
      handleException(e);
      return null;
    }
  }
  
  @Override
  public void removeProperty(ByteBuffer login, String property) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).instanceOperations().removeProperty(property);
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void setProperty(ByteBuffer login, String property, String value) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).instanceOperations().setProperty(property, value);
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public boolean testClassLoad(ByteBuffer login, String className, String asTypeName) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).instanceOperations().testClassLoad(className, asTypeName);
    } catch (Exception e) {
      handleException(e);
      return false;
    }
  }
  
  @Override
  public boolean authenticateUser(ByteBuffer login, String user, Map<String,String> properties) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).securityOperations().authenticateUser(user, properties.get(PASSWORD_PROP).getBytes());
    } catch (Exception e) {
      handleException(e);
      return false;
    }
  }
  
  @Override
  public void changeUserAuthorizations(ByteBuffer login, String user, Set<ByteBuffer> authorizations)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      Set<String> auths = new HashSet<String>();
      for (ByteBuffer auth : authorizations) {
        auths.add(ByteBufferUtil.toString(auth));
      }
      getConnector(login).securityOperations().changeUserAuthorizations(user, new Authorizations(auths.toArray(new String[0])));
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void changeLocalUserPassword(ByteBuffer login, String user, ByteBuffer password) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).securityOperations().changeUserPassword(user, ByteBufferUtil.toBytes(password));
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void createLocalUser(ByteBuffer login, String user, ByteBuffer password) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).securityOperations().createUser(user, ByteBufferUtil.toBytes(password), new Authorizations());
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void dropLocalUser(ByteBuffer login, String user) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).securityOperations().dropUser(user);
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public List<ByteBuffer> getUserAuthorizations(ByteBuffer login, String user) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).securityOperations().getUserAuthorizations(user).getAuthorizationsBB();
    } catch (Exception e) {
      handleException(e);
      return null;
    }
  }
  
  @Override
  public void grantSystemPermission(ByteBuffer login, String user, org.apache.accumulo.proxy.thrift.SystemPermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).securityOperations().grantSystemPermission(user, SystemPermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void grantTablePermission(ByteBuffer login, String user, String table, org.apache.accumulo.proxy.thrift.TablePermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).securityOperations().grantTablePermission(user, table, TablePermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public boolean hasSystemPermission(ByteBuffer login, String user, org.apache.accumulo.proxy.thrift.SystemPermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).securityOperations().hasSystemPermission(user, SystemPermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleException(e);
      return false;
    }
  }
  
  @Override
  public boolean hasTablePermission(ByteBuffer login, String user, String table, org.apache.accumulo.proxy.thrift.TablePermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      return getConnector(login).securityOperations().hasTablePermission(user, table, TablePermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleExceptionTNF(e);
      return false;
    }
  }
  
  @Override
  public Set<String> listLocalUsers(ByteBuffer login) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      return getConnector(login).securityOperations().listUsers();
    } catch (Exception e) {
      handleException(e);
      return null;
    }
  }
  
  @Override
  public void revokeSystemPermission(ByteBuffer login, String user, org.apache.accumulo.proxy.thrift.SystemPermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).securityOperations().revokeSystemPermission(user, SystemPermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleException(e);
    }
  }
  
  @Override
  public void revokeTablePermission(ByteBuffer login, String user, String table, org.apache.accumulo.proxy.thrift.TablePermission perm)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).securityOperations().revokeTablePermission(user, table, TablePermission.getPermissionById((byte) perm.getValue()));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  private Authorizations getAuthorizations(Set<ByteBuffer> authorizations) {
    List<String> auths = new ArrayList<String>();
    for (ByteBuffer bbauth : authorizations) {
      auths.add(ByteBufferUtil.toString(bbauth));
    }
    return new Authorizations(auths.toArray(new String[0]));
  }
  
  @Override
  public String createScanner(ByteBuffer login, String tableName, ScanOptions opts) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Connector connector = getConnector(login);
      
      Authorizations auth;
      if (opts != null && opts.isSetAuthorizations()) {
        auth = getAuthorizations(opts.authorizations);
      } else {
        auth = connector.securityOperations().getUserAuthorizations(connector.whoami());
      }
      Scanner scanner = connector.createScanner(tableName, auth);
      
      if (opts != null) {
        if (opts.iterators != null) {
          for (org.apache.accumulo.proxy.thrift.IteratorSetting iter : opts.iterators) {
            IteratorSetting is = new IteratorSetting(iter.getPriority(), iter.getName(), iter.getIteratorClass(), iter.getProperties());
            scanner.addScanIterator(is);
          }
        }
        org.apache.accumulo.proxy.thrift.Range prange = opts.range;
        if (prange != null) {
          Range range = new Range(Util.fromThrift(prange.getStart()), prange.startInclusive, Util.fromThrift(prange.getStop()), prange.stopInclusive);
          scanner.setRange(range);
        }
        if (opts.columns != null) {
          for (ScanColumn col : opts.columns) {
            if (col.isSetColQualifier())
              scanner.fetchColumn(ByteBufferUtil.toText(col.colFamily), ByteBufferUtil.toText(col.colQualifier));
            else
              scanner.fetchColumnFamily(ByteBufferUtil.toText(col.colFamily));
          }
        }
      }
      
      UUID uuid = UUID.randomUUID();
      
      ScannerPlusIterator spi = new ScannerPlusIterator();
      spi.scanner = scanner;
      spi.iterator = scanner.iterator();
      scannerCache.put(uuid, spi);
      return uuid.toString();
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public String createBatchScanner(ByteBuffer login, String tableName, BatchScanOptions opts) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Connector connector = getConnector(login);
      
      int threads = 10;
      Authorizations auth;
      if (opts != null && opts.isSetAuthorizations()) {
        auth = getAuthorizations(opts.authorizations);
      } else {
        auth = connector.securityOperations().getUserAuthorizations(connector.whoami());
      }
      if (opts != null && opts.threads > 0)
        threads = opts.threads;
      
      BatchScanner scanner = connector.createBatchScanner(tableName, auth, threads);
      
      if (opts != null) {
        if (opts.iterators != null) {
          for (org.apache.accumulo.proxy.thrift.IteratorSetting iter : opts.iterators) {
            IteratorSetting is = new IteratorSetting(iter.getPriority(), iter.getName(), iter.getIteratorClass(), iter.getProperties());
            scanner.addScanIterator(is);
          }
        }
        
        ArrayList<Range> ranges = new ArrayList<Range>();
        
        if (opts.ranges == null) {
          ranges.add(new Range());
        } else {
          for (org.apache.accumulo.proxy.thrift.Range range : opts.ranges) {
            Range aRange = new Range(range.getStart() == null ? null : Util.fromThrift(range.getStart()), true, range.getStop() == null ? null
                : Util.fromThrift(range.getStop()), false);
            ranges.add(aRange);
          }
        }
        scanner.setRanges(ranges);
        
        if (opts.columns != null) {
          for (ScanColumn col : opts.columns) {
            if (col.isSetColQualifier())
              scanner.fetchColumn(ByteBufferUtil.toText(col.colFamily), ByteBufferUtil.toText(col.colQualifier));
            else
              scanner.fetchColumnFamily(ByteBufferUtil.toText(col.colFamily));
          }
        }
      }
      
      UUID uuid = UUID.randomUUID();
      
      ScannerPlusIterator spi = new ScannerPlusIterator();
      spi.scanner = scanner;
      spi.iterator = scanner.iterator();
      scannerCache.put(uuid, spi);
      return uuid.toString();
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  private ScannerPlusIterator getScanner(String scanner) throws UnknownScanner {
    
    UUID uuid = null;
    try {
      uuid = UUID.fromString(scanner);
    } catch (IllegalArgumentException e) {
      throw new UnknownScanner(e.getMessage());
    }
    
    ScannerPlusIterator spi = scannerCache.getIfPresent(uuid);
    if (spi == null) {
      throw new UnknownScanner("Scanner never existed or no longer exists");
    }
    return spi;
  }
  
  @Override
  public boolean hasNext(String scanner) throws UnknownScanner, TException {
    ScannerPlusIterator spi = getScanner(scanner);
    
    return (spi.iterator.hasNext());
  }
  
  @Override
  public KeyValueAndPeek nextEntry(String scanner) throws NoMoreEntriesException, UnknownScanner, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      TException {
    
    ScanResult scanResult = nextK(scanner, 1);
    if (scanResult.results.size() > 0) {
      return new KeyValueAndPeek(scanResult.results.get(0), scanResult.isMore());
    } else {
      throw new NoMoreEntriesException();
    }
  }
  
  @Override
  public ScanResult nextK(String scanner, int k) throws NoMoreEntriesException, UnknownScanner, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      TException {
    
    // fetch the scanner
    ScannerPlusIterator spi = getScanner(scanner);
    Iterator<Map.Entry<Key,Value>> batchScanner = spi.iterator;
    // synchronized to prevent race conditions
    synchronized (batchScanner) {
      ScanResult ret = new ScanResult();
      ret.setResults(new ArrayList<KeyValue>());
      int numRead = 0;
      try {
        while (batchScanner.hasNext() && numRead < k) {
          Map.Entry<Key,Value> next = batchScanner.next();
          ret.addToResults(new KeyValue(Util.toThrift(next.getKey()), ByteBuffer.wrap(next.getValue().get())));
          numRead++;
        }
        ret.setMore(numRead == k);
      } catch (Exception ex) {
        closeScanner(scanner);
        throw new org.apache.accumulo.proxy.thrift.AccumuloSecurityException(ex.toString());
      }
      return ret;
    }
  }

  @Override
  public void closeScanner(String scanner) throws UnknownScanner, TException {
    UUID uuid = null;
    try {
      uuid = UUID.fromString(scanner);
    } catch (IllegalArgumentException e) {
      throw new UnknownScanner(e.getMessage());
    }

    try {
      if (scannerCache.asMap().remove(uuid) == null) {
        throw new UnknownScanner("Scanner never existed or no longer exists");
      }
    } catch (UnknownScanner e) {
      throw e;
    } catch (Exception e) {
      throw new TException(e.toString());
    }
  }
  
  @Override
  public void updateAndFlush(ByteBuffer login, String tableName, Map<ByteBuffer,List<ColumnUpdate>> cells)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, org.apache.accumulo.proxy.thrift.MutationsRejectedException, TException {
    try {
      BatchWriterPlusException bwpe = getWriter(login, tableName, null);
      addCellsToWriter(cells, bwpe);
      if (bwpe.exception != null)
        throw bwpe.exception;
      bwpe.writer.flush();
      bwpe.writer.close();
    } catch (Exception e) {
      handleExceptionMRE(e);
    }
  }
  
  private static final ColumnVisibility EMPTY_VIS = new ColumnVisibility();
  
  private void addCellsToWriter(Map<ByteBuffer,List<ColumnUpdate>> cells, BatchWriterPlusException bwpe) {
    if (bwpe.exception != null)
      return;
    
    HashMap<Text,ColumnVisibility> vizMap = new HashMap<Text,ColumnVisibility>();
    
    for (Map.Entry<ByteBuffer,List<ColumnUpdate>> entry : cells.entrySet()) {
      Mutation m = new Mutation(new Text(ByteBufferUtil.toBytes(entry.getKey())));
      
      for (ColumnUpdate update : entry.getValue()) {
        ColumnVisibility viz = EMPTY_VIS;
        if (update.isSetColVisibility()) {
          Text vizText = new Text(update.getColVisibility());
          viz = vizMap.get(vizText);
          if (viz == null) {
            vizMap.put(vizText, viz = new ColumnVisibility(vizText));
          }
        }
        byte[] value = new byte[0];
        if (update.isSetValue())
          value = update.getValue();
        if (update.isSetTimestamp()) {
          if (update.isSetDeleteCell()) {
            m.putDelete(new Text(update.getColFamily()), new Text(update.getColQualifier()), viz, update.getTimestamp());
          } else {
              m.put(new Text(update.getColFamily()), new Text(update.getColQualifier()), viz, update.getTimestamp(), new Value(value));
            }
        } else {
          if (update.isSetDeleteCell()) {
            m.putDelete(new Text(update.getColFamily()), new Text(update.getColQualifier()), viz);
          } else {
            m.put(new Text(update.getColFamily()), new Text(update.getColQualifier()), viz, new Value(value));
          }
        }
      }
      try {
        bwpe.writer.addMutation(m);
      } catch (MutationsRejectedException mre) {
        bwpe.exception = mre;
      }
    }
  }
  
  @Override
  public String createWriter(ByteBuffer login, String tableName, WriterOptions opts) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      BatchWriterPlusException writer = getWriter(login, tableName, opts);
      UUID uuid = UUID.randomUUID();
      writerCache.put(uuid, writer);
      return uuid.toString();
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public void update(String writer, Map<ByteBuffer,List<ColumnUpdate>> cells) throws TException {
    try {
      BatchWriterPlusException bwpe = getWriter(writer);
      addCellsToWriter(cells, bwpe);
    } catch (UnknownWriter e) {
      // just drop it, this is a oneway thrift call and throwing a TException seems to make all subsequent thrift calls fail
    }
  }
  
  @Override
  public void flush(String writer) throws UnknownWriter, org.apache.accumulo.proxy.thrift.MutationsRejectedException, TException {
    try {
      BatchWriterPlusException bwpe = getWriter(writer);
      if (bwpe.exception != null)
        throw bwpe.exception;
      bwpe.writer.flush();
    } catch (MutationsRejectedException e) {
      throw new org.apache.accumulo.proxy.thrift.MutationsRejectedException(e.toString());
    } catch (UnknownWriter uw) {
      throw uw;
    } catch (Exception e) {
      throw new TException(e);
    }
  }
  
  @Override
  public void closeWriter(String writer) throws UnknownWriter, org.apache.accumulo.proxy.thrift.MutationsRejectedException, TException {
    try {
      BatchWriterPlusException bwpe = getWriter(writer);
      if (bwpe.exception != null)
        throw bwpe.exception;
      bwpe.writer.close();
      writerCache.invalidate(UUID.fromString(writer));
    } catch (UnknownWriter uw) {
      throw uw;
    } catch (MutationsRejectedException e) {
      throw new org.apache.accumulo.proxy.thrift.MutationsRejectedException(e.toString());
    } catch (Exception e) {
      throw new TException(e);
    }
  }

  private BatchWriterPlusException getWriter(String writer) throws UnknownWriter {
    UUID uuid = null;
    try {
      uuid = UUID.fromString(writer);
    } catch (IllegalArgumentException iae) {
      throw new UnknownWriter(iae.getMessage());
    }
    
    BatchWriterPlusException bwpe = writerCache.getIfPresent(uuid);
    if (bwpe == null) {
      throw new UnknownWriter("Writer never existed or no longer exists");
    }
    return bwpe;
  }
  
  private BatchWriterPlusException getWriter(ByteBuffer login, String tableName, WriterOptions opts) throws Exception {
    if (opts == null)
      opts = new WriterOptions();
    if (opts.getMaxMemory() == 0)
      opts.setMaxMemory(DEFAULT_MAX_MEMORY);
    if (opts.getLatencyMs() == 0)
      opts.setLatencyMs(DEFAULT_MAX_LATENCY);
    if (opts.getTimeoutMs() == 0)
      opts.setTimeoutMs(DEFAULT_TIMEOUT);
    if (opts.getThreads() == 0)
      opts.setThreads(DEFAULT_MAX_WRITE_THREADS);
    
    BatchWriterPlusException result = new BatchWriterPlusException();
    result.writer = getConnector(login).createBatchWriter(tableName, opts.getMaxMemory(), opts.getLatencyMs(), opts.getThreads());
    return result;
  }
  
  private IteratorSetting getIteratorSetting(org.apache.accumulo.proxy.thrift.IteratorSetting setting) {
    return new IteratorSetting(setting.priority, setting.name, setting.iteratorClass, setting.getProperties());
  }
  
  private IteratorScope getIteratorScope(org.apache.accumulo.proxy.thrift.IteratorScope scope) {
    return IteratorScope.valueOf(scope.toString().toLowerCase());
  }
  
  private EnumSet<IteratorScope> getIteratorScopes(Set<org.apache.accumulo.proxy.thrift.IteratorScope> scopes) {
    EnumSet<IteratorScope> scopes_ = EnumSet.noneOf(IteratorScope.class);
    for (org.apache.accumulo.proxy.thrift.IteratorScope scope : scopes) {
      scopes_.add(getIteratorScope(scope));
    }
    return scopes_;
  }
  
  private EnumSet<org.apache.accumulo.proxy.thrift.IteratorScope> getProxyIteratorScopes(Set<IteratorScope> scopes) {
    EnumSet<org.apache.accumulo.proxy.thrift.IteratorScope> scopes_ = EnumSet.noneOf(org.apache.accumulo.proxy.thrift.IteratorScope.class);
    for (IteratorScope scope : scopes) {
      scopes_.add(org.apache.accumulo.proxy.thrift.IteratorScope.valueOf(scope.toString().toUpperCase()));
    }
    return scopes_;
  }
  
  @Override
  public void attachIterator(ByteBuffer login, String tableName, org.apache.accumulo.proxy.thrift.IteratorSetting setting,
      Set<org.apache.accumulo.proxy.thrift.IteratorScope> scopes) throws org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().attachIterator(tableName, getIteratorSetting(setting), getIteratorScopes(scopes));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void checkIteratorConflicts(ByteBuffer login, String tableName, org.apache.accumulo.proxy.thrift.IteratorSetting setting,
      Set<org.apache.accumulo.proxy.thrift.IteratorScope> scopes) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().checkIteratorConflicts(tableName, getIteratorSetting(setting), getIteratorScopes(scopes));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public void cloneTable(ByteBuffer login, String tableName, String newTableName, boolean flush, Map<String,String> propertiesToSet,
      Set<String> propertiesToExclude) throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, org.apache.accumulo.proxy.thrift.TableExistsException, TException {
    try {
      propertiesToExclude = propertiesToExclude == null ? new HashSet<String>() : propertiesToExclude;
      propertiesToSet = propertiesToSet == null ? new HashMap<String,String>() : propertiesToSet;
      
      getConnector(login).tableOperations().clone(tableName, newTableName, flush, propertiesToSet, propertiesToExclude);
    } catch (Exception e) {
      handleExceptionTEE(e);
    }
  }
  
  @Override
  public org.apache.accumulo.proxy.thrift.IteratorSetting getIteratorSetting(ByteBuffer login, String tableName, String iteratorName,
      org.apache.accumulo.proxy.thrift.IteratorScope scope) throws org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      IteratorSetting is = getConnector(login).tableOperations().getIteratorSetting(tableName, iteratorName, getIteratorScope(scope));
      return new org.apache.accumulo.proxy.thrift.IteratorSetting(is.getPriority(), is.getName(), is.getIteratorClass(), is.getOptions());
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public Map<String,Set<org.apache.accumulo.proxy.thrift.IteratorScope>> listIterators(ByteBuffer login, String tableName)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Map<String,EnumSet<IteratorScope>> iterMap = getConnector(login).tableOperations().listIterators(tableName);
      Map<String,Set<org.apache.accumulo.proxy.thrift.IteratorScope>> result = new HashMap<String,Set<org.apache.accumulo.proxy.thrift.IteratorScope>>();
      for (Map.Entry<String,EnumSet<IteratorScope>> entry : iterMap.entrySet()) {
        result.put(entry.getKey(), getProxyIteratorScopes(entry.getValue()));
      }
      return result;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  @Override
  public void removeIterator(ByteBuffer login, String tableName, String iterName, Set<org.apache.accumulo.proxy.thrift.IteratorScope> scopes)
      throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      getConnector(login).tableOperations().removeIterator(tableName, iterName, getIteratorScopes(scopes));
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public Set<org.apache.accumulo.proxy.thrift.Range> splitRangeByTablets(ByteBuffer login, String tableName, org.apache.accumulo.proxy.thrift.Range range,
      int maxSplits) throws org.apache.accumulo.proxy.thrift.AccumuloException, org.apache.accumulo.proxy.thrift.AccumuloSecurityException,
      org.apache.accumulo.proxy.thrift.TableNotFoundException, TException {
    try {
      Set<Range> ranges = getConnector(login).tableOperations().splitRangeByTablets(tableName, getRange(range), maxSplits);
      Set<org.apache.accumulo.proxy.thrift.Range> result = new HashSet<org.apache.accumulo.proxy.thrift.Range>();
      for (Range r : ranges) {
        result.add(getRange(r));
      }
      return result;
    } catch (Exception e) {
      handleExceptionTNF(e);
      return null;
    }
  }
  
  private org.apache.accumulo.proxy.thrift.Range getRange(Range r) {
    return new org.apache.accumulo.proxy.thrift.Range(getProxyKey(r.getStartKey()), r.isStartKeyInclusive(), getProxyKey(r.getEndKey()), r.isEndKeyInclusive());
  }
  
  private org.apache.accumulo.proxy.thrift.Key getProxyKey(Key k) {
    if (k == null)
      return null;
    org.apache.accumulo.proxy.thrift.Key result = new org.apache.accumulo.proxy.thrift.Key(TextUtil.getByteBuffer(k.getRow()), TextUtil.getByteBuffer(k
        .getColumnFamily()), TextUtil.getByteBuffer(k.getColumnQualifier()), TextUtil.getByteBuffer(k.getColumnVisibility()));
    result.setTimestamp(k.getTimestamp());
    return result;
  }
  
  private Range getRange(org.apache.accumulo.proxy.thrift.Range range) {
    return new Range(Util.fromThrift(range.start), Util.fromThrift(range.stop));
  }
  
  @Override
  public void importDirectory(ByteBuffer login, String tableName, String importDir, String failureDir, boolean setTime)
      throws org.apache.accumulo.proxy.thrift.TableNotFoundException, org.apache.accumulo.proxy.thrift.AccumuloException,
      org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      getConnector(login).tableOperations().importDirectory(tableName, importDir, failureDir, setTime);
    } catch (Exception e) {
      handleExceptionTNF(e);
    }
  }
  
  @Override
  public org.apache.accumulo.proxy.thrift.Range getRowRange(ByteBuffer row) throws TException {
    return getRange(new Range(ByteBufferUtil.toText(row)));
  }
  
  @Override
  public org.apache.accumulo.proxy.thrift.Key getFollowing(org.apache.accumulo.proxy.thrift.Key key, org.apache.accumulo.proxy.thrift.PartialKey part)
      throws TException {
    Key key_ = Util.fromThrift(key);
    PartialKey part_ = PartialKey.valueOf(part.toString());
    Key followingKey = key_.followingKey(part_);
    return getProxyKey(followingKey);
  }
  
  @Override
  public ByteBuffer login(String principal, Map<String,String> loginProperties) throws org.apache.accumulo.proxy.thrift.AccumuloSecurityException, TException {
    try {
      
      // We don't have an Authenticator on the instance in 1.4- pulling "password" prop manually
      String token = loginProperties.get(PASSWORD_PROP);
      instance.getConnector(principal, token);
      
      return Util.encodeUserPrincipal(principal, token, instance.getInstanceID());
    } catch (Exception e) {
      throw new org.apache.accumulo.proxy.thrift.AccumuloSecurityException(e.toString());
    }
  }
  
  private void checkTableExists(Connector connector, String tableName) throws TableNotFoundException {
    
    if (!connector.tableOperations().exists(tableName)) {
      throw new TableNotFoundException(null, tableName, "Not found");
    }
  }
}
