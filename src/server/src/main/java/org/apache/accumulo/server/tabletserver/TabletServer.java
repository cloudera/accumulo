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

import static org.apache.accumulo.server.problems.ProblemType.TABLET_LOAD;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.accumulo.cloudtrace.instrument.Span;
import org.apache.accumulo.cloudtrace.instrument.Trace;
import org.apache.accumulo.cloudtrace.instrument.thrift.TraceWrap;
import org.apache.accumulo.cloudtrace.thrift.TInfo;
import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.impl.TabletType;
import org.apache.accumulo.core.client.impl.Translator;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.constraints.Constraint.Environment;
import org.apache.accumulo.core.constraints.Violations;
import org.apache.accumulo.core.data.Column;
import org.apache.accumulo.core.data.ConstraintViolationSummary;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.thrift.InitialMultiScan;
import org.apache.accumulo.core.data.thrift.InitialScan;
import org.apache.accumulo.core.data.thrift.IterInfo;
import org.apache.accumulo.core.data.thrift.MapFileInfo;
import org.apache.accumulo.core.data.thrift.MultiScanResult;
import org.apache.accumulo.core.data.thrift.ScanResult;
import org.apache.accumulo.core.data.thrift.TColumn;
import org.apache.accumulo.core.data.thrift.TKey;
import org.apache.accumulo.core.data.thrift.TKeyExtent;
import org.apache.accumulo.core.data.thrift.TKeyValue;
import org.apache.accumulo.core.data.thrift.TMutation;
import org.apache.accumulo.core.data.thrift.TRange;
import org.apache.accumulo.core.data.thrift.UpdateErrors;
import org.apache.accumulo.core.file.FileUtil;
import org.apache.accumulo.core.iterators.IterationInterruptedException;
import org.apache.accumulo.core.master.MasterNotRunningException;
import org.apache.accumulo.core.master.thrift.Compacting;
import org.apache.accumulo.core.master.thrift.MasterClientService;
import org.apache.accumulo.core.master.thrift.TableInfo;
import org.apache.accumulo.core.master.thrift.TabletLoadState;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.security.thrift.AuthInfo;
import org.apache.accumulo.core.security.thrift.SecurityErrorCode;
import org.apache.accumulo.core.security.thrift.ThriftSecurityException;
import org.apache.accumulo.core.tabletserver.thrift.ActiveScan;
import org.apache.accumulo.core.tabletserver.thrift.ConstraintViolationException;
import org.apache.accumulo.core.tabletserver.thrift.NoSuchScanIDException;
import org.apache.accumulo.core.tabletserver.thrift.NotServingTabletException;
import org.apache.accumulo.core.tabletserver.thrift.ScanState;
import org.apache.accumulo.core.tabletserver.thrift.ScanType;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.core.tabletserver.thrift.TabletStats;
import org.apache.accumulo.core.util.AddressUtil;
import org.apache.accumulo.core.util.ByteBufferUtil;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.ColumnFQ;
import org.apache.accumulo.core.util.Daemon;
import org.apache.accumulo.core.util.LoggingRunnable;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.core.util.Stat;
import org.apache.accumulo.core.util.ThriftUtil;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.core.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.server.Accumulo;
import org.apache.accumulo.server.ServerConstants;
import org.apache.accumulo.server.client.ClientServiceHandler;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.master.state.Assignment;
import org.apache.accumulo.server.master.state.DistributedStoreException;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.accumulo.server.master.state.TabletLocationState;
import org.apache.accumulo.server.master.state.TabletStateStore;
import org.apache.accumulo.server.master.state.ZooTabletStateStore;
import org.apache.accumulo.server.metrics.AbstractMetricsImpl;
import org.apache.accumulo.server.problems.ProblemReport;
import org.apache.accumulo.server.problems.ProblemReports;
import org.apache.accumulo.server.security.Authenticator;
import org.apache.accumulo.server.security.SecurityConstants;
import org.apache.accumulo.server.security.SecurityUtil;
import org.apache.accumulo.server.security.ZKAuthenticator;
import org.apache.accumulo.server.tabletserver.Tablet.CommitSession;
import org.apache.accumulo.server.tabletserver.Tablet.KVEntry;
import org.apache.accumulo.server.tabletserver.Tablet.LookupResult;
import org.apache.accumulo.server.tabletserver.Tablet.MajorCompactionReason;
import org.apache.accumulo.server.tabletserver.Tablet.ScanBatch;
import org.apache.accumulo.server.tabletserver.Tablet.Scanner;
import org.apache.accumulo.server.tabletserver.Tablet.SplitInfo;
import org.apache.accumulo.server.tabletserver.Tablet.TConstraintViolationException;
import org.apache.accumulo.server.tabletserver.Tablet.TabletClosedException;
import org.apache.accumulo.server.tabletserver.TabletServerResourceManager.TabletResourceManager;
import org.apache.accumulo.server.tabletserver.TabletStatsKeeper.Operation;
import org.apache.accumulo.server.tabletserver.log.LoggerStrategy;
import org.apache.accumulo.server.tabletserver.log.MutationReceiver;
import org.apache.accumulo.server.tabletserver.log.RemoteLogger;
import org.apache.accumulo.server.tabletserver.log.RoundRobinLoggerStrategy;
import org.apache.accumulo.server.tabletserver.log.TabletServerLogger;
import org.apache.accumulo.server.tabletserver.mastermessage.MasterMessage;
import org.apache.accumulo.server.tabletserver.mastermessage.SplitReportMessage;
import org.apache.accumulo.server.tabletserver.mastermessage.TabletStatusMessage;
import org.apache.accumulo.server.tabletserver.metrics.TabletServerMBean;
import org.apache.accumulo.server.tabletserver.metrics.TabletServerMinCMetrics;
import org.apache.accumulo.server.tabletserver.metrics.TabletServerScanMetrics;
import org.apache.accumulo.server.tabletserver.metrics.TabletServerUpdateMetrics;
import org.apache.accumulo.server.trace.TraceFileSystem;
import org.apache.accumulo.server.util.FileSystemMonitor;
import org.apache.accumulo.server.util.Halt;
import org.apache.accumulo.server.util.MapCounter;
import org.apache.accumulo.server.util.MetadataTable;
import org.apache.accumulo.server.util.MetadataTable.LogEntry;
import org.apache.accumulo.server.util.NamingThreadFactory;
import org.apache.accumulo.server.util.TServerUtils;
import org.apache.accumulo.server.util.TServerUtils.ServerPort;
import org.apache.accumulo.server.util.time.RelativeTime;
import org.apache.accumulo.server.util.time.SimpleTimer;
import org.apache.accumulo.server.zookeeper.DistributedWorkQueue;
import org.apache.accumulo.server.zookeeper.IZooReaderWriter;
import org.apache.accumulo.server.zookeeper.TransactionWatcher;
import org.apache.accumulo.server.zookeeper.ZooCache;
import org.apache.accumulo.server.zookeeper.ZooLock;
import org.apache.accumulo.server.zookeeper.ZooLock.LockLossReason;
import org.apache.accumulo.server.zookeeper.ZooLock.LockWatcher;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.accumulo.start.Platform;
import org.apache.accumulo.start.classloader.AccumuloClassLoader;
import org.apache.commons.collections.map.LRUMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.server.TServer;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;

enum ScanRunState {
  QUEUED, RUNNING, FINISHED
}

public class TabletServer extends AbstractMetricsImpl implements org.apache.accumulo.server.tabletserver.metrics.TabletServerMBean {
  private static final Logger log = Logger.getLogger(TabletServer.class);
  
  private static HashMap<String,Long> prevGcTime = new HashMap<String,Long>();
  private static long lastMemorySize = 0;
  private static long gcTimeIncreasedCount;
  private static AtomicLong scanCount = new AtomicLong();
  private static final Class<? extends LoggerStrategy> DEFAULT_LOGGER_STRATEGY = RoundRobinLoggerStrategy.class;
  
  private static final long MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS = 1000;
  
  private TabletServerLogger logger;
  private LoggerStrategy loggerStrategy;
  
  protected TabletServerMinCMetrics mincMetrics = new TabletServerMinCMetrics();
  
  public TabletServer() {
    super();
    watcher = new TransactionWatcher();

    SimpleTimer.getInstance().schedule(new TimerTask() {
      @Override
      public void run() {
        synchronized (onlineTablets) {
          long now = System.currentTimeMillis();
          for (Tablet tablet : onlineTablets.values())
            try {
              tablet.updateRates(now);
            } catch (Exception ex) {
              log.error(ex, ex);
            }
        }
      }
    }, 5000, 5000);
  }
  
  private synchronized static void logGCInfo() {
    List<GarbageCollectorMXBean> gcmBeans = ManagementFactory.getGarbageCollectorMXBeans();
    Runtime rt = Runtime.getRuntime();
    
    StringBuilder sb = new StringBuilder("gc");
    
    boolean sawChange = false;
    
    long maxIncreaseInCollectionTime = 0;
    
    for (GarbageCollectorMXBean gcBean : gcmBeans) {
      Long prevTime = prevGcTime.get(gcBean.getName());
      long pt = 0;
      if (prevTime != null) {
        pt = prevTime;
      }
      
      long time = gcBean.getCollectionTime();
      
      if (time - pt != 0) {
        sawChange = true;
      }
      
      long increaseInCollectionTime = time - pt;
      sb.append(String.format(" %s=%,.2f(+%,.2f) secs", gcBean.getName(), time / 1000.0, increaseInCollectionTime / 1000.0));
      maxIncreaseInCollectionTime = Math.max(increaseInCollectionTime, maxIncreaseInCollectionTime);
      prevGcTime.put(gcBean.getName(), time);
    }
    
    long mem = rt.freeMemory();
    if (maxIncreaseInCollectionTime == 0) {
      gcTimeIncreasedCount = 0;
    } else {
      gcTimeIncreasedCount++;
      if (gcTimeIncreasedCount > 3 && mem < rt.maxMemory() * 0.05) {
        log.warn("Running low on memory");
        gcTimeIncreasedCount = 0;
      }
    }
    
    if (mem > lastMemorySize) {
      sawChange = true;
    }
    
    String sign = "+";
    if (mem - lastMemorySize <= 0) {
      sign = "";
    }
    
    sb.append(String.format(" freemem=%,d(%s%,d) totalmem=%,d", mem, sign, (mem - lastMemorySize), rt.totalMemory()));
    
    if (sawChange) {
      log.debug(sb.toString());
    }
    
    final long keepAliveTimeout = ServerConfiguration.getSystemConfiguration().getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT);
    if (maxIncreaseInCollectionTime > keepAliveTimeout) {
      Halt.halt("Garbage collection may be interfering with lock keep-alive.  Halting.", -1);
    }
    
