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

package org.apache.solr.client.solrj.cloud.autoscaling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.solr.common.IteratorWriter;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.util.ReflectMapWriter;

import static java.util.Collections.singletonMap;
import static org.apache.solr.client.solrj.cloud.autoscaling.Operand.RANGE_EQUAL;
import static org.apache.solr.client.solrj.cloud.autoscaling.Variable.Type.NODE;

public class PerClauseData implements ReflectMapWriter, Cloneable {
  @JsonProperty
  public Map<String, CollectionDetails> collections = new HashMap<>();

  static Function<Clause, ReplicaCount> NEW_CLAUSEVAL_FUN = c -> new ReplicaCount();

/*  ReplicaCount getClauseValue(String coll, String shard, Clause clause, String key) {
    Map<String, ReplicaCount> countMap = getCountsForClause(coll, shard, clause);
    return countMap.computeIfAbsent(key,NEW_CLAUSEVAL_FUN);
  }*/

  ReplicaCount getCountsForClause(String coll, String shard, Clause clause, Row row) {
    CollectionDetails cd = collections.get(coll);
    if (cd == null) collections.put(coll, cd = new CollectionDetails(coll));
    ShardDetails psd = null;
    if (shard != null && clause.dataGrouping == Clause.DataGrouping.SHARD) {
      psd = cd.shards.get(shard);
      if (psd == null) cd.shards.put(shard, psd = new ShardDetails(coll, shard));
    }
    Map<Clause,  Map<String, ReplicaCount>> map = (psd == null ? cd.clauseValues : psd.values);
    if(clause.tag.op.isSingleValue()) {
      Map<String, ReplicaCount> v = map.get(clause);
      if (v == null) {
        ReplicaCount result = new ReplicaCount();
        map.put(clause, v = singletonMap(SINGLEVALUE, result));
      }
      return v.get(SINGLEVALUE);

    } else {
      String clauseVal = String.valueOf(row.getVal(clause.tag.name));

      Map<String, ReplicaCount> v = map.get(clause);
      if(v == null){
        map.put(clause, v = new HashMap<>());
      }
      ReplicaCount  result = v.get(clauseVal);
      if(result == null) v.put(clauseVal, result = new ReplicaCount());
      return result;
    }
  }

  PerClauseData copy() {
    PerClauseData result = new PerClauseData();
    collections.forEach((s, v) -> result.collections.put(s, v.copy()));
    return result;
  }



  ShardDetails getShardDetails(String c, String s) {
    CollectionDetails cd = collections.get(c);
    if (cd == null) collections.put(c, cd = new CollectionDetails(c));
    ShardDetails sd = cd.shards.get(s);
    if (sd == null) cd.shards.put(s, sd = new ShardDetails(c, s));
    return sd;
  }


  public static class CollectionDetails implements MapWriter, Cloneable {

    public final String coll;
    public final Map<String, ShardDetails> shards = new HashMap<>();
    Map<Clause,  Map<String,  ReplicaCount>> clauseValues = new HashMap<>();

    @Override
    public void writeMap(EntryWriter ew) throws IOException {
      writeClauseVals(ew, clauseValues);
      shards.forEach(ew.getBiConsumer());
    }

    public static void writeClauseVals(EntryWriter ew, Map<Clause, Map<String, ReplicaCount>> clauseValues) throws IOException {
      ew.put("clauseValues", (IteratorWriter) iw -> clauseValues.forEach((clause, k_count) -> {
        iw.addNoEx(singletonMap( "clause", clause.original));
        iw.addNoEx( singletonMap("counts",k_count));
      }));
    }

    CollectionDetails copy() {
      CollectionDetails result = new CollectionDetails(coll);
      shards.forEach((k, shardDetails) -> result.shards.put(k, shardDetails.copy()));
      copyTo(clauseValues, result.clauseValues);
      return result;
    }

    CollectionDetails(String coll) {
      this.coll = coll;
    }
  }

  public static class ShardDetails implements MapWriter, Cloneable {
    final String coll;
    final String shard;
    Double indexSize;
    ReplicaCount replicas = new ReplicaCount();
    public Map<Clause,  Map<String, ReplicaCount>> values = new HashMap<>();

    @Override
    public void writeMap(EntryWriter ew) throws IOException {
      ew.putIfNotNull("indexSize", indexSize);
      ew.putIfNotNull("replicas", replicas);
      CollectionDetails.writeClauseVals(ew,values);
    }


    ShardDetails(String coll, String shard) {
      this.coll = coll;
      this.shard = shard;
    }


    ShardDetails copy() {
      ShardDetails result = new ShardDetails(coll, shard);
      result.indexSize = indexSize;
      result.replicas.increment(replicas);
      copyTo(values, result.values);
      return result;
    }

    public void incrReplicas(Replica.Type type, int delta) {
      replicas._change(type, delta);
    }
  }

