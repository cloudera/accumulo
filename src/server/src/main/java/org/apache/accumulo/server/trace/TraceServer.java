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
package org.apache.accumulo.server.trace;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.TimerTask;

import org.apache.accumulo.cloudtrace.instrument.Span;
import org.apache.accumulo.cloudtrace.thrift.RemoteSpan;
import org.apache.accumulo.cloudtrace.thrift.SpanReceiver;
import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.trace.TraceFormatter;
import org.apache.accumulo.core.util.AddressUtil;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.server.Accumulo;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.security.SecurityUtil;
import org.apache.accumulo.server.util.time.SimpleTimer;
import org.apache.accumulo.server.zookeeper.IZooReaderWriter;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.thrift.TByteArrayOutputStream;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;


public class TraceServer implements Watcher {
  
  final private static Logger log = Logger.getLogger(TraceServer.class);
  final private AccumuloConfiguration conf;
  final private TServer server;
  final private AtomicReference<BatchWriter> writer;
  final private Connector connector;
  final String table;

  private static void put(Mutation m, String cf, String cq, byte[] bytes, int len) {
    m.put(new Text(cf), new Text(cq), new Value(bytes, 0, len));
  }
  
  static class ByteArrayTransport extends TTransport {
    TByteArrayOutputStream out = new TByteArrayOutputStream();
    
    @Override
    public boolean isOpen() {
      return true;
    }
    
    @Override
    public void open() throws TTransportException {}
    
    @Override
    public void close() {}
    
    @Override
    public int read(byte[] buf, int off, int len) {
      return 0;
    }
    
    @Override
    public void write(byte[] buf, int off, int len) throws TTransportException {
      out.write(buf, off, len);
    }
    
    public byte[] get() {
      return out.get();
    }
    
    public int len() {
      return out.len();
    }
  }
  
  class Receiver implements SpanReceiver.Iface {
    @Override
    public void span(RemoteSpan s) throws TException {
      String idString = Long.toHexString(s.traceId);
      String startString = Long.toHexString(s.start);
      Mutation spanMutation = new Mutation(new Text(idString));
      Mutation indexMutation = new Mutation(new Text("idx:" + s.svc + ":" + startString));
      long diff = s.stop - s.start;
      indexMutation.put(new Text(s.description), new Text(s.sender), new Value((idString + ":" + Long.toHexString(diff)).getBytes()));
      ByteArrayTransport transport = new ByteArrayTransport();
      TCompactProtocol protocol = new TCompactProtocol(transport);
      s.write(protocol);
      String parentString = Long.toHexString(s.parentId);
      if (s.parentId == Span.ROOT_SPAN_ID)
        parentString = "";
      put(spanMutation, "span", parentString + ":" + Long.toHexString(s.spanId), transport.get(), transport.len());
      // Map the root span to time so we can look up traces by time
      Mutation timeMutation = null;
      if (s.parentId == Span.ROOT_SPAN_ID) {
        timeMutation = new Mutation(new Text("start:" + startString));
        put(timeMutation, "id", idString, transport.get(), transport.len());
      }
      try {
        final BatchWriter writer = TraceServer.this.writer.get();
        /* Check for null, because we expect spans to come in much faster than flush calls.
           In the case of failure, we'd rather avoid logging tons of NPEs.
         */
        if (null == writer) {
          log.warn("writer is not ready; discarding span.");
          return;
        }
        writer.addMutation(spanMutation);
        writer.addMutation(indexMutation);
        if (timeMutation != null)
          writer.addMutation(timeMutation);
      } catch (MutationsRejectedException exception) {
        log.warn("Unable to write mutation to table; discarding span. set log level to DEBUG for span information and stacktrace. cause: " + exception);
        if (log.isDebugEnabled()) {
          log.debug("discarded span due to rejection of mutation: " + spanMutation, exception);
        }
      /* XXX this could be e.g. an IllegalArgumentExceptoion if we're trying to write this mutation to a writer that has been closed since we retrieved it */
      } catch (RuntimeException exception) {
        log.warn("Unable to write mutation to table; discarding span. set log level to DEBUG for stacktrace. cause: " + exception);
        log.debug("unable to write mutation to table due to exception.", exception);
      }
    }
    
  }
  
