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
package org.apache.solr.cluster.placement;

import java.util.function.Function;

/**
 * Metric-related attribute of a node or replica. It defines a short symbolic name of the metric, the corresponding
 * internal metric name and the desired format/unit conversion. Generic type
 * defines the type of converted values of this attribute.
 */
public interface MetricAttribute<T> {

  /**
   * Return the short-hand name that identifies this attribute.
   */
  String getName();

  /**
   * Return the internal name of a Solr metric associated with this attribute.
   */
  String getInternalName();

  /**
   * Conversion function to convert formats/units of raw values.
   */
  Function<Object, T> getConverter();

  /**
   * Convert raw value. This may involve changing value type or units.
   * Default implementation simply applies the converter function
   * returned by {@link #getConverter()}.
   * @param value raw value
   * @return converted value
   */
  default T convert(Object value) {
    return getConverter().apply(value);
  }
}
