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
package org.apache.solr.update;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Phaser;

import org.apache.http.NoHttpResponseException;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.Diagnostics;
import org.apache.solr.update.processor.DistributedUpdateProcessor;
import org.apache.solr.update.processor.DistributedUpdateProcessor.LeaderRequestReplicationTracker;
import org.apache.solr.update.processor.DistributedUpdateProcessor.RollupRequestReplicationTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used for distributing commands from a shard leader to its replicas.
 */
public class SolrCmdDistributor {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final List<Error> allErrors = Collections.synchronizedList(new ArrayList<>());
  private Http2SolrClient client;
  private Phaser pendingTasksPhaser = new Phaser(1);
  private int retryPause = 500;

  public SolrCmdDistributor(UpdateShardHandler updateShardHandler) {
    this.client = updateShardHandler.getUpdateOnlyHttpClient();
  }
  
  /* For tests only */
  SolrCmdDistributor(Http2SolrClient client, int retryPause) {
    this.client = client;
    this.retryPause = retryPause;
  }
  
  public void finish() {
    blockUntilFinished();
  }

  public void distribDelete(DeleteUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params) throws IOException {
    distribDelete(cmd, nodes, params, false, null, null);
  }

  public void distribDelete(DeleteUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params, boolean sync,
                            RollupRequestReplicationTracker rollupTracker,
                            LeaderRequestReplicationTracker leaderTracker) throws IOException {
    
    if (!cmd.isDeleteById()) {
      blockUntilFinished(); // For DBQ, flush all writes before submitting
    }
    
    for (Node node : nodes) {
      UpdateRequest uReq = new UpdateRequest();
      uReq.setParams(params);
      uReq.setCommitWithin(cmd.commitWithin);
      if (cmd.isDeleteById()) {
        uReq.deleteById(cmd.getId(), cmd.getRoute(), cmd.getVersion());
      } else {
        uReq.deleteByQuery(cmd.query);
      }
      submit(new Req(cmd, node, uReq, sync, rollupTracker, leaderTracker), false);
    }
  }
  
  public void distribAdd(AddUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params) throws IOException {
    distribAdd(cmd, nodes, params, false, null, null);
  }

  public void distribAdd(AddUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params, boolean synchronous) throws IOException {
    distribAdd(cmd, nodes, params, synchronous, null, null);
  }

  public void distribAdd(AddUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params, boolean synchronous,
                         RollupRequestReplicationTracker rollupTracker,
                         LeaderRequestReplicationTracker leaderTracker) throws IOException {
    for (Node node : nodes) {
      UpdateRequest uReq = new UpdateRequest();
      if (cmd.isLastDocInBatch)
        uReq.lastDocInBatch();
      uReq.setParams(params);
      uReq.add(cmd.solrDoc, cmd.commitWithin, cmd.overwrite);
      if (cmd.isInPlaceUpdate()) {
        params.set(DistributedUpdateProcessor.DISTRIB_INPLACE_PREVVERSION, String.valueOf(cmd.prevVersion));
      }
      submit(new Req(cmd, node, uReq, synchronous, rollupTracker, leaderTracker), false);
    }
    
  }

  public void distribCommit(CommitUpdateCommand cmd, List<Node> nodes,
      ModifiableSolrParams params) throws IOException {
    
    // we need to do any retries before commit...
    blockUntilFinished();
    
    UpdateRequest uReq = new UpdateRequest();
    uReq.setParams(params);
    
    addCommit(uReq, cmd);
    
    log.debug("Distrib commit to: {} params: {}", nodes, params);
    
    for (Node node : nodes) {
      submit(new Req(cmd, node, uReq, false), true);
    }
    
  }

  public void blockUntilFinished() {
    pendingTasksPhaser.arriveAndAwaitAdvance();
  }
  
  void addCommit(UpdateRequest ureq, CommitUpdateCommand cmd) {
    if (cmd == null) return;
    ureq.setAction(cmd.optimize ? AbstractUpdateRequest.ACTION.OPTIMIZE
        : AbstractUpdateRequest.ACTION.COMMIT, false, cmd.waitSearcher, cmd.maxOptimizeSegments, cmd.softCommit, cmd.expungeDeletes, cmd.openSearcher);
  }

