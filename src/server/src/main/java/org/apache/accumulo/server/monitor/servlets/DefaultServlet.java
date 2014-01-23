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
package org.apache.accumulo.server.monitor.servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.file.FileUtil;
import org.apache.accumulo.core.master.thrift.MasterMonitorInfo;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.Duration;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.monitor.Monitor;
import org.apache.accumulo.server.monitor.ZooKeeperStatus;
import org.apache.accumulo.server.monitor.ZooKeeperStatus.ZooKeeperState;
import org.apache.accumulo.server.monitor.util.celltypes.NumberType;
import org.apache.accumulo.server.trace.TraceFileSystem;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class DefaultServlet extends BasicServlet {
  
  private static final long serialVersionUID = 1L;
  
  @Override
  protected String getTitle(HttpServletRequest req) {
    return req.getRequestURI().startsWith("/docs") ? "Documentation" : "Accumulo Overview";
  }
  
  private void getResource(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String path = req.getRequestURI();
      
      if (path.endsWith(".jpg"))
        resp.setContentType("image/jpeg");
      
      if (path.endsWith(".html"))
        resp.setContentType("text/html");
      
      path = path.substring(1);
      InputStream data = BasicServlet.class.getClassLoader().getResourceAsStream(path);
      ServletOutputStream out = resp.getOutputStream();
      try {
        if (data != null) {
          byte[] buffer = new byte[1024];
          int n;
          while ((n = data.read(buffer)) > 0)
            out.write(buffer, 0, n);
        } else {
          out.write(("could not get resource " + path + "").getBytes());
        }
      } finally {
        data.close();
      }
    } catch (Throwable t) {
      log.error(t, t);
      throw new IOException(t);
    }
  }
  
  private void getDocResource(HttpServletRequest req, final HttpServletResponse resp) throws IOException {
    final String path = req.getRequestURI();
    if (path.endsWith(".html"))
      resp.setContentType("text/html");
    
    // Allow user to only read any file in docs directory
    final String aHome = System.getenv("ACCUMULO_HOME");
    PermissionCollection pc = new Permissions();
    pc.add(new FilePermission(aHome + "/docs/-", "read"));
    
    AccessControlContext acc = new AccessControlContext(new ProtectionDomain[] {new ProtectionDomain(null, pc)});
    
    IOException e = AccessController.doPrivileged(new PrivilegedAction<IOException>() {
      
      @Override
      public IOException run() {
        InputStream data = null;
        try {
          File file = new File(aHome + path);
          data = new FileInputStream(file.getAbsolutePath());
          byte[] buffer = new byte[1024];
          int n;
          ServletOutputStream out = resp.getOutputStream();
          while ((n = data.read(buffer)) > 0)
            out.write(buffer, 0, n);
          return null;
        } catch (IOException e) {
          return e;
        } finally {
          if (data != null) {
            try {
              data.close();
            } catch (IOException ex) {
              log.error(ex, ex);
            }
          }
        }
      }
    }, acc);
    
    if (e != null)
      throw e;
  }
  
  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/web"))
      getResource(req, resp);
    else if (req.getRequestURI().equals("/docs") || req.getRequestURI().equals("/docs/apidocs"))
      super.doGet(req, resp);
    else if (req.getRequestURI().startsWith("/docs"))
      getDocResource(req, resp);
    else if (req.getRequestURI().startsWith("/monitor"))
      resp.sendRedirect("/master");
    else if (req.getRequestURI().startsWith("/errors"))
      resp.sendRedirect("/problems");
    else
      super.doGet(req, resp);
  }
  
  public static final int GRAPH_WIDTH = 450;
  public static final int GRAPH_HEIGHT = 150;
  
  @SuppressWarnings("unchecked")
  private static void plotData(StringBuilder sb, String title, @SuppressWarnings("rawtypes") List data, boolean points) {
    sb.append("<div class=\"plotHeading\">");
    sb.append(title);
    sb.append("</div>");
    sb.append("</br>");
    String id = "c" + title.hashCode();
    sb.append("<div id=\"" + id + "\" style=\"width:" + GRAPH_WIDTH + "px;height:" + GRAPH_HEIGHT + "px;\"></div>\n");
    
    sb.append("<script type=\"text/javascript\">\n");
    sb.append("$(function () {\n");
    sb.append("    var d1 = [");
    
    String sep = "";
    for (Pair<Long,? extends Number> point : (List<Pair<Long,? extends Number>>) data) {
      if (point.getSecond() == null)
        continue;
      
      String y;
      if (point.getSecond() instanceof Double)
        y = String.format("%1.2f", point.getSecond());
      else
        y = point.getSecond().toString();
      
      sb.append(sep);
      sep = ",";
      sb.append("[" + point.getFirst() + "," + y + "]");
    }
    
    String opts = "lines: { show: true }";
    if (points)
      opts = "points: { show: true, radius: 1 }";
    
    sb.append("    ];\n");
    sb.append("    $.plot($(\"#" + id + "\"), [{ data: d1, " + opts
        + ", color:\"red\" }], {yaxis:{}, xaxis:{mode:\"time\",minTickSize: [1, \"minute\"],timeformat: \"%H:%M\", ticks:3}});");
    sb.append("   });\n");
    sb.append("</script>\n");
  }
  
  @Override
  protected void pageBody(HttpServletRequest req, HttpServletResponse resp, StringBuilder sb) throws IOException {
    if (req.getRequestURI().equals("/docs") || req.getRequestURI().equals("/docs/apidocs")) {
      sb.append("<object data='").append(req.getRequestURI()).append("/index.html' type='text/html' width='100%' height='100%'></object>");
      return;
    }
    
    sb.append("<table class='noborder'>\n");
    sb.append("<tr>\n");
    
    sb.append("<td class='noborder'>\n");
    doAccumuloTable(sb);
    sb.append("</td>\n");
    
    sb.append("<td class='noborder'>\n");
    doZooKeeperTable(sb);
    sb.append("</td>\n");
    
    sb.append("</tr></table>\n");
    sb.append("<br/>\n");
    
    sb.append("<p/><table class=\"noborder\">\n");
    
    sb.append("<tr><td>\n");
    plotData(sb, "Ingest (Entries/s)", Monitor.getIngestRateOverTime(), false);
    sb.append("</td><td>\n");
    plotData(sb, "Scan (Entries/s)", Monitor.getQueryRateOverTime(), false);
    sb.append("</td></tr>\n");
    
    sb.append("<tr><td>\n");
    plotData(sb, "Ingest (MB/s)", Monitor.getIngestByteRateOverTime(), false);
    sb.append("</td><td>\n");
    plotData(sb, "Scan (MB/s)", Monitor.getQueryByteRateOverTime(), false);
    sb.append("</td></tr>\n");
    
    sb.append("<tr><td>\n");
    plotData(sb, "Load Average", Monitor.getLoadOverTime(), false);
    sb.append("</td><td>\n");
    plotData(sb, "Scan Sessions", Monitor.getLookupsOverTime(), false);
    sb.append("</td></tr>\n");
    
    sb.append("<tr><td>\n");
    plotData(sb, "Minor Compactions", Monitor.getMinorCompactionsOverTime(), false);
    sb.append("</td><td>\n");
    plotData(sb, "Major Compactions", Monitor.getMajorCompactionsOverTime(), false);
    sb.append("</td></tr>\n");
    
    sb.append("<tr><td>\n");
    plotData(sb, "Index Cache Hit Rate", Monitor.getIndexCacheHitRateOverTime(), true);
    sb.append("</td><td>\n");
    plotData(sb, "Data Cache Hit Rate", Monitor.getDataCacheHitRateOverTime(), true);
    sb.append("</td></tr>\n");
    
    sb.append("</table>\n");
  }
  
  private void doAccumuloTable(StringBuilder sb) throws IOException {
    // Accumulo
    Configuration conf = CachedConfiguration.getInstance();
    FileSystem fs = TraceFileSystem.wrap(FileUtil.getFileSystem(conf, ServerConfiguration.getSiteConfiguration()));
    MasterMonitorInfo info = Monitor.getMmi();
    sb.append("<table>\n");
    sb.append("<tr><th colspan='2'><a href='/master'>Accumulo Master</a></th></tr>\n");
    if (info == null) {
      sb.append("<tr><td colspan='2'><span class='error'>Master is Down</span></td></tr>\n");
    } else {
      String consumed = "Unknown";
      String diskUsed = "Unknown";
      try {
        Path path = new Path(ServerConfiguration.getSystemConfiguration().get(Property.INSTANCE_DFS_DIR));
        log.debug("Reading the content summary for " + path);
        try {
          ContentSummary acu = fs.getContentSummary(path);
          diskUsed = bytes(acu.getSpaceConsumed());
          ContentSummary rootSummary = fs.getContentSummary(new Path("/"));
          consumed = String.format("%.2f%%", acu.getSpaceConsumed() * 100. / rootSummary.getSpaceConsumed());
        } catch (Exception ex) {
          log.trace("Unable to get disk usage information from hdfs", ex);
        }

        boolean highlight = false;
        tableRow(sb, (highlight = !highlight), "Disk&nbsp;Used", diskUsed);
        if (fs.getUsed() != 0)
          tableRow(sb, (highlight = !highlight), "%&nbsp;of&nbsp;Used&nbsp;DFS", consumed);
        tableRow(sb, (highlight = !highlight), "<a href='/tables'>Tables</a>", NumberType.commas(Monitor.getTotalTables()));
        tableRow(sb, (highlight = !highlight), "<a href='/tservers'>Tablet&nbsp;Servers</a>", NumberType.commas(info.tServerInfo.size(), 1, Long.MAX_VALUE));
        tableRow(sb, (highlight = !highlight), "<a href='/tservers'>Dead&nbsp;Tablet&nbsp;Servers</a>", NumberType.commas(info.deadTabletServers.size(), 0, 0));
        tableRow(sb, (highlight = !highlight), "Tablets", NumberType.commas(Monitor.getTotalTabletCount(), 1, Long.MAX_VALUE));
        tableRow(sb, (highlight = !highlight), "Entries", NumberType.commas(Monitor.getTotalEntries()));
        tableRow(sb, (highlight = !highlight), "Lookups", NumberType.commas(Monitor.getTotalLookups()));
        tableRow(sb, (highlight = !highlight), "Uptime", Duration.format(System.currentTimeMillis() - Monitor.getStartTime()));
      } catch (Exception e) {
        log.debug(e, e);
      }
    }
    sb.append("</table>\n");
  }
  
  private void doZooKeeperTable(StringBuilder sb) throws IOException {
    // Zookeepers
    sb.append("<table>\n");
    sb.append("<tr><th colspan='3'>Zookeeper</th></tr>\n");
    sb.append("<tr><th>Server</th><th>Mode</th><th>Clients</th></tr>\n");
    
    boolean highlight = false;
    for (ZooKeeperState k : ZooKeeperStatus.getZooKeeperStatus()) {
      if (k.clients >= 0) {
        tableRow(sb, (highlight = !highlight), k.keeper, k.mode, k.clients);
      } else {
        tableRow(sb, false, k.keeper, "<span class='error'>Down</span>", "");
      }
    }
    sb.append("</table>\n");
  }
  
  private static final String BYTES[] = {"", "K", "M", "G", "T", "P", "E", "Z"};
  
  private static String bytes(long big) {
    return NumberType.bigNumber(big, BYTES, 1024);
  }
  
  public static void tableRow(StringBuilder sb, boolean highlight, Object... cells) {
    sb.append(highlight ? "<tr class='highlight'>" : "<tr>");
    for (int i = 0; i < cells.length; ++i) {
      Object cell = cells[i];
      String cellValue = cell == null ? "" : String.valueOf(cell).trim();
      sb.append("<td class='").append(i < cells.length - 1 ? "left" : "right").append("'>").append(cellValue.isEmpty() ? "-" : cellValue).append("</td>");
    }
    sb.append("</tr>\n");
  }
}
