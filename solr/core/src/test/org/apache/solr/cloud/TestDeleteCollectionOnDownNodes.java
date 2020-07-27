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

package org.apache.solr.cloud;

import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore // nocommit debugging
public class TestDeleteCollectionOnDownNodes extends SolrCloudTestCase {

  @BeforeClass
  public static void beforeTestDeleteCollectionOnDownNodes() throws Exception {
    configureCluster(4)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
  }

  @Test
  public void deleteCollectionWithDownNodes() throws Exception {

    CollectionAdminRequest.createCollection("halfdeletedcollection2", "conf", 4, 3)
        .setMaxShardsPerNode(20)
        .process(cluster.getSolrClient());

    // stop a couple nodes
    JettySolrRunner j1 = cluster.stopJettySolrRunner(cluster.getRandomJetty(random()));
    JettySolrRunner j2 = cluster.stopJettySolrRunner(cluster.getRandomJetty(random()));


    // delete the collection
    CollectionAdminRequest.deleteCollection("halfdeletedcollection2").process(cluster.getSolrClient());

    assertFalse("Still found collection that should be gone",
        cluster.getSolrClient().getZkStateReader().getClusterState().hasCollection("halfdeletedcollection2"));
  }
}