  private void submit(final Req req, boolean isCommit) {
    pendingTasksPhaser.register();
    submit0(req, isCommit);
  }

  private void submit0(final Req req, boolean isCommit) {

    if (log.isDebugEnabled()) {
      log.debug("sending update to "
          + req.node.getUrl() + " retry:"
          + req.retries + " " + req.cmd + " params:" + req.uReq.getParams());
    }

    try {
      req.uReq.setBasePath(req.node.getUrl());
      if (req.synchronous) {
        NamedList rsp = client.request(req.uReq);
        req.trackRequestResult(rsp, true);
        pendingTasksPhaser.arriveAndDeregister();
      } else {
        //TODO write add cmds in single outputstream
        client.request(req.uReq, null, new Http2SolrClient.OnComplete() {
          @Override
          public void onSuccess(NamedList result) {
            req.trackRequestResult(result, true);
            pendingTasksPhaser.arriveAndDeregister();
          }

          @Override
          public void onFailure(Throwable t) {
            handleAndRetry(req, t, isCommit);
          }
        });
      }
    } catch (Exception e) {
      handleAndRetry(req, e, isCommit);
    }
  }

  private void handleAndRetry(Req req, Throwable t, boolean isCommit) {
    SolrException.log(log, t);
    Error error = new Error(req, t);
    if (checkRetry(error)) {
      submit0(req, isCommit);
    } else {
      req.trackRequestResult(null, false);
      allErrors.add(error);
      pendingTasksPhaser.arriveAndDeregister();
    }
  }

  private boolean checkRetry(Error err) {
    log.info("SolrCmdDistributor got error", err);
    try {
      /*
       * if this is a retryable request we may want to retry, depending on the error we received and
       * the number of times we have already retried
       */
      boolean isRetry = err.req.shouldRetry(err);
      if (testing_errorHook != null) Diagnostics.call(testing_errorHook, err.t);

      // this can happen in certain situations such as close
      if (isRetry) {
        err.req.retries++;

        if (err.req.node instanceof ForwardNode) {
          SolrException.log(SolrCmdDistributor.log, "forwarding update to "
              + err.req.node.getUrl() + " failed - retrying ... retries: "
              + err.req.retries + "/" + err.req.node.getMaxRetries() + ". "
              + err.req.cmd.toString() + " params:"
              + err.req.uReq.getParams() + " rsp:" + err.statusCode, err.t);
        } else {
          SolrException.log(SolrCmdDistributor.log, "FROMLEADER request to "
              + err.req.node.getUrl() + " failed - retrying ... retries: "
              + err.req.retries + "/" + err.req.node.getMaxRetries() + ". "
              + err.req.cmd.toString() + " params:"
              + err.req.uReq.getParams() + " rsp:" + err.statusCode, err.t);
        }
        try {
          Thread.sleep(retryPause); //TODO: Do we want this wait for every error?
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn(null, e);
        }
        return true;
      } else {
        return false;
      }
    } catch (Exception e) {
      // continue on
      log.error("Unexpected Error while doing request retries", e);
      // avoid infinite loop
      return false;
    }
  }

  public static class Req {
    public final Node node;
    public final UpdateRequest uReq;
    public final UpdateCommand cmd;
    private final boolean synchronous;
    private final RollupRequestReplicationTracker rollupTracker;
    private final LeaderRequestReplicationTracker leaderTracker;

    public int retries;

    public Req(UpdateCommand cmd, Node node, UpdateRequest uReq, boolean synchronous) {
      this(cmd, node, uReq, synchronous, null, null);
    }

    public Req(UpdateCommand cmd, Node node, UpdateRequest uReq, boolean synchronous,
               RollupRequestReplicationTracker rollupTracker,
               LeaderRequestReplicationTracker leaderTracker) {
      this.node = node;
      this.uReq = uReq;
      this.synchronous = synchronous;
      this.cmd = cmd;
      this.rollupTracker = rollupTracker;
      this.leaderTracker = leaderTracker;
    }
    
    /**
     * @return true if this request should be retried after receiving a particular error
     *         false otherwise
     */
    boolean shouldRetry(Error err) {
      boolean isRetry = node.checkRetry(err);
      isRetry &= uReq.getDeleteQuery() == null || uReq.getDeleteQuery().isEmpty(); //Don't retry DBQs 
      return isRetry && retries < node.getMaxRetries();
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("SolrCmdDistributor$Req: cmd=").append(cmd.toString());
      sb.append("; node=").append(String.valueOf(node));
      return sb.toString();
    }