  public TraceServer(String args[]) throws Exception {
    Accumulo.init("tracer");
    conf = ServerConfiguration.getSystemConfiguration();
    table = conf.get(Property.TRACE_TABLE);
    Connector connector = null;
    while (true) {
      try {
        connector = HdfsZooInstance.getInstance().getConnector(conf.get(Property.TRACE_USER), conf.get(Property.TRACE_PASSWORD).getBytes());
        if (!connector.tableOperations().exists(table)) {
          connector.tableOperations().create(table);
        }
        connector.tableOperations().setProperty(table, Property.TABLE_FORMATTER_CLASS.getKey(), TraceFormatter.class.getName());
        break;
      } catch (Exception ex) {
        log.info("Waiting to checking/create the trace table.", ex);
        UtilWaitThread.sleep(1000);
      }
    }
    this.connector = connector;
    // make sure we refer to the final variable from now on.
    connector = null;
    
    int port = conf.getPort(Property.TRACE_PORT);
    final ServerSocket sock = ServerSocketChannel.open().socket();
    sock.setReuseAddress(true);
    sock.bind(new InetSocketAddress(port));
    final TServerTransport transport = new TServerSocket(sock);
    TThreadPoolServer.Args options = new TThreadPoolServer.Args(transport);
    options.processor(new SpanReceiver.Processor(new Receiver()));
    server = new TThreadPoolServer(options);
    final InetSocketAddress address = new InetSocketAddress(Accumulo.getLocalAddress(args), sock.getLocalPort());
    registerInZooKeeper(AddressUtil.toString(address));
    writer = new AtomicReference<BatchWriter>(this.connector.createBatchWriter(table, 100l * 1024 * 1024, 5 * 1000l, 10));
  }
  
  public void run() throws Exception {
    SimpleTimer.getInstance().schedule(new TimerTask() {
      @Override
      public void run() {
        flush();
      }
    }, 1000, 1000);
    server.serve();
  }
  
  private void flush() {
    try {
      final BatchWriter writer = this.writer.get();
      if (null != writer) {
        writer.flush();
      }
    } catch (MutationsRejectedException exception) {
      log.warn("Problem flushing traces, resetting writer. Set log level to DEBUG to see stacktrace. cause: " + exception);
      log.debug("flushing traces failed due to exception", exception);
      resetWriter();
    /* XXX e.g. if the writer was closed between when we grabbed it and when we called flush. */
    } catch (RuntimeException exception) {
      log.warn("Problem flushing traces, resetting writer. Set log level to DEBUG to see stacktrace. cause: " + exception);
      log.debug("flushing traces failed due to exception", exception);
      resetWriter();
    }
  }
  
  private void resetWriter() {
    BatchWriter writer = null;
    try {
      writer = connector.createBatchWriter(table, 100l * 1024 * 1024, 5 * 1000l, 10);
    } catch (Exception ex) {
      log.warn("Unable to create a batch writer, will retry. Set log level to DEBUG to see stacktrace. cause: " + ex);
      log.debug("batch writer creation failed with exception.", ex);
    } finally {
      /* Trade in the new writer (even if null) for the one we need to close. */
      writer = this.writer.getAndSet(writer);
      try {
        if (null != writer) {
          writer.close();
        }
      } catch (Exception ex) {
        log.warn("Problem closing batch writer. Set log level to DEBUG to see stacktrace. cause: " + ex);
        log.debug("batch writer close failed with exception", ex);
      }
    }
  }
  

  private void registerInZooKeeper(String name) throws Exception {
    String root = ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZTRACERS;
    IZooReaderWriter zoo = ZooReaderWriter.getInstance();
    String path = zoo.putEphemeralSequential(root + "/trace-", name.getBytes());
    zoo.exists(path, this);
  }
  
  public static void main(String[] args) throws Exception {
    SecurityUtil.serverLogin();
    TraceServer server = new TraceServer(args);
    server.run();
    log.info("tracer stopping");
  }
  
  @Override
  public void process(WatchedEvent event) {
    log.debug("event " + event.getPath() + " " + event.getType() + " " + event.getState());
    if (event.getState() == KeeperState.Expired) {
      log.warn("Trace server lost zookeeper registration at " + event.getPath());
      server.stop();
    } else if (event.getType() == EventType.NodeDeleted) {
      log.warn("Trace server zookeeper entry lost " + event.getPath());
      server.stop();
    }
    if (event.getPath() != null) {
      try {
        if (ZooReaderWriter.getInstance().exists(event.getPath(), this))
          return;
      } catch (Exception ex) {
        log.error(ex, ex);
      }
      log.warn("Trace server unable to reset watch on zookeeper registration");
      server.stop();
    }
  }
  
}