  static void copyTo(Map<Clause, Map<String, ReplicaCount>> values, Map<Clause, Map<String, ReplicaCount>> sink) {
    values.forEach((clause, clauseVal) -> {
      HashMap<String, ReplicaCount> m = new HashMap(clauseVal);
      for (Map.Entry<String, ReplicaCount> e : m.entrySet()) e.setValue(e.getValue().copy());
      sink.put(clause, m);
    });
  }


  static class LazyViolation extends Violation {
    private Policy.Session session;
    private List<ReplicaInfoAndErr> lazyreplicaInfoAndErrs;

    LazyViolation(SealedClause clause, String coll, String shard, String node, Object actualVal, Double replicaCountDelta, Object tagKey, Policy.Session session) {
      super(clause, coll, shard, node, actualVal, replicaCountDelta, tagKey);
      this.session = session;
    }

    @Override
    public List<ReplicaInfoAndErr> getViolatingReplicas() {
      if (lazyreplicaInfoAndErrs == null) {
        populateReplicas();
      }
      return lazyreplicaInfoAndErrs;
    }

    private void populateReplicas() {
      lazyreplicaInfoAndErrs = new ArrayList<>();
      for (Row row : session.matrix) {
        if (node != null && !node.equals(row.node)) continue;
        if (getClause().getThirdTag().isPass(row)) {
          row.forEachReplica(coll, ri -> {
            if (shard == null || Policy.ANY.equals(shard) || Objects.equals(shard, ri.getShard()))
              lazyreplicaInfoAndErrs.add(new ReplicaInfoAndErr(ri));
          });

        }
      }
    }
  }

  public static final String SINGLEVALUE = "";
  private static final ReplicaCount EMPTY_COUNT = new ReplicaCount();
  private static final Map<String, ReplicaCount> EMPTY = singletonMap(SINGLEVALUE, EMPTY_COUNT);

  void getViolations(Map<Clause, Map<String, ReplicaCount>> vals,
                     List<Violation> violations,
                     Clause.ComputedValueEvaluator evaluator,
                     Clause clause, double[] deviations) {
    Map<String, ReplicaCount> rc = vals.get(clause);
    if (rc == null) rc = EMPTY;
    SealedClause sc = clause.getSealedClause(evaluator);
    if (clause.getThirdTag().varType == Variable.Type.NODE && clause.getThirdTag().op == Operand.WILDCARD) {
      for (Row row : evaluator.session.matrix) {
        ReplicaCount replicaCount = rc.getOrDefault(row.node, EMPTY_COUNT);
        addViolation(violations, evaluator, deviations, sc, row.node, row.node, replicaCount);
      }
    } else {
      rc.forEach((name, replicaCount) -> {
        if (!sc.replica.isPass(replicaCount)) {
          Violation v = new LazyViolation(
              sc,
              evaluator.collName,
              evaluator.shardName == null ? Policy.ANY : evaluator.shardName,
              NODE.tagName.equals(clause.tag.name) ? name : null,
              replicaCount,
              sc.getReplica().replicaCountDelta(replicaCount),
              name,
              evaluator.session);
          violations.add(v);
          if (!clause.strict && deviations != null) {
            clause.getThirdTag().varType.computeDeviation(evaluator.session, deviations, replicaCount, sc);
          }
        } else {
          if (sc.replica.op == RANGE_EQUAL)
            sc.getThirdTag().varType.computeDeviation(evaluator.session, deviations, replicaCount, sc);
        }
      });

    }
  }

  private void addViolation(List<Violation> violations,
                            Clause.ComputedValueEvaluator evaluator,
                            double[] deviations, SealedClause sc,
                            String node,
                            String key,
                            ReplicaCount replicaCount) {
    if (!sc.replica.isPass(replicaCount)) {
      Violation v = new LazyViolation(
          sc,
          evaluator.collName,
          evaluator.shardName == null ? Policy.ANY : evaluator.shardName,
          node,
          replicaCount,
          sc.getReplica().replicaCountDelta(replicaCount),
          key,
          evaluator.session);
      violations.add(v);
      if (!sc.strict && deviations != null) {
        sc.getThirdTag().varType.computeDeviation(evaluator.session, deviations, replicaCount, sc);
      }
    } else {
      if (sc.replica.op == RANGE_EQUAL)
        sc.getThirdTag().varType.computeDeviation(evaluator.session, deviations, replicaCount, sc);
    }
  }

  List<Violation> computeViolations(Policy.Session session, Clause clause, double[] deviations) {
    Clause.ComputedValueEvaluator evaluator = new Clause.ComputedValueEvaluator(session);
    List<Violation> result = new ArrayList<>();
    collections.forEach((coll, cd) -> {
      evaluator.collName = coll;
      evaluator.shardName = null;
      if (clause.dataGrouping == Clause.DataGrouping.COLL) {
        getViolations(cd.clauseValues, result, evaluator, clause, deviations);
      } else if (clause.dataGrouping == Clause.DataGrouping.SHARD) {
        cd.shards.forEach((shard, sd) -> {
          evaluator.shardName = shard;
          getViolations(sd.values, result, evaluator, clause, deviations);
        });
      }
    });
    return result;
  }


}