    // Called whenever we get results back from a sub-request.
    // The only ambiguity is if I have _both_ a rollup tracker and a leader tracker. In that case we need to handle
    // both requests returning from leaders of other shards _and_ from my followers. This happens if a leader happens
    // to be the aggregator too.
    //
    // This isn't really a problem because only responses _from_ some leader will have the "rf" parameter, in which case
    // we need to add the data to the rollup tracker.
    //
    // In the case of a leaderTracker and rollupTracker both being present, then we need to take care when assembling
    // the final response to check both the rollup and leader trackers on the aggrator node.
    public void trackRequestResult(NamedList resp, boolean success) {

      // Returning Integer.MAX_VALUE here means there was no "rf" on the response, therefore we just need to increment
      // our achieved rf if we are a leader, i.e. have a leaderTracker.
      int rfFromResp = getRfFromResponse(resp);

      if (leaderTracker != null && rfFromResp == Integer.MAX_VALUE) {
        leaderTracker.trackRequestResult(node, success);
      }

      if (rollupTracker != null) {
        rollupTracker.testAndSetAchievedRf(rfFromResp);
      }
    }

    private int getRfFromResponse(NamedList resp) {
      if (resp != null) {
        Object hdr = resp.get("responseHeader");
        if (hdr != null && hdr instanceof NamedList) {
          NamedList<Object> hdrList = (NamedList<Object>) hdr;
          Object rfObj = hdrList.get(UpdateRequest.REPFACT);
          if (rfObj != null && rfObj instanceof Integer) {
            return (Integer) rfObj;
          }
        }
      }
      return Integer.MAX_VALUE;
    }
  }

  public static Diagnostics.Callable testing_errorHook;  // called on error when forwarding request.  Currently data=[this, Request]

  
  public static class Response {
    public List<Error> errors = new ArrayList<>();
  }

  // Immutable
  public final static class Error {
    public final Throwable t;
    public final int statusCode;
    public final Req req;

    public Error(Req req, Throwable t) {
      this.req = req;
      this.t = t;
      if (t instanceof SolrException) {
        statusCode = ((SolrException) t).code();
      } else {
        statusCode = -1;
      }
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("SolrCmdDistributor$Error: statusCode=").append(statusCode);
      sb.append("; exception=").append(String.valueOf(t));
      sb.append("; req=").append(String.valueOf(req));
      return sb.toString();
    }
  }
  
  public static abstract class Node {
    public abstract String getUrl();
    public abstract boolean checkRetry(Error e);
    public abstract String getCoreName();
    public abstract String getBaseUrl();
    public abstract ZkCoreNodeProps getNodeProps();
    public abstract String getCollection();
    public abstract String getShardId();
    public abstract int getMaxRetries();
  }

  public static class StdNode extends Node {
    protected ZkCoreNodeProps nodeProps;
    protected String collection;
    protected String shardId;
    private final boolean retry;
    private final int maxRetries;

    public StdNode(ZkCoreNodeProps nodeProps) {
      this(nodeProps, null, null, 0);
    }
    
    public StdNode(ZkCoreNodeProps nodeProps, String collection, String shardId) {
      this(nodeProps, collection, shardId, 0);
    }
    
    public StdNode(ZkCoreNodeProps nodeProps, String collection, String shardId, int maxRetries) {
      this.nodeProps = nodeProps;
      this.collection = collection;
      this.shardId = shardId;
      this.retry = maxRetries > 0;
      this.maxRetries = maxRetries;
    }
    
    public String getCollection() {
      return collection;
    }
    
    public String getShardId() {
      return shardId;
    }
        
    @Override
    public String getUrl() {
      return nodeProps.getCoreUrl();
    }
    
    @Override
    public String toString() {
      return this.getClass().getSimpleName() + ": " + nodeProps.getCoreUrl();
    }

