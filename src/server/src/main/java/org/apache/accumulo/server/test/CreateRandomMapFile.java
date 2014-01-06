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
package org.apache.accumulo.server.test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.map.MyMapFile;
import org.apache.accumulo.core.file.map.MyMapFile.Writer;
import org.apache.accumulo.core.file.map.MySequenceFile.CompressionType;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;

/**
 * @deprecated since 1.4 (will become CreateRandomRFile)
 */
@Deprecated
public class CreateRandomMapFile {
  private static int num;
  private static String file;
  
  public static byte[] createValue(long rowid, int dataSize) {
    Random r = new Random(rowid);
    byte value[] = new byte[dataSize];
    
    r.nextBytes(value);
    
    // transform to printable chars
    for (int j = 0; j < value.length; j++) {
      value[j] = (byte) (((0xff & value[j]) % 92) + ' ');
    }
    
    return value;
  }
  
  public static void main(String[] args) {
    file = args[0];
    num = Integer.parseInt(args[1]);
    long rands[] = new long[num];
    
    Random r = new Random();
    
    for (int i = 0; i < rands.length; i++) {
      rands[i] = Math.abs(r.nextLong()) % 10000000000l;
    }
    
    Arrays.sort(rands);
    
    Configuration conf = CachedConfiguration.getInstance();
    Writer mfw;
    try {
      FileSystem fs = FileSystem.get(conf);
      mfw = new MyMapFile.Writer(conf, fs, file, Key.class, Value.class, CompressionType.BLOCK);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    
    for (int i = 0; i < rands.length; i++) {
      Text row = new Text(String.format("row_%010d", rands[i]));
      Key key = new Key(row);
      
      Value dv = new Value(createValue(rands[i], 40));
      
      try {
        mfw.append(key, dv);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    
    try {
      mfw.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
}
