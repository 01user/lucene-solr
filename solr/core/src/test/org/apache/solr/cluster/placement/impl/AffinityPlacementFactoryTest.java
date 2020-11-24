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

package org.apache.solr.cluster.placement.impl;

import org.apache.solr.cluster.Cluster;
import org.apache.solr.cluster.Node;
import org.apache.solr.cluster.Replica;
import org.apache.solr.cluster.Shard;
import org.apache.solr.cluster.SolrCollection;
import org.apache.solr.cluster.placement.*;
import org.apache.solr.cluster.placement.plugins.AffinityPlacementFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Unit test for {@link AffinityPlacementFactory}
 */
public class AffinityPlacementFactoryTest extends Assert {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static PlacementPlugin plugin;

    @BeforeClass
    public static void setupPlugin() {
        PlacementPluginConfig config = PlacementPluginConfigImpl.createConfigFromProperties(
                Map.of("minimalFreeDiskGB", 10L, "deprioritizedFreeDiskGB", 50L));
        plugin = new AffinityPlacementFactory().createPluginInstance(config);
    }

    @Test
    public void testBasicPlacementNewCollection() throws Exception {
        testBasicPlacementInternal(false);
    }

    @Test
    public void testBasicPlacementExistingCollection() throws Exception {
        testBasicPlacementInternal(true);
    }

    @Test
    public void testBasicPlacementNewCollection2() throws Exception {
        testBasicInternal2(false);
    }

    @Test
    public void testBasicPlacementExistingCollection2() throws Exception {
        testBasicInternal2(true);
    }

    private void testBasicInternal2(boolean hasExistingCollection) throws Exception {
        String collectionName = "testCollection";

        Builders.ClusterBuilder clusterBuilder = Builders.newClusterBuilder().initializeNodes(2);
        LinkedList<Builders.NodeBuilder> nodeBuilders = clusterBuilder.getNodeBuilders();
        nodeBuilders.get(0).setCoreCount(1).setFreeDiskGB(100L);
        nodeBuilders.get(1).setCoreCount(10).setFreeDiskGB(100L);

        Builders.CollectionBuilder collectionBuilder = Builders.newCollectionBuilder(collectionName);

        if (hasExistingCollection) {
            // Existing collection has replicas for its shards and is visible in the cluster state
            collectionBuilder.initializeShardsReplicas(1, 1, 0, 0, nodeBuilders);
            clusterBuilder.addCollection(collectionBuilder);
        } else {
            // New collection to create has the shards defined but no replicas and is not present in cluster state
            collectionBuilder.initializeShardsReplicas(1, 0, 0, 0, List.of());
        }

        Cluster cluster = clusterBuilder.build();
        AttributeFetcher attributeFetcher = clusterBuilder.buildAttributeFetcher();

        SolrCollection solrCollection = collectionBuilder.build();
        List<Node> liveNodes = clusterBuilder.buildLiveNodes();

        // Place a new replica for the (only) existing shard of the collection
        PlacementRequestImpl placementRequest = new PlacementRequestImpl(solrCollection,
                Set.of(solrCollection.shards().iterator().next().getShardName()), new HashSet<>(liveNodes),
                1, 0, 0);

        PlacementPlan pp = plugin.computePlacement(cluster, placementRequest, attributeFetcher, new PlacementPlanFactoryImpl());

        assertEquals(1, pp.getReplicaPlacements().size());
        ReplicaPlacement rp = pp.getReplicaPlacements().iterator().next();
        assertEquals(hasExistingCollection ? liveNodes.get(1) : liveNodes.get(0), rp.getNode());
    }