    @Override
    public boolean checkRetry(Error err) {
      if (!retry) return false;

      if (err.statusCode == 404 || err.statusCode == 403 || err.statusCode == 503) {
        return true;
      }

      // if it's a connect exception, lets try again
      if (err.t instanceof SolrServerException) {
        if (isRetriableException(((SolrServerException) err.t).getRootCause())) {
          return true;
        }
      } else {
        if (isRetriableException(err.t)) {
          return true;
        }
      }
      return false;
    }
    
    /**
     * @return true if Solr should retry in case of hitting this exception
     *         false otherwise
     */
    private boolean isRetriableException(Throwable t) {
      return t instanceof SocketException || t instanceof NoHttpResponseException || t instanceof SocketTimeoutException;
    }

    @Override
    public String getBaseUrl() {
      return nodeProps.getBaseUrl();
    }

    @Override
    public String getCoreName() {
      return nodeProps.getCoreName();
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      String baseUrl = nodeProps.getBaseUrl();
      String coreName = nodeProps.getCoreName();
      String url = nodeProps.getCoreUrl();
      result = prime * result + ((baseUrl == null) ? 0 : baseUrl.hashCode());
      result = prime * result + ((coreName == null) ? 0 : coreName.hashCode());
      result = prime * result + ((url == null) ? 0 : url.hashCode());
      result = prime * result + Boolean.hashCode(retry);
      result = prime * result + Integer.hashCode(maxRetries);
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      StdNode other = (StdNode) obj;
      if (this.retry != other.retry) return false;
      if (this.maxRetries != other.maxRetries) return false;
      String baseUrl = nodeProps.getBaseUrl();
      String coreName = nodeProps.getCoreName();
      String url = nodeProps.getCoreUrl();
      if (baseUrl == null) {
        if (other.nodeProps.getBaseUrl() != null) return false;
      } else if (!baseUrl.equals(other.nodeProps.getBaseUrl())) return false;
      if (coreName == null) {
        if (other.nodeProps.getCoreName() != null) return false;
      } else if (!coreName.equals(other.nodeProps.getCoreName())) return false;
      if (url == null) {
        if (other.nodeProps.getCoreUrl() != null) return false;
      } else if (!url.equals(other.nodeProps.getCoreUrl())) return false;
      return true;
    }

    @Override
    public ZkCoreNodeProps getNodeProps() {
      return nodeProps;
    }

    @Override
    public int getMaxRetries() {
      return this.maxRetries;
    }
  }
  
  // RetryNodes are used in the case of 'forward to leader' where we want
  // to try the latest leader on a fail in the case the leader just went down.
  public static class ForwardNode extends StdNode {
    
    private ZkStateReader zkStateReader;
    
    public ForwardNode(ZkCoreNodeProps nodeProps, ZkStateReader zkStateReader, String collection, String shardId, int maxRetries) {
      super(nodeProps, collection, shardId, maxRetries);
      this.zkStateReader = zkStateReader;
      this.collection = collection;
      this.shardId = shardId;
    }

    @Override
    public boolean checkRetry(Error err) {
      boolean doRetry = false;
      if (err.statusCode == 404 || err.statusCode == 403 || err.statusCode == 503) {
        doRetry = true;
      }
      
      // if it's a connect exception, lets try again
      if (err.t instanceof SolrServerException && ((SolrServerException) err.t).getRootCause() instanceof ConnectException) {
        doRetry = true;
      } else if (err.t instanceof ConnectException) {
        doRetry = true;
      }
      if (doRetry) {
        ZkCoreNodeProps leaderProps;
        try {
          leaderProps = new ZkCoreNodeProps(zkStateReader.getLeaderRetry(
              collection, shardId));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        } catch (Exception e) {
          // we retry with same info
          log.warn(null, e);
          return true;
        }
       
        this.nodeProps = leaderProps;
      }
      return doRetry;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = super.hashCode();
      result = prime * result
          + ((collection == null) ? 0 : collection.hashCode());
      result = prime * result + ((shardId == null) ? 0 : shardId.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!super.equals(obj)) return false;
      if (getClass() != obj.getClass()) return false;
      ForwardNode other = (ForwardNode) obj;
      if (nodeProps.getCoreUrl() == null) {
        if (other.nodeProps.getCoreUrl() != null) return false;
      } else if (!nodeProps.getCoreUrl().equals(other.nodeProps.getCoreUrl())) return false;

      return true;
    }
  }

  public List<Error> getErrors() {
    return allErrors;
  }
}

