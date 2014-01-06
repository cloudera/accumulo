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
package org.apache.accumulo.core.conf;

import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.log4j.Logger;

public abstract class AccumuloConfiguration implements Iterable<Entry<String,String>> {
  private static final Logger log = Logger.getLogger(AccumuloConfiguration.class);
  
  public abstract String get(Property property);
  
  public abstract Iterator<Entry<String,String>> iterator();
  
  private void checkType(Property property, PropertyType type) {
    if (!property.getType().equals(type)) {
      String msg = "Configuration method intended for type " + type + " called with a " + property.getType() + " argument (" + property.getKey() + ")";
      IllegalArgumentException err = new IllegalArgumentException(msg);
      log.error(msg, err);
      throw err;
    }
  }
  
  public long getMemoryInBytes(Property property) {
    checkType(property, PropertyType.MEMORY);
    
    String memString = get(property);
    return getMemoryInBytes(memString);
  }
  
  static public long getMemoryInBytes(String str) {
    int multiplier = 0;
    switch (str.charAt(str.length() - 1)) {
      case 'G':
        multiplier += 10;
      case 'M':
        multiplier += 10;
      case 'K':
        multiplier += 10;
      case 'B':
        return Long.parseLong(str.substring(0, str.length() - 1)) << multiplier;
      default:
        return Long.parseLong(str);
    }
  }
  
  public long getTimeInMillis(Property property) {
    checkType(property, PropertyType.TIMEDURATION);
    
    return getTimeInMillis(get(property));
  }
  
  static public long getTimeInMillis(String str) {
    int multiplier = 1;
    switch (str.charAt(str.length() - 1)) {
      case 'd':
        multiplier *= 24;
      case 'h':
        multiplier *= 60;
      case 'm':
        multiplier *= 60;
      case 's':
        multiplier *= 1000;
        if (str.length() > 1 && str.endsWith("ms")) // millis
          // case
          return Long.parseLong(str.substring(0, str.length() - 2));
        return Long.parseLong(str.substring(0, str.length() - 1)) * multiplier;
      default:
        return Long.parseLong(str) * 1000;
    }
  }
  
  public boolean getBoolean(Property property) {
    checkType(property, PropertyType.BOOLEAN);
    return Boolean.parseBoolean(get(property));
  }
  
  public double getFraction(Property property) {
    checkType(property, PropertyType.FRACTION);
    
    return getFraction(get(property));
  }
  
  public double getFraction(String str) {
    if (str.charAt(str.length() - 1) == '%')
      return Double.parseDouble(str.substring(0, str.length() - 1)) / 100.0;
    return Double.parseDouble(str);
  }
  
  public int getPort(Property property) {
    checkType(property, PropertyType.PORT);
    
    String portString = get(property);
    int port = Integer.parseInt(portString);
    if (port != 0) {
      if (port < 1024 || port > 65535) {
        log.error("Invalid port number " + port + "; Using default " + property.getDefaultValue());
        port = Integer.parseInt(property.getDefaultValue());
      }
    }
    return port;
  }
  
  public int getCount(Property property) {
    checkType(property, PropertyType.COUNT);
    
    String countString = get(property);
    return Integer.parseInt(countString);
  }
  
  public static synchronized DefaultConfiguration getDefaultConfiguration() {
    return DefaultConfiguration.getInstance();
  }
  
  // Only here for Shell option-free start-up
  /**
   * 
   * @deprecated not for client use
   */
  @Deprecated
  public static synchronized AccumuloConfiguration getSiteConfiguration() {
    return SiteConfiguration.getInstance(getDefaultConfiguration());
  }
  
  public static AccumuloConfiguration getTableConfiguration(Connector conn, String tableId) throws TableNotFoundException, AccumuloException {
    String tableName = Tables.getTableName(conn.getInstance(), tableId);
    return new ConfigurationCopy(conn.tableOperations().getProperties(tableName));
  }
  
  public int getMaxFilesPerTablet() {
    int maxFilesPerTablet = getCount(Property.TABLE_FILE_MAX);
    if (maxFilesPerTablet <= 0) {
      maxFilesPerTablet = getCount(Property.TSERV_SCAN_MAX_OPENFILES) - 1;
      log.debug("Max files per tablet " + maxFilesPerTablet);
    }
    
    return maxFilesPerTablet;
  }
}
