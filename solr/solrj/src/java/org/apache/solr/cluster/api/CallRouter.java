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
package org.apache.solr.cluster.api;

/**
 * Provide information to route a call to an appropriate node/core
 */
public interface CallRouter {
    /**
     * send to a specific node. usually admin requests
     */
    CallRouter toNode(String nodeName);

    /**
     * Make a request to any replica of the shard of type
     */
    CallRouter toShard(String collection, String shard, ReplicaType type);

    /**
     * Identify the shard using the route key and send the request to a given replica type
     */
    CallRouter toShard(String collection, ReplicaType type, String routeKey);

    /**
     * Make a request to a specific replica
     */
    CallRouter toReplica(String collection, String replicaName);

    /**
     * To any Solr node  that may host this collection
     */
    CallRouter toCollection(String collection);

    /**
     * Make a call dirctly to a specific core in a node
     */
    CallRouter toCore(String node, String core);

    HttpRpc createHttpRpc();

    enum ReplicaType {
        LEADER, NRT, TLOG, PULL, NON_LEADER, ANY
    }
}
