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

package org.apache.solr.client.solrj.cloud;

import java.util.function.Predicate;

import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;

/**Reads the state from state.json
 *
 */
public class DirectShardState implements ShardStateProvider {

  private final Predicate<String> isNodeLive;

  public DirectShardState(Predicate<String> isNodeLive) {
    this.isNodeLive = isNodeLive;
  }

  @Override
  public Replica.State getState(Replica replica) {
    return replica.getState();
  }

  @Override
  public Replica getLeader(Slice slice) {
    return slice.getLeader();
  }

  @Override
  public boolean isActive(Replica replica) {
    return  replica.getNodeName() != null &&
        replica.getState() == Replica.State.ACTIVE &&
        isNodeLive.test(replica.getNodeName());
  }

  @Override
  public boolean isActive(Slice slice) {
    return slice.getState() == Slice.State.ACTIVE;
  }

  @Override
  public Replica getLeader(Slice slice, int timeout) throws InterruptedException {
    throw new RuntimeException("Not implemented");//TODO
  }

  @Override
  public Replica getLeader(String collection, String slice, int timeout) throws InterruptedException {
    throw new RuntimeException("Not implemented");
  }

}
