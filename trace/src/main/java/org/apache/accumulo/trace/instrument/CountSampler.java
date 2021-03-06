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
package org.apache.accumulo.trace.instrument;

import java.util.Collections;

import org.apache.htrace.HTraceConfiguration;

/**
 * @deprecated since 1.7, use org.apache.htrace.impl.CountSampler instead
 */
@Deprecated
public class CountSampler extends org.apache.htrace.impl.CountSampler implements Sampler {
  public CountSampler(long frequency) {
    super(HTraceConfiguration.fromMap(Collections
        .singletonMap(CountSampler.SAMPLER_FREQUENCY_CONF_KEY, Long.toString(frequency))));
  }

  @Override
  public boolean next() {
    return super.next(null);
  }
}
