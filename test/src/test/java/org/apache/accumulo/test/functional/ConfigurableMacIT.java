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
package org.apache.accumulo.test.functional;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.util.MonitorUtil;
import org.apache.accumulo.minicluster.impl.MiniAccumuloClusterImpl;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;

public class ConfigurableMacIT extends AbstractMacIT {
  public static final Logger log = Logger.getLogger(ConfigurableMacIT.class);

  public MiniAccumuloClusterImpl cluster;

  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {}

  public void beforeClusterStart(MiniAccumuloConfigImpl cfg) throws Exception {}

  @Before
  public void setUp() throws Exception {
    MiniAccumuloConfigImpl cfg = new MiniAccumuloConfigImpl(
        createTestDir(this.getClass().getName() + "_" + this.testName.getMethodName()), ROOT_PASSWORD);
    cfg.setNativeLibPaths(NativeMapIT.nativeMapLocation().getAbsolutePath());
    Configuration coreSite = new Configuration(false);
    configure(cfg, coreSite);
    cfg.setProperty(Property.TSERV_NATIVEMAP_ENABLED, Boolean.TRUE.toString());
    configureForEnvironment(cfg, getClass(), createSharedTestDir(this.getClass().getName() + "-ssl"));
    cluster = new MiniAccumuloClusterImpl(cfg);
    if (coreSite.size() > 0) {
      File csFile = new File(cluster.getConfig().getConfDir(), "core-site.xml");
      if (csFile.exists())
        throw new RuntimeException(csFile + " already exist");

      OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(cluster.getConfig().getConfDir(), "core-site.xml")));
      coreSite.writeXml(out);
      out.close();
    }
    beforeClusterStart(cfg);
    cluster.start();
  }

  @After
  public void tearDown() throws Exception {
    cleanUp(cluster);
  }

  public MiniAccumuloClusterImpl getCluster() {
    return cluster;
  }

  @Override
  public Connector getConnector() throws AccumuloException, AccumuloSecurityException {
    return getCluster().getConnector("root", ROOT_PASSWORD);
  }

  public Process exec(Class<?> clazz, String... args) throws IOException {
    return getCluster().exec(clazz, args);
  }

  @Override
  public String rootPath() {
    return getCluster().getConfig().getDir().getAbsolutePath();
  }

  public String getMonitor() throws KeeperException, InterruptedException {
    Instance instance = new ZooKeeperInstance(getCluster().getClientConfig());
    return MonitorUtil.getLocation(instance);
  }

}
