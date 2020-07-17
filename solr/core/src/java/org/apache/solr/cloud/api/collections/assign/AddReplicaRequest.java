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

package org.apache.solr.cloud.api.collections.assign;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 */
public class AddReplicaRequest extends BaseAssignRequest {
  private final ReplicaType type;
  private final Map<String, Object> params = new HashMap<>();
  private final String targetNode;
  private final Set<String> nodeSet;

  public AddReplicaRequest(String collection, String shard, ReplicaType type, Map<String, Object> params,
                           String targetNode, Set<String> nodeSet) {
    super(collection, shard);
    this.type = type;
    if (params != null) {
      this.params.putAll(params);
    }
    this.targetNode = targetNode;
    this.nodeSet = nodeSet;
    Objects.requireNonNull(this.type, "'type' must not be null");
  }

  public ReplicaType getType() {
    return type;
  }

  public Map<String, Object> getParams() {
    return params;
  }

  // impls may request a specific target node
  public String getTargetNode() {
    return targetNode;
  }

  // subset of live nodes to consider as valid targets, or null
  public Set<String> getNodeSet() {
    return nodeSet;
  }
}