    lastMemorySize = mem;
  }
  
  private TabletStatsKeeper statsKeeper;
  
  private static class Session {
    long lastAccessTime;
    long startTime;
    String user;
    String client = TServerUtils.clientAddress.get();
    public boolean reserved;
    
    public void cleanup() {}
  }
  
  private static class SessionManager {
    
    SecureRandom random;
    Map<Long,Session> sessions;
    
    SessionManager() {
      random = new SecureRandom();
      sessions = new HashMap<Long,Session>();
      
      final long maxIdle = ServerConfiguration.getSystemConfiguration().getTimeInMillis(Property.TSERV_SESSION_MAXIDLE);
      
      TimerTask r = new TimerTask() {
        public void run() {
          sweep(maxIdle);
        }
      };
      
      SimpleTimer.getInstance().schedule(r, 0, Math.max(maxIdle / 2, 1000));
    }
    
    synchronized long createSession(Session session, boolean reserve) {
      long sid = random.nextLong();
      
      while (sessions.containsKey(sid)) {
        sid = random.nextLong();
      }
      
      sessions.put(sid, session);
      
      session.reserved = reserve;
      
      session.startTime = session.lastAccessTime = System.currentTimeMillis();
      
      return sid;
    }
    
    /**
     * while a session is reserved, it cannot be canceled or removed
     * 
     * @param sessionId
     */
    
    synchronized Session reserveSession(long sessionId) {
      Session session = sessions.get(sessionId);
      if (session != null) {
        if (session.reserved)
          throw new IllegalStateException();
        session.reserved = true;
      }
      
      return session;
      
    }
    
    synchronized void unreserveSession(Session session) {
      if (!session.reserved)
        throw new IllegalStateException();
      session.reserved = false;
      session.lastAccessTime = System.currentTimeMillis();
    }
    
    synchronized void unreserveSession(long sessionId) {
      Session session = getSession(sessionId);
      if (session != null)
        unreserveSession(session);
    }
    
    synchronized Session getSession(long sessionId) {
      Session session = sessions.get(sessionId);
      if (session != null)
        session.lastAccessTime = System.currentTimeMillis();
      return session;
    }
    
    Session removeSession(long sessionId) {
      Session session = null;
      synchronized (this) {
        session = sessions.remove(sessionId);
      }
      
      // do clean up out side of lock..
      if (session != null)
        session.cleanup();
      
      return session;
    }
    
    private void sweep(long maxIdle) {
      ArrayList<Session> sessionsToCleanup = new ArrayList<Session>();
      synchronized (this) {
        Iterator<Session> iter = sessions.values().iterator();
        while (iter.hasNext()) {
          Session session = iter.next();
          long idleTime = System.currentTimeMillis() - session.lastAccessTime;
          if (idleTime > maxIdle && !session.reserved) {
            iter.remove();
            sessionsToCleanup.add(session);
          }
        }
      }
      
      // do clean up outside of lock
      for (Session session : sessionsToCleanup) {
        session.cleanup();
      }
    }
    
    synchronized void removeIfNotAccessed(final long sessionId, long delay) {
      Session session = sessions.get(sessionId);
      if (session != null) {
        final long removeTime = session.lastAccessTime;
        TimerTask r = new TimerTask() {
          public void run() {
            Session sessionToCleanup = null;
            synchronized (SessionManager.this) {
              Session session2 = sessions.get(sessionId);
              if (session2 != null && session2.lastAccessTime == removeTime && !session2.reserved) {
                sessions.remove(sessionId);
                sessionToCleanup = session2;
              }
            }
            
            // call clean up outside of lock
            if (sessionToCleanup != null)
              sessionToCleanup.cleanup();
          }
        };
        
        SimpleTimer.getInstance().schedule(r, delay);
      }
    }
    
    public synchronized Map<String,MapCounter<ScanRunState>> getActiveScansPerTable() {
      Map<String,MapCounter<ScanRunState>> counts = new HashMap<String,MapCounter<ScanRunState>>();
      for (Entry<Long,Session> entry : sessions.entrySet()) {
        
        Session session = entry.getValue();
        @SuppressWarnings("rawtypes")
        ScanTask nbt = null;
        String tableID = null;
        
        if (session instanceof ScanSession) {
          ScanSession ss = (ScanSession) session;
          nbt = ss.nextBatchTask;
          tableID = ss.extent.getTableId().toString();
        } else if (session instanceof MultiScanSession) {
          MultiScanSession mss = (MultiScanSession) session;
          nbt = mss.lookupTask;
          tableID = mss.threadPoolExtent.getTableId().toString();
        }
        
        if (nbt == null)
          continue;
        
        ScanRunState srs = nbt.getScanRunState();
        
        if (nbt == null || srs == ScanRunState.FINISHED)
          continue;
        
        MapCounter<ScanRunState> stateCounts = counts.get(tableID);
        if (stateCounts == null) {
          stateCounts = new MapCounter<ScanRunState>();
          counts.put(tableID, stateCounts);
        }
        
        stateCounts.increment(srs, 1);
      }
      
      return counts;
    }
    
    public synchronized List<ActiveScan> getActiveScans() {
      
      ArrayList<ActiveScan> activeScans = new ArrayList<ActiveScan>();
      
      long ct = System.currentTimeMillis();
      
      for (Entry<Long,Session> entry : sessions.entrySet()) {
        Session session = entry.getValue();
        if (session instanceof ScanSession) {
          ScanSession ss = (ScanSession) session;
          
          ScanState state = ScanState.RUNNING;
          
          ScanTask<ScanBatch> nbt = ss.nextBatchTask;
          if (nbt == null) {
            state = ScanState.IDLE;
          } else {
            switch (nbt.getScanRunState()) {
              case QUEUED:
                state = ScanState.QUEUED;
                break;
              case FINISHED:
                state = ScanState.IDLE;
                break;
            }
          }
          
          activeScans.add(new ActiveScan(ss.client, ss.user, ss.extent.getTableId().toString(), ct - ss.startTime, ct - ss.lastAccessTime, ScanType.SINGLE,
              state, ss.extent.toThrift(), Translator.translate(ss.columnSet, Translator.CT), ss.ssiList, ss.ssio));
          
        } else if (session instanceof MultiScanSession) {
          MultiScanSession mss = (MultiScanSession) session;
          
          ScanState state = ScanState.RUNNING;
          
          ScanTask<MultiScanResult> nbt = mss.lookupTask;
          if (nbt == null) {
            state = ScanState.IDLE;
          } else {
            switch (nbt.getScanRunState()) {
              case QUEUED:
                state = ScanState.QUEUED;
                break;
              case FINISHED:
                state = ScanState.IDLE;
                break;
            }
          }
          
          activeScans.add(new ActiveScan(mss.client, mss.user, mss.threadPoolExtent.getTableId().toString(), ct - mss.startTime, ct - mss.lastAccessTime,
              ScanType.BATCH, state, mss.threadPoolExtent.toThrift(), Translator.translate(mss.columnSet, Translator.CT), mss.ssiList, mss.ssio));
        }
      }
      
      return activeScans;
    }
  }
  
  static class TservConstraintEnv implements Environment {
    
    private AuthInfo credentials;
    private Authenticator authenticator;
    private Authorizations auths;
    private KeyExtent ke;
    
    TservConstraintEnv(Authenticator authenticator, AuthInfo credentials) {
      this.authenticator = authenticator;
      this.credentials = credentials;
    }
    
    void setExtent(KeyExtent ke) {
      this.ke = ke;
    }
    
    @Override
    public KeyExtent getExtent() {
      return ke;
    }
    
    @Override
    public String getUser() {
      return credentials.user;
    }
    
    @Override
    public Authorizations getAuthorizations() {
      if (auths == null)
        try {
          this.auths = authenticator.getUserAuthorizations(credentials, getUser());
        } catch (AccumuloSecurityException e) {
          throw new RuntimeException(e);
        }
      return auths;
    }
    
  }
  
  private abstract class ScanTask<T> implements RunnableFuture<T> {
    
    protected AtomicBoolean interruptFlag;
    protected ArrayBlockingQueue<Object> resultQueue;
    protected AtomicInteger state;
    protected AtomicReference<ScanRunState> runState;
    
    private static final int INITIAL = 1;
    private static final int ADDED = 2;
    private static final int CANCELED = 3;
    
    ScanTask() {
      interruptFlag = new AtomicBoolean(false);
      runState = new AtomicReference<ScanRunState>(ScanRunState.QUEUED);
      state = new AtomicInteger(INITIAL);
      resultQueue = new ArrayBlockingQueue<Object>(1);
    }
    
    protected void addResult(Object o) {
      if (state.compareAndSet(INITIAL, ADDED))
        resultQueue.add(o);
      else if (state.get() == ADDED)
        throw new IllegalStateException("Tried to add more than one result");
    }
    
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (!mayInterruptIfRunning)
        throw new IllegalArgumentException("Cancel will always attempt to interupt running next batch task");
      
      if (state.get() == CANCELED)
        return true;
      
      if (state.compareAndSet(INITIAL, CANCELED)) {
        interruptFlag.set(true);
        resultQueue = null;
        return true;
      }
      
      return false;
    }
    
    @Override
    public T get() throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      
      ArrayBlockingQueue<Object> localRQ = resultQueue;
      
      if (state.get() == CANCELED)
        throw new CancellationException();
      
      if (localRQ == null && state.get() == ADDED)
        throw new IllegalStateException("Tried to get result twice");
      
      Object r = localRQ.poll(timeout, unit);
      
      // could have been canceled while waiting
      if (state.get() == CANCELED) {
        if (r != null)
          throw new IllegalStateException("Nothing should have been added when in canceled state");
        
        throw new CancellationException();
      }
      
      if (r == null)
        throw new TimeoutException();
      
      // make this method stop working now that something is being
      // returned
      resultQueue = null;
      
      if (r instanceof Throwable)
        throw new ExecutionException((Throwable) r);
      
      return (T) r;
    }
    
    @Override
    public boolean isCancelled() {
      return state.get() == CANCELED;
    }
    
    @Override
    public boolean isDone() {
      return runState.get().equals(ScanRunState.FINISHED);
    }
    
    public ScanRunState getScanRunState() {
      return runState.get();
    }
    
  }
  
  private static class UpdateSession extends Session {
    public Tablet currentTablet;
    public MapCounter<Tablet> successfulCommits = new MapCounter<Tablet>();
    Map<KeyExtent,Long> failures = new HashMap<KeyExtent,Long>();
    HashSet<KeyExtent> authFailures = new HashSet<KeyExtent>();
    public Violations violations;
    public AuthInfo credentials;
    public long totalUpdates = 0;
    public long flushTime = 0;
    Stat prepareTimes = new Stat();
    Stat walogTimes = new Stat();
    Stat commitTimes = new Stat();
    Stat authTimes = new Stat();
    public Map<Tablet,List<Mutation>> queuedMutations = new HashMap<Tablet,List<Mutation>>();
    public long queuedMutationSize = 0;
    TservConstraintEnv cenv = null;
  }
  
  private static class ScanSession extends Session {
    public KeyExtent extent;
    public HashSet<Column> columnSet;
    public List<IterInfo> ssiList;
    public Map<String,Map<String,String>> ssio;
    public long entriesReturned = 0;
    public Stat nbTimes = new Stat();
    public long batchCount = 0;
    public volatile ScanTask<ScanBatch> nextBatchTask;
    public AtomicBoolean interruptFlag;
    public Scanner scanner;
    
    @Override
    public void cleanup() {
      try {
        if (nextBatchTask != null)
          nextBatchTask.cancel(true);
      } finally {
        if (scanner != null)
          scanner.close();
      }
    }
    
  }
  
  private static class MultiScanSession extends Session {
    HashSet<Column> columnSet;
    Map<KeyExtent,List<Range>> queries;
    public List<IterInfo> ssiList;
    public Map<String,Map<String,String>> ssio;
    public Authorizations auths;
    
    // stats
    int numRanges;
    int numTablets;
    int numEntries;
    long totalLookupTime;
    
    public volatile ScanTask<MultiScanResult> lookupTask;
    public KeyExtent threadPoolExtent;
    
    @Override
    public void cleanup() {
      if (lookupTask != null)
        lookupTask.cancel(true);
    }
  }
  
  /**
   * This little class keeps track of writes in progress and allows readers to wait for writes that started before the read. It assumes that the operation ids
   * are monotonically increasing.
   * 
   */
  static class WriteTracker {
    private static AtomicLong operationCounter = new AtomicLong(1);
    private Map<TabletType,TreeSet<Long>> inProgressWrites = new EnumMap<TabletType,TreeSet<Long>>(TabletType.class);
    
    WriteTracker() {
      for (TabletType ttype : TabletType.values()) {
        inProgressWrites.put(ttype, new TreeSet<Long>());
      }
    }
    
    synchronized long startWrite(TabletType ttype) {
      long operationId = operationCounter.getAndIncrement();
      inProgressWrites.get(ttype).add(operationId);
      return operationId;
    }
    
    synchronized void finishWrite(long operationId) {
      if (operationId == -1)
        return;
      
      boolean removed = false;
      
      for (TabletType ttype : TabletType.values()) {
        removed = inProgressWrites.get(ttype).remove(operationId);
        if (removed)
          break;
      }
      
      if (!removed) {
        throw new IllegalArgumentException("Attempted to finish write not in progress,  operationId " + operationId);
      }
      
      this.notifyAll();
    }
    
    synchronized void waitForWrites(TabletType ttype) {
      long operationId = operationCounter.getAndIncrement();
      while (inProgressWrites.get(ttype).floor(operationId) != null) {
        try {
          this.wait();
        } catch (InterruptedException e) {
          log.error(e, e);
        }
      }
    }
    
    public long startWrite(Set<Tablet> keySet) {
      if (keySet.size() == 0)
        return -1;
      
      ArrayList<KeyExtent> extents = new ArrayList<KeyExtent>(keySet.size());
      
      for (Tablet tablet : keySet)
        extents.add(tablet.getExtent());
      
      return startWrite(TabletType.type(extents));
    }
  }
  
  TransactionWatcher watcher;
  
  private class ThriftClientHandler extends ClientServiceHandler implements TabletClientService.Iface {
    
    SessionManager sessionManager;
    
    AccumuloConfiguration acuConf = ServerConfiguration.getSystemConfiguration();
    
    TabletServerUpdateMetrics updateMetrics = new TabletServerUpdateMetrics();
    
    TabletServerScanMetrics scanMetrics = new TabletServerScanMetrics();
    
    WriteTracker writeTracker = new WriteTracker();
    
    ThriftClientHandler() {
      super(watcher);
      log.debug(ThriftClientHandler.class.getName() + " created");
      sessionManager = new SessionManager();
      // Register the metrics MBean
      try {
        updateMetrics.register();
        scanMetrics.register();
      } catch (Exception e) {
        log.error("Exception registering MBean with MBean Server", e);
      }
    }
    
    @Override
    public List<TKeyExtent> bulkImport(TInfo tinfo, AuthInfo credentials, long tid, Map<TKeyExtent,Map<String,MapFileInfo>> files, boolean setTime)
        throws ThriftSecurityException {
      
      try {
        if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      List<TKeyExtent> failures = new ArrayList<TKeyExtent>();
      
      for (Entry<TKeyExtent,Map<String,MapFileInfo>> entry : files.entrySet()) {
        TKeyExtent tke = entry.getKey();
        Map<String,MapFileInfo> fileMap = entry.getValue();
        
        Tablet importTablet = onlineTablets.get(new KeyExtent(tke));
        
        if (importTablet == null) {
          failures.add(tke);
        } else {
          try {
            importTablet.importMapFiles(tid, fileMap, setTime);
          } catch (IOException ioe) {
            log.info("files " + fileMap.keySet() + " not imported to " + new KeyExtent(tke) + ": " + ioe.getMessage());
            failures.add(tke);
          }
        }
      }
      return failures;
    }
    
    private class NextBatchTask extends ScanTask<ScanBatch> {
      
      private long scanID;
      
      NextBatchTask(long scanID, AtomicBoolean interruptFlag) {
        this.scanID = scanID;
        this.interruptFlag = interruptFlag;
        
        if (interruptFlag.get())
          cancel(true);
      }
      
      @Override
      public void run() {
        
        ScanSession scanSession = (ScanSession) sessionManager.getSession(scanID);
        String oldThreadName = Thread.currentThread().getName();

        try {
          if (isCancelled() || scanSession == null)
            return;
          
          Thread.currentThread().setName(
              "User: " + scanSession.user + " Start: " + scanSession.startTime + " Client: " + scanSession.client + " Tablet: " + scanSession.extent);

          runState.set(ScanRunState.RUNNING);
          
          Tablet tablet = onlineTablets.get(scanSession.extent);
          
          if (tablet == null) {
            addResult(new org.apache.accumulo.core.tabletserver.thrift.NotServingTabletException(scanSession.extent.toThrift()));
            return;
          }
          
          long t1 = System.currentTimeMillis();
          ScanBatch batch = scanSession.scanner.read();
          long t2 = System.currentTimeMillis();
          scanSession.nbTimes.addStat(t2 - t1);
          
          // there should only be one thing on the queue at a time, so
          // it should be ok to call add()
          // instead of put()... if add() fails because queue is at
          // capacity it means there is code
          // problem somewhere
          addResult(batch);
        } catch (TabletClosedException e) {
          addResult(new org.apache.accumulo.core.tabletserver.thrift.NotServingTabletException(scanSession.extent.toThrift()));
        } catch (IterationInterruptedException iie) {
          if (!isCancelled()) {
            log.warn("Iteration interrupted, when scan not cancelled", iie);
            addResult(iie);
          }
        } catch (TooManyFilesException tmfe) {
          addResult(tmfe);
        } catch (Throwable e) {
          log.warn("exception while scanning tablet " + (scanSession == null ? "(unknown)" : scanSession.extent), e);
          addResult(e);
        } finally {
          runState.set(ScanRunState.FINISHED);
          Thread.currentThread().setName(oldThreadName);
        }
        
      }
    }
    
    private class LookupTask extends ScanTask<MultiScanResult> {
      
      private long scanID;
      
      LookupTask(long scanID) {
        this.scanID = scanID;
      }
      
      @Override
      public void run() {
        MultiScanSession session = (MultiScanSession) sessionManager.getSession(scanID);
        String oldThreadName = Thread.currentThread().getName();
        
        try {
          if (isCancelled() || session == null)
            return;

          runState.set(ScanRunState.RUNNING);
          Thread.currentThread().setName("Client: " + session.client + " User: " + session.user + " Start: " + session.startTime + " Table: ");

          long maxResultsSize = acuConf.getMemoryInBytes(Property.TABLE_SCAN_MAXMEM);
          long bytesAdded = 0;
          long maxScanTime = 4000;
          
          long startTime = System.currentTimeMillis();
          
          ArrayList<KVEntry> results = new ArrayList<KVEntry>();
          Map<KeyExtent,List<Range>> failures = new HashMap<KeyExtent,List<Range>>();
          ArrayList<KeyExtent> fullScans = new ArrayList<KeyExtent>();
          KeyExtent partScan = null;
          Key partNextKey = null;
          boolean partNextKeyInclusive = false;
          
          Iterator<Entry<KeyExtent,List<Range>>> iter = session.queries.entrySet().iterator();
          
          // check the time so that the read ahead thread is not monopolized
          while (iter.hasNext() && bytesAdded < maxResultsSize && (System.currentTimeMillis() - startTime) < maxScanTime) {
            Entry<KeyExtent,List<Range>> entry = iter.next();
            
            iter.remove();
            
            // check that tablet server is serving requested tablet
            Tablet tablet = onlineTablets.get(entry.getKey());
            if (tablet == null) {
              failures.put(entry.getKey(), entry.getValue());
              continue;
            }
            Thread.currentThread().setName(
                "Client: " + session.client + " User: " + session.user + " Start: " + session.startTime + " Tablet: " + entry.getKey().toString());
            
            LookupResult lookupResult;
            try {
              
              // do the following check to avoid a race condition
              // between setting false below and the task being
              // canceled
              if (isCancelled())
                interruptFlag.set(true);
              
              lookupResult = tablet.lookup(entry.getValue(), session.columnSet, session.auths, results, maxResultsSize - bytesAdded, session.ssiList,
                  session.ssio, interruptFlag);
              
              // if the tablet was closed it it possible that the
              // interrupt flag was set.... do not want it set for
              // the next
              // lookup
              interruptFlag.set(false);
              
            } catch (IOException e) {
              log.warn("lookup failed for tablet " + entry.getKey(), e);
              throw new RuntimeException(e);
            }
            
            bytesAdded += lookupResult.bytesAdded;
            
            if (lookupResult.unfinishedRanges.size() > 0) {
              if (lookupResult.closed) {
                failures.put(entry.getKey(), lookupResult.unfinishedRanges);
              } else {
                session.queries.put(entry.getKey(), lookupResult.unfinishedRanges);
                partScan = entry.getKey();
                partNextKey = lookupResult.unfinishedRanges.get(0).getStartKey();
                partNextKeyInclusive = lookupResult.unfinishedRanges.get(0).isStartKeyInclusive();
              }
            } else {
              fullScans.add(entry.getKey());
            }
          }
          
          long finishTime = System.currentTimeMillis();
          session.totalLookupTime += (finishTime - startTime);
          session.numEntries += results.size();
          
          // convert everything to thrift before adding result
          List<TKeyValue> retResults = new ArrayList<TKeyValue>();
          for (KVEntry entry : results)
            retResults.add(new TKeyValue(entry.key.toThrift(), ByteBuffer.wrap(entry.value)));
          Map<TKeyExtent,List<TRange>> retFailures = Translator.translate(failures, Translator.KET, new Translator.ListTranslator<Range,TRange>(Translator.RT));
          List<TKeyExtent> retFullScans = Translator.translate(fullScans, Translator.KET);
          TKeyExtent retPartScan = null;
          TKey retPartNextKey = null;
          if (partScan != null) {
            retPartScan = partScan.toThrift();
            retPartNextKey = partNextKey.toThrift();
          }
          // add results to queue
          addResult(new MultiScanResult(retResults, retFailures, retFullScans, retPartScan, retPartNextKey, partNextKeyInclusive, session.queries.size() != 0));
        } catch (IterationInterruptedException iie) {
          if (!isCancelled()) {
            log.warn("Iteration interrupted, when scan not cancelled", iie);
            addResult(iie);
          }
        } catch (Throwable e) {
          log.warn("exception while doing multi-scan ", e);
          addResult(e);
        } finally {
          Thread.currentThread().setName(oldThreadName);
          runState.set(ScanRunState.FINISHED);
        }
      }
    }
    
    @Override
    public InitialScan startScan(TInfo tinfo, AuthInfo credentials, TKeyExtent textent, TRange range, List<TColumn> columns, int batchSize,
        List<IterInfo> ssiList, Map<String,Map<String,String>> ssio, List<ByteBuffer> authorizations, boolean waitForWrites, boolean isolated)
        throws NotServingTabletException, ThriftSecurityException, org.apache.accumulo.core.tabletserver.thrift.TooManyFilesException {
      
      Authorizations userauths = null;
      
      try {
        if (!authenticator.hasTablePermission(credentials, credentials.user, new String(textent.getTable()), TablePermission.READ))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
        
        userauths = authenticator.getUserAuthorizations(credentials, credentials.user);
        for (ByteBuffer auth : authorizations)
          if (!userauths.contains(ByteBufferUtil.toBytes(auth)))
            throw new ThriftSecurityException(credentials.user, SecurityErrorCode.BAD_AUTHORIZATIONS);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      scanCount.addAndGet(1);
      
      KeyExtent extent = new KeyExtent(textent);
      
      // wait for any writes that are in flight.. this done to ensure
      // consistency across client restarts... assume a client writes
      // to accumulo and dies while waiting for a confirmation from
      // accumulo... the client process restarts and tries to read
      // data from accumulo making the assumption that it will get
      // any writes previously made... however if the server side thread
      // processing the write from the dead client is still in progress,
      // the restarted client may not see the write unless we wait here.
      // this behavior is very important when the client is reading the
      // !METADATA table
      if (waitForWrites)
        writeTracker.waitForWrites(TabletType.type(extent));
      
      Tablet tablet = onlineTablets.get(extent);
      if (tablet == null)
        throw new NotServingTabletException(textent);
      
      ScanSession scanSession = new ScanSession();
      scanSession.user = credentials.user;
      scanSession.extent = new KeyExtent(extent);
      scanSession.columnSet = new HashSet<Column>();
      scanSession.ssiList = ssiList;
      scanSession.ssio = ssio;
      scanSession.interruptFlag = new AtomicBoolean();
      
      for (TColumn tcolumn : columns) {
        scanSession.columnSet.add(new Column(tcolumn));
      }
      
      scanSession.scanner = tablet.createScanner(new Range(range), batchSize, scanSession.columnSet, new Authorizations(authorizations), ssiList, ssio,
          isolated, scanSession.interruptFlag);
      
      long sid = sessionManager.createSession(scanSession, true);
      
      ScanResult scanResult;
      try {
        scanResult = continueScan(tinfo, sid, scanSession);
      } catch (NoSuchScanIDException e) {
        log.error("The impossible happened", e);
        throw new RuntimeException();
      } finally {
        sessionManager.unreserveSession(sid);
      }
      
      return new InitialScan(sid, scanResult);
    }
    
    @Override
    public ScanResult continueScan(TInfo tinfo, long scanID) throws NoSuchScanIDException, NotServingTabletException,
        org.apache.accumulo.core.tabletserver.thrift.TooManyFilesException {
      ScanSession scanSession = (ScanSession) sessionManager.reserveSession(scanID);
      if (scanSession == null) {
        throw new NoSuchScanIDException();
      }
      
      try {
        return continueScan(tinfo, scanID, scanSession);
      } finally {
        sessionManager.unreserveSession(scanSession);
      }
    }
    
    private ScanResult continueScan(TInfo tinfo, long scanID, ScanSession scanSession) throws NoSuchScanIDException, NotServingTabletException,
        org.apache.accumulo.core.tabletserver.thrift.TooManyFilesException {
      
      if (scanSession.nextBatchTask == null) {
        scanSession.nextBatchTask = new NextBatchTask(scanID, scanSession.interruptFlag);
        resourceManager.executeReadAhead(scanSession.extent, scanSession.nextBatchTask);
      }
      
      ScanBatch bresult;
      try {
        bresult = scanSession.nextBatchTask.get(MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS, TimeUnit.MILLISECONDS);
        scanSession.nextBatchTask = null;
      } catch (ExecutionException e) {
        sessionManager.removeSession(scanID);
        if (e.getCause() instanceof NotServingTabletException)
          throw (NotServingTabletException) e.getCause();
        else if (e.getCause() instanceof TooManyFilesException)
          throw new org.apache.accumulo.core.tabletserver.thrift.TooManyFilesException(scanSession.extent.toThrift());
        else
          throw new RuntimeException(e);
      } catch (CancellationException ce) {
        sessionManager.removeSession(scanID);
        Tablet tablet = onlineTablets.get(scanSession.extent);
        if (tablet == null || tablet.isClosed())
          throw new NotServingTabletException(scanSession.extent.toThrift());
        else
          throw new NoSuchScanIDException();
      } catch (TimeoutException e) {
        List<TKeyValue> param = Collections.emptyList();
        long timeout = acuConf.getTimeInMillis(Property.TSERV_CLIENT_TIMEOUT);
        sessionManager.removeIfNotAccessed(scanID, timeout);
        return new ScanResult(param, true);
      } catch (Throwable t) {
        sessionManager.removeSession(scanID);
        log.warn("Failed to get next batch", t);
        throw new RuntimeException(t);
      }
      
      ScanResult scanResult = new ScanResult(Key.compress(bresult.results), bresult.more);
      
      scanSession.entriesReturned += scanResult.results.size();
      
      scanSession.batchCount++;
      
      if (scanResult.more && scanSession.batchCount > 3) {
        // start reading next batch while current batch is transmitted
        // to client
        scanSession.nextBatchTask = new NextBatchTask(scanID, scanSession.interruptFlag);
        resourceManager.executeReadAhead(scanSession.extent, scanSession.nextBatchTask);
      }
      
      if (!scanResult.more)
        closeScan(tinfo, scanID);
      
      return scanResult;
    }
    
    @Override
    public void closeScan(TInfo tinfo, long scanID) {
      ScanSession ss = (ScanSession) sessionManager.removeSession(scanID);
      if (ss != null) {
        long t2 = System.currentTimeMillis();
        
        log.debug(String.format("ScanSess tid %s %s %,d entries in %.2f secs, nbTimes = [%s] ", TServerUtils.clientAddress.get(), ss.extent.getTableId()
            .toString(), ss.entriesReturned, (t2 - ss.startTime) / 1000.0, ss.nbTimes.toString()));
        if (scanMetrics.isEnabled()) {
          scanMetrics.add(TabletServerScanMetrics.scan, t2 - ss.startTime);
          scanMetrics.add(TabletServerScanMetrics.resultSize, ss.entriesReturned);
        }
      }
    }
    
    @Override
    public InitialMultiScan startMultiScan(TInfo tinfo, AuthInfo credentials, Map<TKeyExtent,List<TRange>> tbatch, List<TColumn> tcolumns,
        List<IterInfo> ssiList, Map<String,Map<String,String>> ssio, List<ByteBuffer> authorizations, boolean waitForWrites) throws ThriftSecurityException {
      // find all of the tables that need to be scanned
      HashSet<String> tables = new HashSet<String>();
      for (TKeyExtent keyExtent : tbatch.keySet()) {
        tables.add(new String(keyExtent.getTable()));
      }
      
      // check if user has permission to the tables
      Authorizations userauths = null;
      try {
        for (String table : tables)
          if (!authenticator.hasTablePermission(credentials, credentials.user, table, TablePermission.READ))
            throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
        
        userauths = authenticator.getUserAuthorizations(credentials, credentials.user);
        for (ByteBuffer auth : authorizations)
          if (!userauths.contains(ByteBufferUtil.toBytes(auth)))
            throw new ThriftSecurityException(credentials.user, SecurityErrorCode.BAD_AUTHORIZATIONS);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      KeyExtent threadPoolExtent = null;
      
      Map<KeyExtent,List<Range>> batch = Translator.translate(tbatch, Translator.TKET, new Translator.ListTranslator<TRange,Range>(Translator.TRT));
      
      for (KeyExtent keyExtent : batch.keySet()) {
        if (threadPoolExtent == null) {
          threadPoolExtent = keyExtent;
        } else if (keyExtent.equals(Constants.ROOT_TABLET_EXTENT)) {
          throw new IllegalArgumentException("Cannot batch query root tablet with other tablets " + threadPoolExtent + " " + keyExtent);
        } else if (keyExtent.getTableId().toString().equals(Constants.METADATA_TABLE_ID)
            && !threadPoolExtent.getTableId().toString().equals(Constants.METADATA_TABLE_ID)) {
          throw new IllegalArgumentException("Cannot batch query !METADATA and non !METADATA tablets " + threadPoolExtent + " " + keyExtent);
        }
        
      }
      
      if (waitForWrites)
        writeTracker.waitForWrites(TabletType.type(batch.keySet()));
      
      MultiScanSession mss = new MultiScanSession();
      mss.user = credentials.user;
      mss.queries = batch;
      mss.columnSet = new HashSet<Column>(tcolumns.size());
      mss.ssiList = ssiList;
      mss.ssio = ssio;
      mss.auths = new Authorizations(authorizations);
      
      mss.numTablets = batch.size();
      for (List<Range> ranges : batch.values()) {
        mss.numRanges += ranges.size();
      }
      
      for (TColumn tcolumn : tcolumns)
        mss.columnSet.add(new Column(tcolumn));
      
      mss.threadPoolExtent = threadPoolExtent;
      
      long sid = sessionManager.createSession(mss, true);
      
      MultiScanResult result;
      try {
        result = continueMultiScan(tinfo, sid, mss);
      } catch (NoSuchScanIDException e) {
        log.error("the impossible happened", e);
        throw new RuntimeException("the impossible happened", e);
      } finally {
        sessionManager.unreserveSession(sid);
      }
      
      scanCount.addAndGet(batch.size());
      return new InitialMultiScan(sid, result);
    }
    
    @Override
    public MultiScanResult continueMultiScan(TInfo tinfo, long scanID) throws NoSuchScanIDException {
      
      MultiScanSession session = (MultiScanSession) sessionManager.reserveSession(scanID);
      
      if (session == null) {
        throw new NoSuchScanIDException();
      }
      
      try {
        return continueMultiScan(tinfo, scanID, session);
      } finally {
        sessionManager.unreserveSession(session);
      }
    }
    
    private MultiScanResult continueMultiScan(TInfo tinfo, long scanID, MultiScanSession session) throws NoSuchScanIDException {
      
      if (session.lookupTask == null) {
        session.lookupTask = new LookupTask(scanID);
        resourceManager.executeReadAhead(session.threadPoolExtent, session.lookupTask);
      }
      
      try {
        MultiScanResult scanResult = session.lookupTask.get(MAX_TIME_TO_WAIT_FOR_SCAN_RESULT_MILLIS, TimeUnit.MILLISECONDS);
        session.lookupTask = null;
        return scanResult;
      } catch (TimeoutException e1) {
        long timeout = acuConf.getTimeInMillis(Property.TSERV_CLIENT_TIMEOUT);
        sessionManager.removeIfNotAccessed(scanID, timeout);
        List<TKeyValue> results = Collections.emptyList();
        Map<TKeyExtent,List<TRange>> failures = Collections.emptyMap();
        List<TKeyExtent> fullScans = Collections.emptyList();
        return new MultiScanResult(results, failures, fullScans, null, null, false, true);
      } catch (Throwable t) {
        sessionManager.removeSession(scanID);
        log.warn("Failed to get multiscan result", t);
        throw new RuntimeException(t);
      }
    }
    
    @Override
    public void closeMultiScan(TInfo tinfo, long scanID) throws NoSuchScanIDException {
      MultiScanSession session = (MultiScanSession) sessionManager.removeSession(scanID);
      if (session == null) {
        throw new NoSuchScanIDException();
      }
      
      long t2 = System.currentTimeMillis();
      log.debug(String.format("MultiScanSess %s %,d entries in %.2f secs (lookup_time:%.2f secs tablets:%,d ranges:%,d) ", TServerUtils.clientAddress.get(),
          session.numEntries, (t2 - session.startTime) / 1000.0, session.totalLookupTime / 1000.0, session.numTablets, session.numRanges));
    }
    
    @Override
    public long startUpdate(TInfo tinfo, AuthInfo credentials) throws ThriftSecurityException {
      // Make sure user is real
      try {
        if (!authenticator.authenticateUser(credentials, credentials.user, credentials.password)) {
          if (updateMetrics.isEnabled())
            updateMetrics.add(TabletServerUpdateMetrics.permissionErrors, 0);
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.BAD_CREDENTIALS);
        }
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      UpdateSession us = new UpdateSession();
      us.violations = new Violations();
      us.credentials = credentials;
      us.cenv = new TservConstraintEnv(authenticator, credentials);
      
      long sid = sessionManager.createSession(us, false);
      
      return sid;
    }
    
    private void setUpdateTablet(UpdateSession us, KeyExtent keyExtent) {
      long t1 = System.currentTimeMillis();
      if (us.currentTablet != null && us.currentTablet.getExtent().equals(keyExtent))
        return;

      if (us.currentTablet == null && (us.failures.containsKey(keyExtent) || us.authFailures.contains(keyExtent))) {
        // if there were previous failures, then do not accept additional writes
        return;
      }
      
      try {
        // if user has no permission to write to this table, add it to
        // the failures list
        boolean sameTable = us.currentTablet != null && (us.currentTablet.getExtent().getTableId().equals(keyExtent.getTableId()));
        if (sameTable
            || authenticator.hasTablePermission(SecurityConstants.getSystemCredentials(), us.credentials.user, keyExtent.getTableId().toString(),
                TablePermission.WRITE)) {
          long t2 = System.currentTimeMillis();
          us.authTimes.addStat(t2 - t1);
          us.currentTablet = onlineTablets.get(keyExtent);
          if (us.currentTablet != null) {
            us.queuedMutations.put(us.currentTablet, new ArrayList<Mutation>());
          } else {
            // not serving tablet, so report all mutations as
            // failures
            us.failures.put(keyExtent, 0l);
            if (updateMetrics.isEnabled())
              updateMetrics.add(TabletServerUpdateMetrics.unknownTabletErrors, 0);
          }
        } else {
          log.warn("Denying access to table " + keyExtent.getTableId() + " for user " + us.credentials.user);
          long t2 = System.currentTimeMillis();
          us.authTimes.addStat(t2 - t1);
          us.currentTablet = null;
          us.authFailures.add(keyExtent);
          if (updateMetrics.isEnabled())
            updateMetrics.add(TabletServerUpdateMetrics.permissionErrors, 0);
          return;
        }
      } catch (AccumuloSecurityException e) {
        log.error("Denying permission to check user " + us.credentials.user + " with user " + e.getUser(), e);
        long t2 = System.currentTimeMillis();
        us.authTimes.addStat(t2 - t1);
        us.currentTablet = null;
        us.authFailures.add(keyExtent);
        if (updateMetrics.isEnabled())
          updateMetrics.add(TabletServerUpdateMetrics.permissionErrors, 0);
        return;
      }
    }
    
    @Override
    public void applyUpdates(TInfo tinfo, long updateID, TKeyExtent tkeyExtent, List<TMutation> tmutations) {
      UpdateSession us = (UpdateSession) sessionManager.reserveSession(updateID);
      if (us == null) {
        throw new RuntimeException("No Such SessionID");
      }
      
      try {
        KeyExtent keyExtent = new KeyExtent(tkeyExtent);
        setUpdateTablet(us, keyExtent);
        
        if (us.currentTablet != null) {
          List<Mutation> mutations = us.queuedMutations.get(us.currentTablet);
          for (TMutation tmutation : tmutations) {
            Mutation mutation = new Mutation(tmutation);
            mutations.add(mutation);
            us.queuedMutationSize += mutation.numBytes();
          }
          if (us.queuedMutationSize > ServerConfiguration.getSystemConfiguration().getMemoryInBytes(Property.TSERV_MUTATION_QUEUE_MAX))
            flush(us);
        }
      } finally {
        sessionManager.unreserveSession(us);
      }
    }
    
    private void flush(UpdateSession us) {
      
      int mutationCount = 0;
      Map<CommitSession,List<Mutation>> sendables = new HashMap<CommitSession,List<Mutation>>();
      Throwable error = null;
      
      long pt1 = System.currentTimeMillis();
      
      boolean containsMetadataTablet = false;
      for (Tablet tablet : us.queuedMutations.keySet())
        if (tablet.getExtent().getTableId().toString().equals(Constants.METADATA_TABLE_ID))
          containsMetadataTablet = true;
      
      if (!containsMetadataTablet && us.queuedMutations.size() > 0)
        TabletServer.this.resourceManager.waitUntilCommitsAreEnabled();
      
      Span prep = Trace.start("prep");
      for (Entry<Tablet,? extends List<Mutation>> entry : us.queuedMutations.entrySet()) {
        
        Tablet tablet = entry.getKey();
        List<Mutation> mutations = entry.getValue();
        if (mutations.size() > 0) {
          try {
            if (updateMetrics.isEnabled())
              updateMetrics.add(TabletServerUpdateMetrics.mutationArraySize, mutations.size());
            
            CommitSession commitSession = tablet.prepareMutationsForCommit(us.cenv, mutations);
            if (commitSession == null) {
              if (us.currentTablet == tablet) {
                us.currentTablet = null;
              }
              us.failures.put(tablet.getExtent(), us.successfulCommits.get(tablet));
            } else {
              sendables.put(commitSession, mutations);
              mutationCount += mutations.size();
            }
            
          } catch (TConstraintViolationException e) {
            us.violations.add(e.getViolations());
            if (updateMetrics.isEnabled())
              updateMetrics.add(TabletServerUpdateMetrics.constraintViolations, 0);
            
            if (e.getNonViolators().size() > 0) {
              // only log and commit mutations if there were some
              // that did not
              // violate constraints... this is what
              // prepareMutationsForCommit()
              // expects
              sendables.put(e.getCommitSession(), e.getNonViolators());
            }
            
            mutationCount += mutations.size();
            
          } catch (HoldTimeoutException t) {
            error = t;
            log.debug("Giving up on mutations due to a long memory hold time");
            break;
          } catch (Throwable t) {
            error = t;
            log.error("Unexpected error preparing for commit", error);
            break;
          }
        }
      }
      prep.stop();
      
      Span wal = Trace.start("wal");
      long pt2 = System.currentTimeMillis();
      long avgPrepareTime = (long) ((pt2 - pt1) / (double) us.queuedMutations.size());
      us.prepareTimes.addStat(pt2 - pt1);
      if (updateMetrics.isEnabled())
        updateMetrics.add(TabletServerUpdateMetrics.commitPrep, (avgPrepareTime));
      
      if (error != null) {
        for (Entry<CommitSession,List<Mutation>> e : sendables.entrySet()) {
          e.getKey().abortCommit(e.getValue());
        }
        throw new RuntimeException(error);
      }
      try {
        while (true) {
          try {
            long t1 = System.currentTimeMillis();
            
            logger.logManyTablets(sendables);
            
            long t2 = System.currentTimeMillis();
            us.walogTimes.addStat(t2 - t1);
            if (updateMetrics.isEnabled())
              updateMetrics.add(TabletServerUpdateMetrics.waLogWriteTime, (t2 - t1));
            
            break;
          } catch (IOException ex) {
            log.warn("logging mutations failed, retrying");
          } catch (Throwable t) {
            log.error("Unknown exception logging mutations, counts for mutations in flight not decremented!", t);
            throw new RuntimeException(t);
          }
        }
        
        wal.stop();
        
        Span commit = Trace.start("commit");
        long t1 = System.currentTimeMillis();
        for (Entry<CommitSession,? extends List<Mutation>> entry : sendables.entrySet()) {
          CommitSession commitSession = entry.getKey();
          List<Mutation> mutations = entry.getValue();
          
          commitSession.commit(mutations);
          
          Tablet tablet = commitSession.getTablet();
          
          if (tablet == us.currentTablet) {
            // because constraint violations may filter out some
            // mutations, for proper
            // accounting with the client code, need to increment
            // the count based
            // on the original number of mutations from the client
            // NOT the filtered number
            us.successfulCommits.increment(tablet, us.queuedMutations.get(tablet).size());
          }
        }
        long t2 = System.currentTimeMillis();
        
        long avgCommitTime = (long) ((t2 - t1) / (double) sendables.size());
        
        us.flushTime += (t2 - pt1);
        us.commitTimes.addStat(t2 - t1);
        
        if (updateMetrics.isEnabled())
          updateMetrics.add(TabletServerUpdateMetrics.commitTime, avgCommitTime);
        commit.stop();
      } finally {
        us.queuedMutations.clear();
        if (us.currentTablet != null) {
          us.queuedMutations.put(us.currentTablet, new ArrayList<Mutation>());
        }
        us.queuedMutationSize = 0;
      }
      us.totalUpdates += mutationCount;
    }
    
    @Override
    public UpdateErrors closeUpdate(TInfo tinfo, long updateID) throws NoSuchScanIDException {
      UpdateSession us = (UpdateSession) sessionManager.removeSession(updateID);
      if (us == null) {
        throw new NoSuchScanIDException();
      }
      
      // clients may or may not see data from an update session while
      // it is in progress, however when the update session is closed
      // want to ensure that reads wait for the write to finish
      long opid = writeTracker.startWrite(us.queuedMutations.keySet());
      
      try {
        flush(us);
      } finally {
        writeTracker.finishWrite(opid);
      }
      
      log.debug(String.format("UpSess %s %,d in %.3fs, at=[%s] ft=%.3fs(pt=%.3fs lt=%.3fs ct=%.3fs)", TServerUtils.clientAddress.get(), us.totalUpdates,
          (System.currentTimeMillis() - us.startTime) / 1000.0, us.authTimes.toString(), us.flushTime / 1000.0, us.prepareTimes.getSum() / 1000.0,
          us.walogTimes.getSum() / 1000.0, us.commitTimes.getSum() / 1000.0));
      if (us.failures.size() > 0) {
        Entry<KeyExtent,Long> first = us.failures.entrySet().iterator().next();
        log.debug(String.format("Failures: %d, first extent %s successful commits: %d", us.failures.size(), first.getKey().toString(), first.getValue()));
      }
      List<ConstraintViolationSummary> violations = us.violations.asList();
      if (violations.size() > 0) {
        ConstraintViolationSummary first = us.violations.asList().iterator().next();
        log.debug(String.format("Violations: %d, first %s occurs %d", violations.size(), first.violationDescription, first.numberOfViolatingMutations));
      }
      if (us.authFailures.size() > 0) {
        KeyExtent first = us.authFailures.iterator().next();
        log.debug(String.format("Authentication Failures: %d, first %s", us.authFailures.size(), first.toString()));
      }
      
      return new UpdateErrors(Translator.translate(us.failures, Translator.KET), Translator.translate(violations, Translator.CVST), Translator.translate(
          us.authFailures, Translator.KET));
    }
    
    @Override
    public void update(TInfo tinfo, AuthInfo credentials, TKeyExtent tkeyExtent, TMutation tmutation) throws NotServingTabletException,
        ConstraintViolationException, ThriftSecurityException {
      try {
        if (!authenticator.hasTablePermission(credentials, credentials.user, new String(tkeyExtent.getTable()), TablePermission.WRITE))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      KeyExtent keyExtent = new KeyExtent(tkeyExtent);
      Tablet tablet = onlineTablets.get(new KeyExtent(keyExtent));
      if (tablet == null) {
        throw new NotServingTabletException(tkeyExtent);
      }
      
      if (!keyExtent.getTableId().toString().equals(Constants.METADATA_TABLE_ID))
        TabletServer.this.resourceManager.waitUntilCommitsAreEnabled();
      
      long opid = writeTracker.startWrite(TabletType.type(keyExtent));
      
      try {
        Mutation mutation = new Mutation(tmutation);
        List<Mutation> mutations = Collections.singletonList(mutation);
        
        Span prep = Trace.start("prep");
        CommitSession cs = tablet.prepareMutationsForCommit(new TservConstraintEnv(authenticator, credentials), mutations);
        prep.stop();
        if (cs == null) {
          throw new NotServingTabletException(tkeyExtent);
        }
        
        while (true) {
          try {
            Span wal = Trace.start("wal");
            logger.log(cs, cs.getWALogSeq(), mutation);
            wal.stop();
            break;
          } catch (IOException ex) {
            log.warn(ex, ex);
          }
        }
        
        Span commit = Trace.start("commit");
        cs.commit(mutations);
        commit.stop();
      } catch (TConstraintViolationException e) {
        throw new ConstraintViolationException(Translator.translate(e.getViolations().asList(), Translator.CVST));
      } finally {
        writeTracker.finishWrite(opid);
      }
    }
    
    @Override
    public void splitTablet(TInfo tinfo, AuthInfo credentials, TKeyExtent tkeyExtent, ByteBuffer splitPoint) throws NotServingTabletException,
        ThriftSecurityException {
      String tableId = new String(ByteBufferUtil.toBytes(tkeyExtent.table));
      try {
        if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_TABLE)
            && !authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM)
            && !authenticator.hasTablePermission(credentials, credentials.user, tableId, TablePermission.ALTER_TABLE))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      KeyExtent keyExtent = new KeyExtent(tkeyExtent);
      
      Tablet tablet = onlineTablets.get(keyExtent);
      if (tablet == null) {
        throw new NotServingTabletException(tkeyExtent);
      }
      
      if (keyExtent.getEndRow() == null || !keyExtent.getEndRow().equals(ByteBufferUtil.toText(splitPoint))) {
        try {
          if (TabletServer.this.splitTablet(tablet, ByteBufferUtil.toBytes(splitPoint)) == null) {
            throw new NotServingTabletException(tkeyExtent);
          }
        } catch (IOException e) {
          log.warn("Failed to split " + keyExtent, e);
          throw new RuntimeException(e);
        }
      }
    }
    
    @Override
    public TabletServerStatus getTabletServerStatus(TInfo tinfo, AuthInfo credentials) throws ThriftSecurityException, TException {
      return getStats(sessionManager.getActiveScansPerTable());
    }
    
    @Override
    public List<TabletStats> getTabletStats(TInfo tinfo, AuthInfo credentials, String tableId) throws ThriftSecurityException, TException {
      TreeMap<KeyExtent,Tablet> onlineTabletsCopy;
      synchronized (onlineTablets) {
        onlineTabletsCopy = new TreeMap<KeyExtent,Tablet>(onlineTablets);
      }
      List<TabletStats> result = new ArrayList<TabletStats>();
      Text text = new Text(tableId);
      KeyExtent start = new KeyExtent(text, new Text(), null);
      for (Entry<KeyExtent,Tablet> entry : onlineTabletsCopy.tailMap(start).entrySet()) {
        KeyExtent ke = entry.getKey();
        if (ke.getTableId().compareTo(text) == 0) {
          Tablet tablet = entry.getValue();
          TabletStats stats = tablet.timer.getTabletStats();
          stats.extent = ke.toThrift();
          stats.ingestRate = tablet.ingestRate();
          stats.queryRate = tablet.queryRate();
          stats.splitCreationTime = tablet.getSplitCreationTime();
          stats.numEntries = tablet.getNumEntries();
          result.add(stats);
        }
      }
      return result;
    }
    
    private ZooCache masterLockCache = new ZooCache();
    
    private void checkPermission(AuthInfo credentials, String lock, boolean requiresSystemPermission, final String request) throws ThriftSecurityException {
      if (requiresSystemPermission) {
        boolean fatal = false;
        try {
          log.debug("Got " + request + " message from user: " + credentials.user);
          if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM)) {
            log.warn("Got " + request + " message from user: " + credentials.user);
            throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
          }
        } catch (AccumuloSecurityException e) {
          log.warn("Got " + request + " message from unauthenticatable user: " + e.getUser());
          if (e.getUser().equals(SecurityConstants.SYSTEM_USERNAME)) {
            log.fatal("Got message from a service with a mismatched configuration. Please ensure a compatible configuration.", e);
            fatal = true;
          }
          throw e.asThriftException();
        } finally {
          if (fatal) {
            Halt.halt(1, new Runnable() {
              public void run() {
                logGCInfo();
              }
            });
          }
        }
      }
      
      if (tabletServerLock == null || !tabletServerLock.wasLockAcquired()) {
        log.warn("Got " + request + " message from master before lock acquired, ignoring...");
        throw new RuntimeException("Lock not acquired");
      }
      
      if (tabletServerLock != null && tabletServerLock.wasLockAcquired() && !tabletServerLock.isLocked()) {
        Halt.halt(1, new Runnable() {
          public void run() {
            log.info("Tablet server no longer holds lock during checkPermission() : " + request + ", exiting");
            logGCInfo();
          }
        });
      }
      
      ZooUtil.LockID lid = new ZooUtil.LockID(ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZMASTER_LOCK, lock);
      
      try {
        if (!ZooLock.isLockHeld(masterLockCache, lid)) {
          // maybe the cache is out of date and a new master holds the
          // lock?
          masterLockCache.clear();
          if (!ZooLock.isLockHeld(masterLockCache, lid)) {
            log.warn("Got " + request + " message from a master that does not hold the current lock " + lock);
            throw new RuntimeException("bad master lock");
          }
        }
      } catch (Exception e) {
        throw new RuntimeException("bad master lock", e);
      }
    }
    
    @Override
    public void loadTablet(TInfo tinfo, AuthInfo credentials, String lock, final TKeyExtent textent) {
      try {
        checkPermission(credentials, lock, true, "loadTablet");
      } catch (ThriftSecurityException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
      
      final KeyExtent extent = new KeyExtent(textent);
      
      synchronized (unopenedTablets) {
        synchronized (openingTablets) {
          synchronized (onlineTablets) {
            
            // checking if this exact tablet is in any of the sets
            // below is not a strong enough check
            // when splits and fix splits occurring
            
            Set<KeyExtent> unopenedOverlapping = KeyExtent.findOverlapping(extent, unopenedTablets);
            Set<KeyExtent> openingOverlapping = KeyExtent.findOverlapping(extent, openingTablets);
            Set<KeyExtent> onlineOverlapping = KeyExtent.findOverlapping(extent, onlineTablets);
            Set<KeyExtent> all = new HashSet<KeyExtent>();
            all.addAll(unopenedOverlapping);
            all.addAll(openingOverlapping);
            all.addAll(onlineOverlapping);
            
            if (!all.isEmpty()) {
              if (all.size() != 1 || !all.contains(extent)) {
                log.error("Tablet " + extent + " overlaps previously assigned " + unopenedOverlapping + " " + openingOverlapping + " " + onlineOverlapping);
              }
              return;
            }
            
            unopenedTablets.add(extent);
          }
        }
      }
      
      // add the assignment job to the appropriate queue
      log.info("Loading tablet " + extent);
      
      final Runnable ah = new LoggingRunnable(log, new AssignmentHandler(extent));
      // Root tablet assignment must take place immediately
      if (extent.compareTo(Constants.ROOT_TABLET_EXTENT) == 0) {
        new Thread("Root Tablet Assignment") {
          public void run() {
            ah.run();
            if (onlineTablets.containsKey(extent)) {
              log.info("Root tablet loaded: " + extent);
            } else {
              log.info("Root tablet failed to load");
            }
            
          }
        }.start();
      } else {
        if (extent.getTableId().compareTo(new Text(Constants.METADATA_TABLE_ID)) == 0) {
          resourceManager.addMetaDataAssignment(ah);
        } else {
          resourceManager.addAssignment(ah);
        }
      }
    }
    
    @Override
    public void unloadTablet(TInfo tinfo, AuthInfo credentials, String lock, TKeyExtent textent, boolean save) {
      try {
        checkPermission(credentials, lock, true, "unloadTablet");
      } catch (ThriftSecurityException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
      
      KeyExtent extent = new KeyExtent(textent);
      
      resourceManager.addMigration(extent, new LoggingRunnable(log, new UnloadTabletHandler(extent, save)));
    }
    
    @Override
    public void flush(TInfo tinfo, AuthInfo credentials, String lock, String tableId, ByteBuffer startRow, ByteBuffer endRow) {
      try {
        checkPermission(credentials, lock, true, "flush");
      } catch (ThriftSecurityException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
      
      ArrayList<Tablet> tabletsToFlush = new ArrayList<Tablet>();
      
      KeyExtent ke = new KeyExtent(new Text(tableId), ByteBufferUtil.toText(endRow), ByteBufferUtil.toText(startRow));
      
      synchronized (onlineTablets) {
        for (Tablet tablet : onlineTablets.values())
          if (ke.overlaps(tablet.getExtent()))
            tabletsToFlush.add(tablet);
      }
      
      Long flushID = null;
      
      for (Tablet tablet : tabletsToFlush) {
        if (flushID == null) {
          // read the flush id once from zookeeper instead of reading
          // it for each tablet
          try {
            flushID = tablet.getFlushID();
          } catch (NoNodeException e) {
            // table was probably deleted
            log.info("Asked to flush table that has no flush id " + ke + " " + e.getMessage());
            return;
          }
        }
        tablet.flush(flushID);
      }
    }
    
    @Override
    public void flushTablet(TInfo tinfo, AuthInfo credentials, String lock, TKeyExtent textent) throws TException {
      try {
        checkPermission(credentials, lock, true, "flushTablet");
      } catch (ThriftSecurityException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
      Tablet tablet = onlineTablets.get(new KeyExtent(textent));
      if (tablet != null) {
        log.info("Flushing " + tablet.getExtent());
        try {
          tablet.flush(tablet.getFlushID());
        } catch (NoNodeException nne) {
          log.info("Asked to flush tablet that has no flush id " + new KeyExtent(textent) + " " + nne.getMessage());
        }
      }
    }
    
    @Override
    public void halt(TInfo tinfo, AuthInfo credentials, String lock) throws ThriftSecurityException {
      
      checkPermission(credentials, lock, true, "halt");
      
      Halt.halt(0, new Runnable() {
        public void run() {
          log.info("Master requested tablet server halt");
          logGCInfo();
          serverStopRequested = true;
          try {
            tabletServerLock.unlock();
          } catch (Exception e) {
            log.error(e, e);
          }
        }
      });
    }
    
    @Override
    public void fastHalt(TInfo info, AuthInfo credentials, String lock) {
      try {
        halt(info, credentials, lock);
      } catch (Exception e) {
        log.warn("Error halting", e);
      }
    }
    
    @Override
    public TabletStats getHistoricalStats(TInfo tinfo, AuthInfo credentials) throws ThriftSecurityException, TException {
      return statsKeeper.getTabletStats();
    }
    
    @Override
    public void useLoggers(TInfo tinfo, AuthInfo credentials, Set<String> loggers) throws TException {
      loggerStrategy.preferLoggers(loggers);
    }
    
    @Override
    public List<ActiveScan> getActiveScans(TInfo tinfo, AuthInfo credentials) throws ThriftSecurityException, TException {
      
      try {
        if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
      
      return sessionManager.getActiveScans();
    }
    
    @Override
    public void chop(TInfo tinfo, AuthInfo credentials, String lock, TKeyExtent textent) throws TException {
      try {
        if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      KeyExtent ke = new KeyExtent(textent);
      
      Tablet tablet = onlineTablets.get(ke);
      if (tablet != null) {
        tablet.chopFiles();
      }
    }
    
    @Override
    public void compact(TInfo tinfo, AuthInfo credentials, String lock, String tableId, ByteBuffer startRow, ByteBuffer endRow) throws TException {
      try {
        if (!authenticator.hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM))
          throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      KeyExtent ke = new KeyExtent(new Text(tableId), ByteBufferUtil.toText(endRow), ByteBufferUtil.toText(startRow));
      
      ArrayList<Tablet> tabletsToCompact = new ArrayList<Tablet>();
      synchronized (onlineTablets) {
        for (Tablet tablet : onlineTablets.values())
          if (ke.overlaps(tablet.getExtent()))
            tabletsToCompact.add(tablet);
      }
      
      Long compactionId = null;
      
      for (Tablet tablet : tabletsToCompact) {
        // all for the same table id, so only need to read
        // compaction id once
        if (compactionId == null)
          try {
            compactionId = tablet.getCompactionID();
          } catch (NoNodeException e) {
            log.info("Asked to compact table with no compaction id " + ke + " " + e.getMessage());
            return;
          }
        tablet.compactAll(compactionId);
      }
      
    }
    
  }
  
  private class SplitRunner implements Runnable {
    private Tablet tablet;
    
    public SplitRunner(Tablet tablet) {
      this.tablet = tablet;
    }
    
    @Override
    public void run() {
      if (majorCompactorDisabled) {
        // this will make split task that were queued when shutdown was
        // initiated exit
        return;
      }
      
      splitTablet(tablet);
    }
  }
  
  boolean isMajorCompactionDisabled() {
    return majorCompactorDisabled;
  }
  
  private class MajorCompactor implements Runnable {
    private AccumuloConfiguration acuConf = ServerConfiguration.getSystemConfiguration();
    
    public void run() {
      while (!majorCompactorDisabled) {
        try {
          UtilWaitThread.sleep(acuConf.getTimeInMillis(Property.TSERV_MAJC_DELAY));
          
          TreeMap<KeyExtent,Tablet> copyOnlineTablets = new TreeMap<KeyExtent,Tablet>();
          
          synchronized (onlineTablets) {
            copyOnlineTablets.putAll(onlineTablets); // avoid
                                                     // concurrent
                                                     // modification
          }
          
          int numMajorCompactionsInProgress = 0;
          
          Iterator<Entry<KeyExtent,Tablet>> iter = copyOnlineTablets.entrySet().iterator();
          while (iter.hasNext() && !majorCompactorDisabled) { // bail
                                                              // early
                                                              // now
                                                              // if
                                                              // we're
                                                              // shutting
                                                              // down
            
            Entry<KeyExtent,Tablet> entry = iter.next();
            
            Tablet tablet = entry.getValue();
            
            // if we need to split AND compact, we need a good way
            // to decide what to do
            if (tablet.needsSplit()) {
              resourceManager.executeSplit(tablet.getExtent(), new LoggingRunnable(log, new SplitRunner(tablet)));
              continue;
            }
            
            int maxLogEntriesPerTablet = ServerConfiguration.getTableConfiguration(tablet.getExtent().getTableId().toString()).getCount(
                Property.TABLE_MINC_LOGS_MAX);
            
            if (tablet.getLogCount() >= maxLogEntriesPerTablet) {
              log.debug("Initiating minor compaction for " + tablet.getExtent() + " because it has " + tablet.getLogCount() + " write ahead logs");
              tablet.initiateMinorCompaction();
            }
            
            synchronized (tablet) {
              if (tablet.initiateMajorCompaction(MajorCompactionReason.NORMAL) || tablet.majorCompactionQueued() || tablet.majorCompactionRunning()) {
                numMajorCompactionsInProgress++;
                continue;
              }
            }
          }
          
          int idleCompactionsToStart = Math.max(1, acuConf.getCount(Property.TSERV_MAJC_MAXCONCURRENT) / 2);
          
          if (numMajorCompactionsInProgress < idleCompactionsToStart) {
            // system is not major compacting, can schedule some
            // idle compactions
            iter = copyOnlineTablets.entrySet().iterator();
            
            while (iter.hasNext() && !majorCompactorDisabled && numMajorCompactionsInProgress < idleCompactionsToStart) {
              Entry<KeyExtent,Tablet> entry = iter.next();
              Tablet tablet = entry.getValue();
              
              if (tablet.initiateMajorCompaction(MajorCompactionReason.IDLE)) {
                numMajorCompactionsInProgress++;
              }
            }
          }
        } catch (Throwable t) {
          log.error("Unexpected exception in " + Thread.currentThread().getName(), t);
          UtilWaitThread.sleep(1000);
        }
      }
    }
  }
  
  private void splitTablet(Tablet tablet) {
    try {
      
      TreeMap<KeyExtent,SplitInfo> tabletInfo = splitTablet(tablet, null);
      if (tabletInfo == null) {
        // either split or compact not both
        // were not able to split... so see if a major compaction is
        // needed
        tablet.initiateMajorCompaction(MajorCompactionReason.NORMAL);
      }
    } catch (IOException e) {
      statsKeeper.updateTime(Operation.SPLIT, 0, 0, true);
      log.error("split failed: " + e.getMessage() + " for tablet " + tablet.getExtent(), e);
    } catch (Exception e) {
      statsKeeper.updateTime(Operation.SPLIT, 0, 0, true);
      log.error("Unknown error on split: " + e, e);
    }
  }
  
  private TreeMap<KeyExtent,SplitInfo> splitTablet(Tablet tablet, byte[] splitPoint) throws IOException {
    long t1 = System.currentTimeMillis();
    
    TreeMap<KeyExtent,SplitInfo> tabletInfo = tablet.split(splitPoint);
    if (tabletInfo == null) {
      return null;
    }
    
    log.info("Starting split: " + tablet.getExtent());
    statsKeeper.incrementStatusSplit();
    long start = System.currentTimeMillis();
    
    Tablet[] newTablets = new Tablet[2];
    
    Entry<KeyExtent,SplitInfo> first = tabletInfo.firstEntry();
    newTablets[0] = new Tablet(TabletServer.this, new Text(first.getValue().dir), first.getKey(), resourceManager.createTabletResourceManager(),
        first.getValue().datafiles, first.getValue().time, first.getValue().initFlushID, first.getValue().initCompactID);
    
    Entry<KeyExtent,SplitInfo> last = tabletInfo.lastEntry();
    newTablets[1] = new Tablet(TabletServer.this, new Text(last.getValue().dir), last.getKey(), resourceManager.createTabletResourceManager(),
        last.getValue().datafiles, last.getValue().time, last.getValue().initFlushID, last.getValue().initCompactID);
    
    // roll tablet stats over into tablet server's statsKeeper object as
    // historical data
    statsKeeper.saveMinorTimes(tablet.timer);
    statsKeeper.saveMajorTimes(tablet.timer);
    
    // lose the reference to the old tablet and open two new ones
    synchronized (onlineTablets) {
      onlineTablets.remove(tablet.getExtent());
      onlineTablets.put(newTablets[0].getExtent(), newTablets[0]);
      onlineTablets.put(newTablets[1].getExtent(), newTablets[1]);
    }
    // tell the master
    enqueueMasterMessage(new SplitReportMessage(tablet.getExtent(), newTablets[0].getExtent(), new Text("/" + newTablets[0].getLocation().getName()),
        newTablets[1].getExtent(), new Text("/" + newTablets[1].getLocation().getName())));
    
    statsKeeper.updateTime(Operation.SPLIT, start, 0, false);
    long t2 = System.currentTimeMillis();
    log.info("Tablet split: " + tablet.getExtent() + " size0 " + newTablets[0].estimateTabletSize() + " size1 " + newTablets[1].estimateTabletSize() + " time "
        + (t2 - t1) + "ms");
    
    return tabletInfo;
  }
  
  public long lastPingTime = System.currentTimeMillis();
  public Socket currentMaster;
  
  // a queue to hold messages that are to be sent back to the master
  private BlockingDeque<MasterMessage> masterMessages = new LinkedBlockingDeque<MasterMessage>();
  
  // add a message for the main thread to send back to the master
  void enqueueMasterMessage(MasterMessage m) {
    masterMessages.addLast(m);
  }
  
  private class UnloadTabletHandler implements Runnable {
    private KeyExtent extent;
    private boolean saveState;
    
    public UnloadTabletHandler(KeyExtent extent, boolean saveState) {
      this.extent = extent;
      this.saveState = saveState;
    }
    
    public void run() {
      
      Tablet t = null;
      
      synchronized (unopenedTablets) {
        if (unopenedTablets.contains(extent)) {
          unopenedTablets.remove(extent);
          // enqueueMasterMessage(new TabletUnloadedMessage(extent));
          return;
        }
      }
      synchronized (openingTablets) {
        while (openingTablets.contains(extent)) {
          try {
            openingTablets.wait();
          } catch (InterruptedException e) {}
        }
      }
      synchronized (onlineTablets) {
        if (onlineTablets.containsKey(extent)) {
          t = onlineTablets.get(extent);
        }
      }
      
      if (t == null) {
        // Tablet has probably been recently unloaded: repeated master
        // unload request is crossing the successful unloaded message
        if (!recentlyUnloadedCache.containsKey(extent)) {
          log.info("told to unload tablet that was not being served " + extent);
          enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.UNLOAD_FAILURE_NOT_SERVING, extent));
        }
        return;
      }
      
      try {
        t.close(saveState);
      } catch (Throwable e) {
        
        if ((t.isClosing() || t.isClosed()) && e instanceof IllegalStateException) {
          log.debug("Failed to unload tablet " + extent + "... it was alread closing or closed : " + e.getMessage());
        } else {
          log.error("Failed to close tablet " + extent + "... Aborting migration", e);
        }
        
        enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.UNLOAD_ERROR, extent));
        return;
      }
      
      // stop serving tablet - client will get not serving tablet
      // exceptions
      recentlyUnloadedCache.put(extent, System.currentTimeMillis());
      onlineTablets.remove(extent);
      
      try {
        TServerInstance instance = new TServerInstance(clientAddress, getLock().getSessionId());
        TabletLocationState tls = new TabletLocationState(extent, null, instance, null, null, false);
        log.debug("Unassigning " + tls);
        TabletStateStore.unassign(tls);
      } catch (DistributedStoreException ex) {
        log.warn("Unable to update storage", ex);
      } catch (KeeperException e) {
        log.warn("Unable determine our zookeeper session information", e);
      } catch (InterruptedException e) {
        log.warn("Interrupted while getting our zookeeper session information", e);
      }
      
      // tell the master how it went
      enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.UNLOADED, extent));
      
      // roll tablet stats over into tablet server's statsKeeper object as
      // historical data
      statsKeeper.saveMinorTimes(t.timer);
      statsKeeper.saveMajorTimes(t.timer);
      
      log.info("unloaded " + extent);
      
    }
  }
  
  private class AssignmentHandler implements Runnable {
    private KeyExtent extent;
    private int retryAttempt = 0;
    
    public AssignmentHandler(KeyExtent extent) {
      this.extent = extent;
    }
    
    public AssignmentHandler(KeyExtent extent, int retryAttempt) {
      this(extent);
      this.retryAttempt = retryAttempt;
    }
    
    public void run() {
      log.info(clientAddress + ": got assignment from master: " + extent);
      
      final boolean isMetaDataTablet = extent.getTableId().toString().compareTo(Constants.METADATA_TABLE_ID) == 0;
      
      synchronized (unopenedTablets) {
        synchronized (openingTablets) {
          synchronized (onlineTablets) {
            // nothing should be moving between sets, do a sanity
            // check
            Set<KeyExtent> unopenedOverlapping = KeyExtent.findOverlapping(extent, unopenedTablets);
            Set<KeyExtent> openingOverlapping = KeyExtent.findOverlapping(extent, openingTablets);
            Set<KeyExtent> onlineOverlapping = KeyExtent.findOverlapping(extent, onlineTablets);
            
            if (openingOverlapping.contains(extent) || onlineOverlapping.contains(extent))
              return;
            
            if (!unopenedTablets.contains(extent) || unopenedOverlapping.size() != 1 || openingOverlapping.size() > 0 || onlineOverlapping.size() > 0) {
              throw new IllegalStateException("overlaps assigned " + extent + " " + !unopenedTablets.contains(extent) + " " + unopenedOverlapping + " "
                  + openingOverlapping + " " + onlineOverlapping);
            }
          }
          
          unopenedTablets.remove(extent);
          openingTablets.add(extent);
        }
      }
      
      log.debug("Loading extent: " + extent);
      
      // check Metadata table before accepting assignment
      SortedMap<KeyExtent,Text> tabletsInRange = null;
      SortedMap<Key,Value> tabletsKeyValues = new TreeMap<Key,Value>();
      try {
        tabletsInRange = verifyTabletInformation(extent, TabletServer.this.getTabletSession(), tabletsKeyValues, getClientAddressString(), getLock());
      } catch (Exception e) {
        synchronized (openingTablets) {
          openingTablets.remove(extent);
          openingTablets.notifyAll();
        }
        log.warn("Failed to verify tablet " + extent, e);
        throw new RuntimeException(e);
      }
      
      if (tabletsInRange == null) {
        log.info("Reporting tablet " + extent + " assignment failure: unable to verify Tablet Information");
        enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.LOAD_FAILURE, extent));
        synchronized (openingTablets) {
          openingTablets.remove(extent);
          openingTablets.notifyAll();
        }
        return;
      }
      // If extent given is not the one to be opened, update
      if (tabletsInRange.size() != 1 || !tabletsInRange.containsKey(extent)) {
        
        tabletsKeyValues.clear();
        
        synchronized (openingTablets) {
          openingTablets.remove(extent);
          openingTablets.notifyAll();
          for (KeyExtent e : tabletsInRange.keySet())
            openingTablets.add(e);
        }
      } else {
        // remove any metadata entries for the previous tablet
        Iterator<Key> iter = tabletsKeyValues.keySet().iterator();
        Text row = extent.getMetadataEntry();
        while (iter.hasNext()) {
          Key key = iter.next();
          if (!key.getRow().equals(row)) {
            iter.remove();
          }
        }
      }
      if (tabletsInRange.size() > 1) {
        log.debug("Master didn't know " + extent + " was split, letting it know about " + tabletsInRange.keySet());
        enqueueMasterMessage(new SplitReportMessage(extent, tabletsInRange));
      }
      
      // create the tablet object
      for (Entry<KeyExtent,Text> entry : tabletsInRange.entrySet()) {
        Tablet tablet = null;
        boolean successful = false;
        
        final KeyExtent extentToOpen = entry.getKey();
        Text locationToOpen = entry.getValue();
        
        if (onlineTablets.containsKey(extentToOpen)) {
          // know this was from fixing a split, because initial check
          // would have caught original extent
          log.warn("Something is screwy!  Already serving tablet " + extentToOpen + " derived from fixing split. Original extent = " + extent);
          synchronized (openingTablets) {
            openingTablets.remove(extentToOpen);
            openingTablets.notifyAll();
          }
          continue;
        }
        
        try {
          TabletResourceManager trm = resourceManager.createTabletResourceManager();
          
          // this opens the tablet file and fills in the endKey in the
          // extent
          tablet = new Tablet(TabletServer.this, locationToOpen, extentToOpen, trm, tabletsKeyValues);
          /*
           * If a minor compaction starts after a tablet opens, this indicates a log recovery occurred. This recovered data must be minor compacted.
           * 
           * There are three reasons to wait for this minor compaction to finish before placing the tablet in online tablets.
           * 
           * 1) The log recovery code does not handle data written to the tablet on multiple tablet servers. 2) The log recovery code does not block if memory
           * is full. Therefore recovering lots of tablets that use a lot of memory could run out of memory. 3) The minor compaction finish event did not make
           * it to the logs (the file will be in !METADATA, preventing replay of compacted data)... but do not want a majc to wipe the file out from !METADATA
           * and then have another process failure... this could cause duplicate data to replay
           */
          if (tablet.getNumEntriesInMemory() > 0 && !tablet.minorCompactNow()) {
            throw new RuntimeException("Minor compaction after recovery fails for " + extentToOpen);
          }
          
          Assignment assignment = new Assignment(extentToOpen, getTabletSession());
          TabletStateStore.setLocation(assignment);

          synchronized (openingTablets) {
            synchronized (onlineTablets) {
              openingTablets.remove(extentToOpen);
              onlineTablets.put(extentToOpen, tablet);
              openingTablets.notifyAll();
              recentlyUnloadedCache.remove(tablet);
            }
          }
          tablet = null; // release this reference
          successful = true;
        } catch (Throwable e) {
          log.warn("exception trying to assign tablet " + extentToOpen + " " + locationToOpen, e);
          if (e.getMessage() != null)
            log.warn(e.getMessage());
          String table = extent.getTableId().toString();
          ProblemReports.getInstance().report(new ProblemReport(table, TABLET_LOAD, extentToOpen.getUUID().toString(), getClientAddressString(), e));
        }
        
        if (!successful) {
          synchronized (unopenedTablets) {
            synchronized (openingTablets) {
              openingTablets.remove(extentToOpen);
              unopenedTablets.add(extentToOpen);
              openingTablets.notifyAll();
            }
          }
          log.warn("failed to open tablet " + extentToOpen + " reporting failure to master");
          enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.LOAD_FAILURE, extentToOpen));
          long reschedule = Math.min((1l << Math.min(32, retryAttempt)) * 1000, 10 * 60 * 1000l);
          log.warn(String.format("rescheduling tablet load in %.2f seconds", reschedule / 1000.));
          SimpleTimer.getInstance().schedule(new TimerTask() {
            @Override
            public void run() {
              log.info("adding tablet " + extent + " back to the assignment pool (retry " + retryAttempt + ")");
              AssignmentHandler handler = new AssignmentHandler(extentToOpen, retryAttempt + 1);
              if (isMetaDataTablet) {
                if (Constants.ROOT_TABLET_EXTENT.equals(extent)) {
                  new Thread(new LoggingRunnable(log, handler), "Root tablet assignment retry").start();
                } else {
                  resourceManager.addMetaDataAssignment(handler);
                }
              } else {
                resourceManager.addAssignment(handler);
              }
            }
          }, reschedule);
        } else {
          enqueueMasterMessage(new TabletStatusMessage(TabletLoadState.LOADED, extentToOpen));
        }
      }
    }
  }
  
  private FileSystem fs;
  private Configuration conf;
  private ZooCache cache;
  
  private SortedMap<KeyExtent,Tablet> onlineTablets = Collections.synchronizedSortedMap(new TreeMap<KeyExtent,Tablet>());
  private SortedSet<KeyExtent> unopenedTablets = Collections.synchronizedSortedSet(new TreeSet<KeyExtent>());
  private SortedSet<KeyExtent> openingTablets = Collections.synchronizedSortedSet(new TreeSet<KeyExtent>());
  @SuppressWarnings("unchecked")
  private Map<KeyExtent,Long> recentlyUnloadedCache = (Map<KeyExtent, Long>)Collections.synchronizedMap(new LRUMap(1000));
  
  private Thread majorCompactorThread;
  
  // used for stopping the server and MasterListener thread
  private volatile boolean serverStopRequested = false;
  
  private InetSocketAddress clientAddress;
  
  private TabletServerResourceManager resourceManager;
  private Authenticator authenticator;
  private volatile boolean majorCompactorDisabled = false;
  
  private volatile boolean shutdownComplete = false;
  
  private ZooLock tabletServerLock;
  
  private TServer server;
  
  private DistributedWorkQueue bulkFailedCopyQ;
  
  private static final String METRICS_PREFIX = "tserver";
  
  private static ObjectName OBJECT_NAME = null;
  
  public TabletStatsKeeper getStatsKeeper() {
    return statsKeeper;
  }
  
  public Set<String> getLoggers() throws TException, MasterNotRunningException, ThriftSecurityException {
    Set<String> allLoggers = new HashSet<String>();
    String dir = ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZLOGGERS;
    for (String child : cache.getChildren(dir)) {
      allLoggers.add(new String(cache.get(dir + "/" + child)));
    }
    if (allLoggers.isEmpty()) {
      log.warn("there are no loggers registered in zookeeper");
      return allLoggers;
    }
    Set<String> result = loggerStrategy.getLoggers(Collections.unmodifiableSet(allLoggers));
    Set<String> bogus = new HashSet<String>(result);
    bogus.removeAll(allLoggers);
    if (!bogus.isEmpty())
      log.warn("logger strategy is returning loggers that are not candidates");
    result.removeAll(bogus);
    if (result.isEmpty())
      log.warn("strategy returned no useful loggers");
    return result;
  }
  
  public void addLoggersToMetadata(List<RemoteLogger> logs, KeyExtent extent, int id) {
    log.info("Adding " + logs.size() + " logs for extent " + extent + " as alias " + id);
    if (!this.onlineTablets.containsKey(extent)) {
      // minor compaction due to recovery... don't make updates... if it finishes, there will be no WALs,
      // if it doesn't, we'll need to do the same recovery with the old files.
      return;
    }
    
    List<MetadataTable.LogEntry> entries = new ArrayList<MetadataTable.LogEntry>();
    long now = RelativeTime.currentTimeMillis();
    List<String> logSet = new ArrayList<String>();
    for (RemoteLogger log : logs)
      logSet.add(log.toString());
    for (RemoteLogger log : logs) {
      MetadataTable.LogEntry entry = new MetadataTable.LogEntry();
      entry.extent = extent;
      entry.tabletId = id;
      entry.timestamp = now;
      entry.server = log.getLogger();
      entry.filename = log.getFileName();
      entry.logSet = logSet;
      entries.add(entry);
    }
    MetadataTable.addLogEntries(SecurityConstants.getSystemCredentials(), entries, getLock());
  }
  
  private int startServer(Property portHint, TProcessor processor, String threadName) throws UnknownHostException {
    ServerPort sp = TServerUtils.startServer(portHint, processor, this.getClass().getSimpleName(), threadName, Property.TSERV_PORTSEARCH,
        Property.TSERV_MINTHREADS, Property.TSERV_THREADCHECK);
    this.server = sp.server;
    return sp.port;
  }
  
  private String getMasterAddress() {
    try {
      List<String> locations = HdfsZooInstance.getInstance().getMasterLocations();
      if (locations.size() == 0)
        return null;
      return locations.get(0);
    } catch (Exception e) {
      log.warn("Failed to obtain master host " + e);
    }
    
    return null;
  }
  
  // Connect to the master for posting asynchronous results
  private MasterClientService.Iface masterConnection(String address) {
    try {
      if (address == null) {
        return null;
      }
      MasterClientService.Iface client = ThriftUtil.getClient(new MasterClientService.Client.Factory(), address, Property.MASTER_CLIENTPORT,
          Property.GENERAL_RPC_TIMEOUT, ServerConfiguration.getSystemConfiguration());
      // log.info("Listener API to master has been opened");
      return client;
    } catch (Exception e) {
      log.warn("Issue with masterConnection (" + address + ") " + e, e);
    }
    return null;
  }
  
  private void returnMasterConnection(MasterClientService.Iface client) {
    ThriftUtil.returnClient(client);
  }
  
  private int startTabletClientService() throws UnknownHostException {
    // start listening for client connection last
    TabletClientService.Iface tch = TraceWrap.service(new ThriftClientHandler());
    TabletClientService.Processor processor = new TabletClientService.Processor(tch);
    int port = startServer(Property.TSERV_CLIENTPORT, processor, "Thrift Client Server");
    log.info("port = " + port);
    return port;
  }
  
  ZooLock getLock() {
    return tabletServerLock;
  }
  
  private void announceExistence() {
    IZooReaderWriter zoo = ZooReaderWriter.getInstance();
    try {
      String zPath = ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZTSERVERS + "/" + getClientAddressString();
      
      zoo.putPersistentData(zPath, new byte[] {}, NodeExistsPolicy.SKIP);
      
      tabletServerLock = new ZooLock(zPath);
      
      LockWatcher lw = new LockWatcher() {
        
        @Override
        public void lostLock(final LockLossReason reason) {
          Halt.halt(0, new Runnable() {
            public void run() {
              if (!serverStopRequested)
                log.fatal("Lost tablet server lock (reason = " + reason + "), exiting.");
              logGCInfo();
            }
          });
        }
        
        @Override
        public void unableToMonitorLockNode(final Throwable e) {
          Halt.halt(0, new Runnable() {
            @Override
            public void run() {
              log.fatal("Lost ability to monitor tablet server lock, exiting.", e);
            }
          });
          
        }
      };
      
      byte[] lockContent = new ServerServices(getClientAddressString(), Service.TSERV_CLIENT).toString().getBytes();
      for (int i = 0; i < 120 / 5; i++) {
        zoo.putPersistentData(zPath, new byte[0], NodeExistsPolicy.SKIP);
        
        if (tabletServerLock.tryLock(lw, lockContent)) {
          log.debug("Obtained tablet server lock " + tabletServerLock.getLockPath());
          return;
        }
        log.info("Waiting for tablet server lock");
        UtilWaitThread.sleep(5000);
      }
      String msg = "Too many retries, exiting.";
      log.info(msg);
      throw new RuntimeException(msg);
    } catch (Exception e) {
      log.info("Could not obtain tablet server lock, exiting.", e);
      throw new RuntimeException(e);
    }
  }
  
  // main loop listens for client requests
  public void run() {
    SecurityUtil.serverLogin();

    int clientPort = 0;
    try {
      clientPort = startTabletClientService();
    } catch (UnknownHostException e1) {
      log.error("Unable to start tablet client service", e1);
      UtilWaitThread.sleep(1000);
    }
    if (clientPort == 0) {
      throw new RuntimeException("Failed to start the tablet client service");
    }
    clientAddress = new InetSocketAddress(clientAddress.getAddress(), clientPort);
    announceExistence();
    
    ThreadPoolExecutor distWorkQThreadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(
        ServerConfiguration.getSystemConfiguration().getCount(Property.TSERV_WORKQ_THREADS),
        new NamingThreadFactory("distributed work queue"));

    bulkFailedCopyQ = new DistributedWorkQueue(Constants.ZROOT + "/" + HdfsZooInstance.getInstance().getInstanceID() + Constants.ZBULK_FAILED_COPYQ);
    try {
      bulkFailedCopyQ.startProcessing(new BulkFailedCopyProcessor(), distWorkQThreadPool);
    } catch (Exception e1) {
      throw new RuntimeException("Failed to start distributed work queue for copying ", e1);
    }

    try {
      OBJECT_NAME = new ObjectName("accumulo.server.metrics:service=TServerInfo,name=TabletServerMBean,instance=" + Thread.currentThread().getName());
      // Do this because interface not in same package.
      StandardMBean mbean = new StandardMBean(this, TabletServerMBean.class, false);
      this.register(mbean);
      mincMetrics.register();
    } catch (Exception e) {
      log.error("Error registering with JMX", e);
    }
    
    String masterHost;
    while (!serverStopRequested) {
      // send all of the pending messages
      try {
        MasterMessage mm = null;
        MasterClientService.Iface iface = null;
        
        try {
          // wait until a message is ready to send, or a sever stop
          // was requested
          while (mm == null && !serverStopRequested) {
            mm = masterMessages.poll(1000, TimeUnit.MILLISECONDS);
          }
          
          // have a message to send to the master, so grab a
          // connection
          masterHost = getMasterAddress();
          iface = masterConnection(masterHost);
          TServiceClient client = (TServiceClient) iface;
          
          // if while loop does not execute at all and mm != null,
          // then
          // finally block should place mm back on queue
          while (!serverStopRequested && mm != null && client != null && client.getOutputProtocol() != null
              && client.getOutputProtocol().getTransport() != null && client.getOutputProtocol().getTransport().isOpen()) {
            try {
              mm.send(SecurityConstants.getSystemCredentials(), getClientAddressString(), iface);
              mm = null;
            } catch (TException ex) {
              log.warn("Error sending message: queuing message again");
              masterMessages.putFirst(mm);
              mm = null;
              throw ex;
            }
            
            // if any messages are immediately available grab em and
            // send them
            mm = masterMessages.poll();
          }
          
        } finally {
          
          if (mm != null) {
            masterMessages.putFirst(mm);
          }
          returnMasterConnection(iface);
          
          UtilWaitThread.sleep(1000);
        }
      } catch (InterruptedException e) {
        log.info("Interrupt Exception received, shutting down");
        serverStopRequested = true;
        
      } catch (Exception e) {
        // may have lost connection with master
        // loop back to the beginning and wait for a new one
        // this way we survive master failures
        log.error(getClientAddressString() + ": TServerInfo: Exception. Master down?", e);
      }
    }
    
    // wait for shutdown
    // if the main thread exits oldServer the master listener, the JVM will
    // kill the
    // other threads and finalize objects. We want the shutdown that is
    // running
    // in the master listener thread to complete oldServer this happens.
    // consider making other threads daemon threads so that objects don't
    // get prematurely finalized
    synchronized (this) {
      while (shutdownComplete == false) {
        try {
          this.wait(1000);
        } catch (InterruptedException e) {
          log.error(e.toString());
        }
      }
    }
    log.debug("Stopping Thrift Servers");
    TServerUtils.stopTServer(server);
    
    try {
      log.debug("Closing filesystem");
      fs.close();
    } catch (IOException e) {
      log.warn("Failed to close filesystem : " + e.getMessage(), e);
    }
    
    logGCInfo();
    
    log.info("TServerInfo: stop requested. exiting ... ");
    
    try {
      tabletServerLock.unlock();
    } catch (Exception e) {
      log.warn("Failed to release tablet server lock", e);
    }
  }
  
  private long totalMinorCompactions;
  
  public static SortedMap<KeyExtent,Text> verifyTabletInformation(KeyExtent extent, TServerInstance instance, SortedMap<Key,Value> tabletsKeyValues,
      String clientAddress, ZooLock lock) throws AccumuloSecurityException, DistributedStoreException {
    for (int tries = 0; tries < 3; tries++) {
      try {
        log.debug("verifying extent " + extent);
        if (extent.equals(Constants.ROOT_TABLET_EXTENT)) {
          ZooTabletStateStore store = new ZooTabletStateStore();
          if (!store.iterator().hasNext()) {
            log.warn("Illegal state: location is not set in zookeeper");
            return null;
          }
          TabletLocationState next = store.iterator().next();
          if (!instance.equals(next.future)) {
            log.warn("Future location is not to this server for the root tablet");
            return null;
          }
          TreeMap<KeyExtent,Text> set = new TreeMap<KeyExtent,Text>();
          set.put(extent, new Text(Constants.ZROOT_TABLET));
          return set;
        }
        
        List<ColumnFQ> columnsToFetch = Arrays.asList(new ColumnFQ[] {Constants.METADATA_DIRECTORY_COLUMN, Constants.METADATA_PREV_ROW_COLUMN,
            Constants.METADATA_SPLIT_RATIO_COLUMN, Constants.METADATA_OLD_PREV_ROW_COLUMN, Constants.METADATA_TIME_COLUMN});
        
        if (tabletsKeyValues == null) {
          tabletsKeyValues = new TreeMap<Key,Value>();
        }
        MetadataTable.getTabletAndPrevTabletKeyValues(tabletsKeyValues, extent, null, SecurityConstants.getSystemCredentials());
        
        SortedMap<Text,SortedMap<ColumnFQ,Value>> tabletEntries;
        tabletEntries = MetadataTable.getTabletEntries(tabletsKeyValues, columnsToFetch);
        
        if (tabletEntries.size() == 0) {
          log.warn("Failed to find any metadata entries for " + extent);
          return null;
        }
        
        // ensure lst key in map is same as extent that was passed in
        if (!tabletEntries.lastKey().equals(extent.getMetadataEntry())) {
          log.warn("Failed to find metadata entry for " + extent + " found " + tabletEntries.lastKey());
          return null;
        }
        
        TServerInstance future = null;
        Text metadataEntry = extent.getMetadataEntry();
        for (Entry<Key,Value> entry : tabletsKeyValues.entrySet()) {
          Key key = entry.getKey();
          if (!metadataEntry.equals(key.getRow()))
            continue;
          Text cf = key.getColumnFamily();
          if (cf.equals(Constants.METADATA_FUTURE_LOCATION_COLUMN_FAMILY)) {
            future = new TServerInstance(entry.getValue(), key.getColumnQualifier());
          } else if (cf.equals(Constants.METADATA_CURRENT_LOCATION_COLUMN_FAMILY)) {
            log.error("Tablet seems to be already assigned to " + new TServerInstance(entry.getValue(), key.getColumnQualifier()));
            return null;
          }
        }
        if (future == null) {
          log.warn("The master has not assigned " + extent + " to " + instance);
          return null;
        }
        if (!instance.equals(future)) {
          log.warn("Table " + extent + " has been assigned to " + future + " which is not " + instance);
          return null;
        }
        
        // look for incomplete splits
        int splitsFixed = 0;
        for (Entry<Text,SortedMap<ColumnFQ,Value>> entry : tabletEntries.entrySet()) {
          
          if (extent.getPrevEndRow() != null) {
            Text prevRowMetadataEntry = new Text(KeyExtent.getMetadataEntry(extent.getTableId(), extent.getPrevEndRow()));
            if (entry.getKey().compareTo(prevRowMetadataEntry) <= 0) {
              continue;
            }
          }
          
          if (entry.getValue().containsKey(Constants.METADATA_OLD_PREV_ROW_COLUMN)) {
            KeyExtent fixedke = MetadataTable.fixSplit(entry.getKey(), entry.getValue(), instance, SecurityConstants.getSystemCredentials(), lock);
            if (fixedke != null) {
              if (fixedke.getPrevEndRow() == null || fixedke.getPrevEndRow().compareTo(extent.getPrevEndRow()) < 0) {
                extent = new KeyExtent(extent);
                extent.setPrevEndRow(fixedke.getPrevEndRow());
              }
              splitsFixed++;
            }
          }
        }
        
        if (splitsFixed > 0) {
          // reread and reverify metadata entries now that metadata
          // entries were fixed
          tabletsKeyValues.clear();
          return verifyTabletInformation(extent, instance, null, clientAddress, lock);
        }
        
        SortedMap<KeyExtent,Text> children = new TreeMap<KeyExtent,Text>();
        
        for (Entry<Text,SortedMap<ColumnFQ,Value>> entry : tabletEntries.entrySet()) {
          if (extent.getPrevEndRow() != null) {
            Text prevRowMetadataEntry = new Text(KeyExtent.getMetadataEntry(extent.getTableId(), extent.getPrevEndRow()));
            
            if (entry.getKey().compareTo(prevRowMetadataEntry) <= 0) {
              continue;
            }
          }
          
          Value prevEndRowIBW = entry.getValue().get(Constants.METADATA_PREV_ROW_COLUMN);
          if (prevEndRowIBW == null) {
            log.warn("Metadata entry does not have prev row (" + entry.getKey() + ")");
            return null;
          }
          
          Value dirIBW = entry.getValue().get(Constants.METADATA_DIRECTORY_COLUMN);
          if (dirIBW == null) {
            log.warn("Metadata entry does not have directory (" + entry.getKey() + ")");
            return null;
          }
          
          Text dir = new Text(dirIBW.get());
          
          KeyExtent child = new KeyExtent(entry.getKey(), prevEndRowIBW);
          children.put(child, dir);
        }
        
        if (!MetadataTable.isContiguousRange(extent, new TreeSet<KeyExtent>(children.keySet()))) {
          log.warn("For extent " + extent + " metadata entries " + children + " do not form a contiguous range.");
          return null;
        }
        
        return children;
      } catch (AccumuloException e) {
        log.error("error verifying metadata information. retrying ...");
        log.error(e.toString());
        UtilWaitThread.sleep(1000);
      } catch (AccumuloSecurityException e) {
        // if it's a security exception, retrying won't work either.
        log.error(e.toString());
        throw e;
      }
    }
    // default is to accept
    return null;
  }
  
  public String getClientAddressString() {
    if (clientAddress == null)
      return null;
    return AddressUtil.toString(clientAddress);
  }
  
  TServerInstance getTabletSession() {
    String address = getClientAddressString();
    if (address == null)
      return null;
    
    try {
      return new TServerInstance(address, tabletServerLock.getSessionId());
    } catch (Exception ex) {
      log.warn("Unable to read session from tablet server lock" + ex);
      return null;
    }
  }
  
  public void config(String[] args) throws UnknownHostException {
    InetAddress local = Accumulo.getLocalAddress(args);
    
    try {
      Accumulo.init("tserver");
      log.info("Tablet server starting on " + local.getHostAddress());
      
      conf = CachedConfiguration.getInstance();
      fs = TraceFileSystem.wrap(FileUtil.getFileSystem(conf, ServerConfiguration.getSiteConfiguration()));
      
      authenticator = ZKAuthenticator.getInstance();
      
      if (args.length > 0)
        conf.set("tabletserver.hostname", args[0]);
      Accumulo.enableTracing(local.getHostName(), "tserver");
    } catch (IOException e) {
      log.fatal("couldn't get a reference to the filesystem. quitting");
      throw new RuntimeException(e);
    }
    clientAddress = new InetSocketAddress(local, 0);
    logger = new TabletServerLogger(this, ServerConfiguration.getSystemConfiguration().getMemoryInBytes(Property.TSERV_WALOG_MAX_SIZE));
    
    if (ServerConfiguration.getSystemConfiguration().getBoolean(Property.TSERV_LOCK_MEMORY)) {
      String path = "lib/native/mlock/" + System.mapLibraryName("MLock-" + Platform.getPlatform());
      path = new File(path).getAbsolutePath();
      try {
        System.load(path);
        log.info("Trying to lock memory pages to RAM");
        if (MLock.lockMemoryPages() < 0)
          log.error("Failed to lock memory pages to RAM");
        else
          log.info("Memory pages are now locked into RAM");
      } catch (Throwable t) {
        log.error("Failed to load native library for locking pages to RAM " + path + " (" + t + ")", t);
      }
    }
    
    FileSystemMonitor.start(Property.TSERV_MONITOR_FS);
    
    TimerTask gcDebugTask = new TimerTask() {
      @Override
      public void run() {
        logGCInfo();
      }
    };
    
    SimpleTimer.getInstance().schedule(gcDebugTask, 0, 1000);
    
    this.resourceManager = new TabletServerResourceManager(conf, fs);
    
    lastPingTime = System.currentTimeMillis();
    
    currentMaster = null;
    
    statsKeeper = new TabletStatsKeeper();
    
    // start major compactor
    majorCompactorThread = new Daemon(new LoggingRunnable(log, new MajorCompactor()));
    majorCompactorThread.setName("Split/MajC initiator");
    majorCompactorThread.start();
    
    String className = ServerConfiguration.getSystemConfiguration().get(Property.TSERV_LOGGER_STRATEGY);
    Class<? extends LoggerStrategy> klass = DEFAULT_LOGGER_STRATEGY;
    try {
      klass = AccumuloClassLoader.loadClass(className, LoggerStrategy.class);
    } catch (Exception ex) {
      log.warn("Unable to load class " + className + " for logger strategy, using " + klass.getName(), ex);
    }
    try {
      Constructor<? extends LoggerStrategy> constructor = klass.getConstructor(TabletServer.class);
      loggerStrategy = constructor.newInstance(this);
    } catch (Exception ex) {
      log.warn("Unable to create object of type " + klass.getName() + " using " + DEFAULT_LOGGER_STRATEGY.getName());
    }
    if (loggerStrategy == null) {
      try {
        loggerStrategy = DEFAULT_LOGGER_STRATEGY.getConstructor(TabletServer.class).newInstance(this);
      } catch (Exception ex) {
        log.fatal("Programmer error: cannot create a logger strategy.");
        throw new RuntimeException(ex);
      }
    }
    cache = new ZooCache();
    
  }
  
  public TabletServerStatus getStats(Map<String,MapCounter<ScanRunState>> scanCounts) {
    TabletServerStatus result = new TabletServerStatus();
    
    Map<KeyExtent,Tablet> onlineTabletsCopy;
    synchronized (this.onlineTablets) {
      onlineTabletsCopy = new HashMap<KeyExtent,Tablet>(this.onlineTablets);
    }
    Map<String,TableInfo> tables = new HashMap<String,TableInfo>();
    for (Entry<KeyExtent,Tablet> entry : onlineTabletsCopy.entrySet()) {
      String tableId = entry.getKey().getTableId().toString();
      TableInfo table = tables.get(tableId);
      if (table == null) {
        table = new TableInfo();
        table.minor = new Compacting();
        table.major = new Compacting();
        tables.put(tableId, table);
      }
      Tablet tablet = entry.getValue();
      long recs = tablet.getNumEntries();
      table.tablets++;
      table.onlineTablets++;
      table.recs += recs;
      table.queryRate += tablet.queryRate();
      table.queryByteRate += tablet.queryByteRate();
      table.ingestRate += tablet.ingestRate();
      table.ingestByteRate += tablet.ingestByteRate();
      long recsInMemory = tablet.getNumEntriesInMemory();
      table.recsInMemory += recsInMemory;
      if (tablet.minorCompactionRunning())
        table.minor.running++;
      if (tablet.minorCompactionQueued())
        table.minor.queued++;
      if (tablet.majorCompactionRunning())
        table.major.running++;
      if (tablet.majorCompactionQueued())
        table.major.queued++;
    }
    
    for (Entry<String,MapCounter<ScanRunState>> entry : scanCounts.entrySet()) {
      TableInfo table = tables.get(entry.getKey());
      if (table == null) {
        table = new TableInfo();
        tables.put(entry.getKey(), table);
      }
      
      if (table.scans == null)
        table.scans = new Compacting();
      
      table.scans.queued += entry.getValue().get(ScanRunState.QUEUED);
      table.scans.running += entry.getValue().get(ScanRunState.RUNNING);
    }
    
    ArrayList<KeyExtent> offlineTabletsCopy = new ArrayList<KeyExtent>();
    synchronized (this.unopenedTablets) {
      synchronized (this.openingTablets) {
        offlineTabletsCopy.addAll(this.unopenedTablets);
        offlineTabletsCopy.addAll(this.openingTablets);
      }
    }
    
    for (KeyExtent extent : offlineTabletsCopy) {
      String tableId = extent.getTableId().toString();
      TableInfo table = tables.get(tableId);
      if (table == null) {
        table = new TableInfo();
        tables.put(tableId, table);
      }
      table.tablets++;
    }
    
    result.lastContact = RelativeTime.currentTimeMillis();
    result.tableMap = tables;
    result.osLoad = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    result.name = getClientAddressString();
    result.holdTime = resourceManager.holdTime();
    result.lookups = scanCount.get();
    result.loggers = new HashSet<String>();
    result.indexCacheHits = resourceManager.getIndexCache().getStats().getHitCount();
    result.indexCacheRequest = resourceManager.getIndexCache().getStats().getRequestCount();
    result.dataCacheHits = resourceManager.getDataCache().getStats().getHitCount();
    result.dataCacheRequest = resourceManager.getDataCache().getStats().getRequestCount();
    logger.getLoggers(result.loggers);
    return result;
  }
  
  public static void main(String[] args) throws IOException {
    try {
      SecurityUtil.serverLogin();
      
      TabletServer server = new TabletServer();
      server.config(args);
      server.run();
    } catch (Exception ex) {
      log.error("Uncaught exception in TabletServer.main, exiting", ex);
    }
  }
  
  public void minorCompactionFinished(CommitSession tablet, String newDatafile, int walogSeq) throws IOException {
    totalMinorCompactions++;
    logger.minorCompactionFinished(tablet, newDatafile, walogSeq);
  }
  
  public void minorCompactionStarted(CommitSession tablet, int lastUpdateSequence, String newMapfileLocation) throws IOException {
    logger.minorCompactionStarted(tablet, lastUpdateSequence, newMapfileLocation);
  }
  
  public void recover(Tablet tablet, List<LogEntry> logEntries, Set<String> tabletFiles, MutationReceiver mutationReceiver) throws IOException {
    List<String> recoveryLogs = new ArrayList<String>();
    List<LogEntry> sorted = new ArrayList<LogEntry>(logEntries);
    Collections.sort(sorted, new Comparator<LogEntry>() {
      @Override
      public int compare(LogEntry e1, LogEntry e2) {
        return (int) (e1.timestamp - e2.timestamp);
      }
    });
    for (LogEntry entry : sorted) {
      String recovery = null;
      for (String log : entry.logSet) {
        String[] parts = log.split("/"); // "host:port/filename"
        log = ServerConstants.getRecoveryDir() + "/" + parts[1] + ".recovered";
        Path finished = new Path(log + "/finished");
        TabletServer.log.info("Looking for " + finished);
        if (fs.exists(finished)) {
          recovery = log;
          break;
        }
      }
      if (recovery == null)
        throw new IOException("Unable to find recovery files for extent " + tablet.getExtent() + " logEntry: " + entry);
      recoveryLogs.add(recovery);
    }
    logger.recover(tablet, recoveryLogs, tabletFiles, mutationReceiver);
  }
  
  private final AtomicInteger logIdGenerator = new AtomicInteger();
  
  public int createLogId(KeyExtent tablet) {
    String table = tablet.getTableId().toString();
    AccumuloConfiguration acuTableConf = ServerConfiguration.getTableConfiguration(table);
    if (acuTableConf.getBoolean(Property.TABLE_WALOG_ENABLED)) {
      return logIdGenerator.incrementAndGet();
    }
    return -1;
  }
  
  // / JMX methods
  
  @Override
  public long getEntries() {
    if (this.isEnabled()) {
      long result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        result += tablet.getNumEntries();
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public long getEntriesInMemory() {
    if (this.isEnabled()) {
      long result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        result += tablet.getNumEntriesInMemory();
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public long getIngest() {
    if (this.isEnabled()) {
      long result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        result += tablet.getNumEntriesInMemory();
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getMajorCompactions() {
    if (this.isEnabled()) {
      int result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        if (tablet.majorCompactionRunning())
          result++;
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getMajorCompactionsQueued() {
    if (this.isEnabled()) {
      int result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        if (tablet.majorCompactionQueued())
          result++;
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getMinorCompactions() {
    if (this.isEnabled()) {
      int result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        if (tablet.minorCompactionRunning())
          result++;
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getMinorCompactionsQueued() {
    if (this.isEnabled()) {
      int result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        if (tablet.minorCompactionQueued())
          result++;
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getOnlineCount() {
    if (this.isEnabled())
      return onlineTablets.size();
    return 0;
  }
  
  @Override
  public int getOpeningCount() {
    if (this.isEnabled())
      return openingTablets.size();
    return 0;
  }
  
  @Override
  public long getQueries() {
    if (this.isEnabled()) {
      long result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        result += tablet.totalQueries();
      }
      return result;
    }
    return 0;
  }
  
  @Override
  public int getUnopenedCount() {
    if (this.isEnabled())
      return unopenedTablets.size();
    return 0;
  }
  
  @Override
  public String getName() {
    if (this.isEnabled())
      return getClientAddressString();
    return "";
  }
  
  @Override
  public long getTotalMinorCompactions() {
    if (this.isEnabled())
      return totalMinorCompactions;
    return 0;
  }
  
  @Override
  public double getHoldTime() {
    if (this.isEnabled())
      return this.resourceManager.holdTime() / 1000.;
    return 0;
  }
  
  @Override
  public double getAverageFilesPerTablet() {
    if (this.isEnabled()) {
      int count = 0;
      long result = 0;
      for (Tablet tablet : Collections.unmodifiableCollection(onlineTablets.values())) {
        result += tablet.getDatafiles().size();
        count++;
      }
      if (count == 0)
        return 0;
      return result / (double) count;
    }
    return 0;
  }
  
  protected ObjectName getObjectName() {
    return OBJECT_NAME;
  }
  
  protected String getMetricsPrefix() {
    return METRICS_PREFIX;
  }
  
}