    /**
     * When this test places a replica for a new collection, it should pick the node with less cores.<p>
     *
     * When it places a replica for an existing collection, it should pick the node with more cores that doesn't already have a replica for the shard.
     */
    private void testBasicPlacementInternal(boolean hasExistingCollection) throws Exception {
        String collectionName = "testCollection";

        Node node1 = new ClusterAbstractionsForTest.NodeImpl("node1");
        Node node2 = new ClusterAbstractionsForTest.NodeImpl("node2");
        Set<Node> liveNodes = Set.of(node1, node2);

        ClusterAbstractionsForTest.SolrCollectionImpl solrCollection;
        // Make sure new collections are not visible in the cluster state and existing ones are
        final Map<String, SolrCollection> clusterCollections;
        if (hasExistingCollection) {
            // An existing collection with a single replica on node 1. Note that new collections already exist by the time the plugin is called, but are empty
            solrCollection = PluginTestHelper.createCollection(collectionName, Map.of(), 1, 1, 0, 0, Set.of(node1));
            clusterCollections = Map.of(solrCollection.getName(), solrCollection);
        } else {
            // A new collection has the shards defined ok but no replicas
            solrCollection = PluginTestHelper.createCollection(collectionName, Map.of(), 1, 0, 0, 0, Set.of());
            clusterCollections = Map.of();
        }

        Cluster cluster = new ClusterAbstractionsForTest.ClusterImpl(liveNodes, clusterCollections);
        // Place a new replica for the (only) existing shard of the collection
        PlacementRequestImpl placementRequest = new PlacementRequestImpl(solrCollection, Set.of(solrCollection.shards().iterator().next().getShardName()), liveNodes, 1, 0, 0);
        // More cores on node2
        Map<Node, Integer> nodeToCoreCount = Map.of(node1, 1, node2, 10);
        // A lot of free disk on the two nodes
        final Map<Node, Long> nodeToFreeDisk = Map.of(node1, 100L, node2, 100L);
        AttributeValues attributeValues = new AttributeValuesImpl(nodeToCoreCount, Map.of(), nodeToFreeDisk, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        AttributeFetcher attributeFetcher = new AttributeFetcherForTest(attributeValues);
        PlacementPlanFactory placementPlanFactory = new PlacementPlanFactoryImpl();

        PlacementPlan pp = plugin.computePlacement(cluster, placementRequest, attributeFetcher, placementPlanFactory);


        assertEquals(1, pp.getReplicaPlacements().size());
        ReplicaPlacement rp = pp.getReplicaPlacements().iterator().next();
        assertEquals(hasExistingCollection ? node2 : node1, rp.getNode());
    }

    @Test
    public void testAvailabilityZones() throws Exception {
        String collectionName = "testCollection";
        int NUM_NODES = 6;
        Builders.ClusterBuilder clusterBuilder = Builders.newClusterBuilder().initializeNodes(NUM_NODES);
        for (int i = 0; i < NUM_NODES; i++) {
            Builders.NodeBuilder nodeBuilder = clusterBuilder.getNodeBuilders().get(i);
            nodeBuilder.setCoreCount(0);
            nodeBuilder.setFreeDiskGB(100L);
            if (i < NUM_NODES / 2) {
                nodeBuilder.setSysprop(AffinityPlacementFactory.AVAILABILITY_ZONE_SYSPROP, "az1");
            } else {
                nodeBuilder.setSysprop(AffinityPlacementFactory.AVAILABILITY_ZONE_SYSPROP, "az2");
            }
        }

        Builders.CollectionBuilder collectionBuilder = Builders.newCollectionBuilder(collectionName);
        collectionBuilder.initializeShardsReplicas(2, 0, 0, 0, clusterBuilder.getNodeBuilders());
        clusterBuilder.addCollection(collectionBuilder);

        Cluster cluster = clusterBuilder.build();

        SolrCollection solrCollection = cluster.getCollection(collectionName);

        PlacementRequestImpl placementRequest = new PlacementRequestImpl(solrCollection,
            StreamSupport.stream(solrCollection.shards().spliterator(), false)
                 .map(Shard::getShardName).collect(Collectors.toSet()),
            cluster.getLiveNodes(), 2, 2, 2);

        PlacementPlanFactory placementPlanFactory = new PlacementPlanFactoryImpl();
        AttributeFetcher attributeFetcher = clusterBuilder.buildAttributeFetcher();
        PlacementPlan pp = plugin.computePlacement(cluster, placementRequest, attributeFetcher, placementPlanFactory);
        // 2 shards, 6 replicas
        assertEquals(12, pp.getReplicaPlacements().size());
//        List<ReplicaPlacement> placements = new ArrayList<>(pp.getReplicaPlacements());
//        Collections.sort(placements, Comparator
//            .comparing((ReplicaPlacement p) -> p.getNode().getName())
//            .thenComparing((ReplicaPlacement p) -> p.getShardName())
//            .thenComparing((ReplicaPlacement p) -> p.getReplicaType())
//        );
        // shard -> AZ -> replica count
        Map<Replica.ReplicaType, Map<String, Map<String, AtomicInteger>>> replicas = new HashMap<>();
        AttributeValues attributeValues = attributeFetcher.fetchAttributes();
        for (ReplicaPlacement rp : pp.getReplicaPlacements()) {
            Optional<String> azOptional = attributeValues.getSystemProperty(rp.getNode(), AffinityPlacementFactory.AVAILABILITY_ZONE_SYSPROP);
            if (!azOptional.isPresent()) {
                fail("missing AZ sysprop for node " + rp.getNode());
            }
            String az = azOptional.get();
            replicas.computeIfAbsent(rp.getReplicaType(), type -> new HashMap<>())
                .computeIfAbsent(rp.getShardName(), shard -> new HashMap<>())
                .computeIfAbsent(az, zone -> new AtomicInteger()).incrementAndGet();
        }
        replicas.forEach((type, perTypeReplicas) -> {
            perTypeReplicas.forEach((shard, azCounts) -> {
                assertEquals("number of AZs", 2, azCounts.size());
                azCounts.forEach((az, count) -> {
                    assertTrue("too few replicas shard=" + shard + ", type=" + type + ", az=" + az,
                        count.get() >= 1);
                });
            });
        });
    }

    @Test
    //@Ignore
    public void testScalability() throws Exception {
        log.info("==== numNodes ====");
        runTestScalability(1000, 100, 40, 40, 20);
        runTestScalability(2000, 100, 40, 40, 20);
        runTestScalability(5000, 100, 40, 40, 20);
        runTestScalability(10000, 100, 40, 40, 20);
        runTestScalability(20000, 100, 40, 40, 20);
        log.info("==== numShards ====");
        runTestScalability(5000, 100, 40, 40, 20);
        runTestScalability(5000, 200, 40, 40, 20);
        runTestScalability(5000, 500, 40, 40, 20);
        runTestScalability(5000, 1000, 40, 40, 20);
        runTestScalability(5000, 2000, 40, 40, 20);
        log.info("==== numReplicas ====");
        runTestScalability(5000, 100, 100, 0, 0);
        runTestScalability(5000, 100, 200, 0, 0);
        runTestScalability(5000, 100, 500, 0, 0);
        runTestScalability(5000, 100, 1000, 0, 0);
        runTestScalability(5000, 100, 2000, 0, 0);
    }

    private void runTestScalability(int numNodes, int numShards,
                                    int nrtReplicas, int tlogReplicas,
                                    int pullReplicas) throws Exception {

        int REPLICAS_PER_SHARD = nrtReplicas + tlogReplicas + pullReplicas;
        int TOTAL_REPLICAS = numShards * REPLICAS_PER_SHARD;

        String collectionName = "testCollection";

        final Set<Node> liveNodes = new HashSet<>();
        final Map<Node, Long> nodeToFreeDisk = new HashMap<>();
        final Map<Node, Integer> nodeToCoreCount = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            Node node = new ClusterAbstractionsForTest.NodeImpl("node_" + i);
            liveNodes.add(node);
            nodeToFreeDisk.put(node, Long.valueOf(numNodes));
            nodeToCoreCount.put(node, 0);
        }
        ClusterAbstractionsForTest.SolrCollectionImpl solrCollection =
            PluginTestHelper.createCollection(collectionName, Map.of(), numShards, 0, 0, 0, Set.of());

        Cluster cluster = new ClusterAbstractionsForTest.ClusterImpl(liveNodes, Map.of());
        PlacementRequestImpl placementRequest = new PlacementRequestImpl(solrCollection,
            // XXX awkward!
            // StreamSupport.stream(solrCollection.shards().spliterator(), false)
            //     .map(Shard::getShardName).collect(Collectors.toSet()),
            solrCollection.getShardNames(),
            liveNodes, nrtReplicas, tlogReplicas, pullReplicas);

        AttributeValues attributeValues = new AttributeValuesImpl(nodeToCoreCount, Map.of(), nodeToFreeDisk, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        AttributeFetcher attributeFetcher = new AttributeFetcherForTest(attributeValues);
        PlacementPlanFactory placementPlanFactory = new PlacementPlanFactoryImpl();

        long start = System.nanoTime();
        PlacementPlan pp = plugin.computePlacement(cluster, placementRequest, attributeFetcher, placementPlanFactory);
        long end = System.nanoTime();
        log.info("ComputePlacement: {} nodes, {} shards, {} total replicas, elapsed time {} ms.", numNodes, numShards, TOTAL_REPLICAS, TimeUnit.NANOSECONDS.toMillis(end - start)); //nowarn
        assertEquals("incorrect number of calculated placements", TOTAL_REPLICAS,
            pp.getReplicaPlacements().size());
        // check that replicas are correctly placed
        Map<Node, AtomicInteger> replicasPerNode = new HashMap<>();
        Map<Node, Set<String>> shardsPerNode = new HashMap<>();
        Map<String, AtomicInteger> replicasPerShard = new HashMap<>();
        Map<Replica.ReplicaType, AtomicInteger> replicasByType = new HashMap<>();
        for (ReplicaPlacement placement : pp.getReplicaPlacements()) {
            replicasPerNode.computeIfAbsent(placement.getNode(), n -> new AtomicInteger()).incrementAndGet();
            shardsPerNode.computeIfAbsent(placement.getNode(), n -> new HashSet<>()).add(placement.getShardName());
            replicasByType.computeIfAbsent(placement.getReplicaType(), t -> new AtomicInteger()).incrementAndGet();
            replicasPerShard.computeIfAbsent(placement.getShardName(), s -> new AtomicInteger()).incrementAndGet();
        }
        int perNode = TOTAL_REPLICAS > numNodes ? TOTAL_REPLICAS / numNodes : 1;
        replicasPerNode.forEach((node, count) -> {
            assertEquals(count.get(), perNode);
        });
        shardsPerNode.forEach((node, names) -> {
            assertEquals(names.size(), perNode);
        });

        replicasPerShard.forEach((shard, count) -> {
            assertEquals(count.get(), REPLICAS_PER_SHARD);
        });
    }
}
