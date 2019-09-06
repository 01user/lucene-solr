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

package org.apache.solr.handler.admin;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.http.client.HttpClient;
import org.apache.solr.api.ApiBag;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.CollectionApiMapping;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.CommandOperation;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.BlobRepository;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.RuntimeLib;
import org.apache.solr.handler.SolrConfigHandler;
import org.apache.solr.handler.admin.BaseHandlerApiSupport.ApiCommand;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.RTimer;
import org.apache.solr.util.SimplePostTool;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.GET;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.client.solrj.request.CollectionApiMapping.EndPoint.CLUSTER_CMD;
import static org.apache.solr.client.solrj.request.CollectionApiMapping.EndPoint.CLUSTER_NODES;
import static org.apache.solr.common.util.CommandOperation.captureErrors;
import static org.apache.solr.common.util.StrUtils.formatString;
import static org.apache.solr.core.ConfigOverlay.ZNODEVER;
import static org.apache.solr.core.RuntimeLib.SHA256;

//implements  v2 only APIs at /cluster/* end point
class ClusterAPI {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  //sync the cluster props in every node
  static void syncClusterProps(ApiInfo info) throws IOException {
    CoreContainer cc = info.coreContainer;
    Stat stat = new Stat();
    Map<String, Object> clusterProperties = new ClusterProperties(cc.getZkController().getZkClient()).getClusterProperties(stat);
    try {
      cc.getPackageManager().onChange(clusterProperties);
    } catch (SolrException e) {
      log.error("error executing command : " + info.op.jsonStr(), e);
      throw e;
    } catch (Exception e) {
      log.error("error executing command : " + info.op.jsonStr(), e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "error executing command : ", e);
    }
    log.info("current version of clusterprops.json is {} , trying to get every node to update ", stat.getVersion());
    log.debug("The current clusterprops.json:  {}", clusterProperties);
    waitForStateSync(stat.getVersion(), cc);
  }

  private static void waitForStateSync(int expectedVersion, CoreContainer coreContainer) {
    final RTimer timer = new RTimer();
    int waitTimeSecs = 30;
    // get a list of active replica cores to query for the schema zk version (skipping this core of course)
    List<ClusterAPI.PerNodeCallable> concurrentTasks = new ArrayList<>();

    ZkStateReader zkStateReader = coreContainer.getZkController().getZkStateReader();
    for (String nodeName : zkStateReader.getClusterState().getLiveNodes()) {
      PerNodeCallable e = new PerNodeCallable(coreContainer.getUpdateShardHandler().getDefaultHttpClient(), zkStateReader.getBaseUrlForNodeName(nodeName), expectedVersion, waitTimeSecs);
      concurrentTasks.add(e);
    }
    if (concurrentTasks.isEmpty()) return; // nothing to wait for ...

    log.info("Waiting up to {} secs for {} nodes to update clusterprops to be of version {} ",
        waitTimeSecs, concurrentTasks.size(), expectedVersion);
    SolrConfigHandler.execInparallel(concurrentTasks, parallelExecutor -> {
      try {
        List<String> failedList = SolrConfigHandler.executeAll(expectedVersion, waitTimeSecs, concurrentTasks, parallelExecutor);

        // if any tasks haven't completed within the specified timeout, it's an error
        if (failedList != null)
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
              formatString("{0} out of {1} the property {2} to be of version {3} within {4} seconds! Failed cores: {5}",
                  failedList.size(), concurrentTasks.size() + 1, expectedVersion, 30, failedList));
      } catch (InterruptedException e) {
        log.warn(formatString(
            "Request was interrupted . trying to set the clusterprops to version {0} to propagate to {1} nodes ",
            expectedVersion, concurrentTasks.size()));
        Thread.currentThread().interrupt();

      }
    });

    log.info("Took {}ms to update the clusterprops to be of version {}  on {} nodes",
        timer.getTime(), expectedVersion, concurrentTasks.size());

  }


  enum Commands implements ApiCommand {

/*
    ADD_REPO(CLUSTER_CMD, POST, "add-repository") {
      @Override
      void call(ApiInfo info) throws Exception {
      }
    },
    UPDATE_REPO(CLUSTER_CMD, POST, "update-repository") {
      @Override
      void call(ApiInfo info) throws Exception {
      }
    },
    DELETE_REPO(CLUSTER_CMD, POST, "delete-repository") {
      @Override
      void call(ApiInfo info) throws Exception {
      }
    },
*/

    GET_NODES(CLUSTER_NODES, GET, null) {
      @Override
      void call(ApiInfo info) throws Exception {
        info.rsp.add("nodes", info.coreContainer.getZkController().getClusterState().getLiveNodes());
      }

    },
    POST_BLOB(CollectionApiMapping.EndPoint.CLUSTER_BLOB, POST, null) {
      @Override
      void call(ApiInfo info) throws Exception {
        CoreContainer coreContainer = ((CollectionHandlerApi) info.apiHandler).handler.coreContainer;
        Iterable<ContentStream> streams = info.req.getContentStreams();
        if (streams == null) throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "no payload");
        String sha256 = null;
        ContentStream stream = streams.iterator().next();
        try {
          ByteBuffer buf = SimplePostTool.inputStreamToByteArray(stream.getStream());
          sha256 = BlobRepository.sha256Digest(buf);
          coreContainer.getBlobRepository().persistToFile(buf, sha256);
          coreContainer.getBlobRepository().putTmpBlob(buf, sha256);
          List<String> nodes = coreContainer.getBlobRepository().shuffledNodes();
          int i = 0;
          for (String node : nodes) {
            String baseUrl = coreContainer.getZkController().getZkStateReader().getBaseUrlForNodeName(node);
            String url = baseUrl.replace("/solr", "/api") + "/node/blob?sha256=" + sha256 + "&fromNode=";
            if (i < 25) {
              // the first 25 nodes will be asked to fetch from this node
              //it's there in  the memory now , so , it must be served fast
              url += coreContainer.getZkController().getNodeName();
            } else {
              // trying to avoid the thundering herd problem when there are a very large no:of nodes
              // others should try to fetch it from any node where it is available
              url += "*";
            }
            try {
              //fire and forget
              Utils.executeGET(coreContainer.getUpdateShardHandler().getDefaultHttpClient(), url, null);
            } catch (Exception e) {
              log.info("Node: " + node +
                  " failed to respond for blob notification", e);
              //ignore the exception
              // some nodes may be down or not responding
            }
            i++;
          }
          info.rsp.add(SHA256, sha256);

        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
        } finally {
          if (sha256 != null) coreContainer.getBlobRepository().removeTmpBlob(sha256);
        }

      }

      @Override
      public boolean isRaw() {
        return true;
      }
    },

    SET_CLUSTER_PROPERTY_OBJ(CLUSTER_CMD,
        POST,
        "set-obj-property") {
      @Override
      void call(ApiInfo info) throws Exception {
        ClusterProperties clusterProperties = new ClusterProperties(info.coreContainer.getZkController().getZkClient());
        try {
          clusterProperties.setClusterProperties(info.op.getDataMap());
        } catch (Exception e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error in API", e);
        }
      }

    },
    ADD_PACKAGE(CLUSTER_CMD,
        POST,
        "add-package") {
      @Override
      void call(ApiInfo info) throws Exception {
        if (addUpdatePackage(info)) syncClusterProps(info);
      }


    },
    UPDATE_PACKAGE(CLUSTER_CMD,
        POST,
        "update-package") {
      @Override
      void call(ApiInfo info) throws Exception {
        if (addUpdatePackage(info)) syncClusterProps(info);
      }
    },
    DELETE_PKG(CLUSTER_CMD,
        POST,
        "delete-package") {
      @Override
      void call(ApiInfo info) throws Exception {
        if (deletePackage(info)) syncClusterProps(info);
      }

      boolean deletePackage(ApiInfo params) throws Exception {
        if (!RuntimeLib.isEnabled()) {
          params.op.addError("node not started with enable.runtime.lib=true");
          return false;
        }
        String name = params.op.getStr(CommandOperation.ROOT_OBJ);
        ClusterProperties clusterProperties = new ClusterProperties(params.coreContainer.getZkController().getZkClient());
        Map<String, Object> props = clusterProperties.getClusterProperties();
        List<String> pathToLib = asList(CommonParams.PACKAGE, name);
        Map existing = (Map) Utils.getObjectByPath(props, false, pathToLib);
        if (existing == null) {
          params.op.addError("No such runtimeLib : " + name);
          return false;
        }
        Map delta = new LinkedHashMap();
        Utils.setObjectByPath(delta, pathToLib, null, true);
        clusterProperties.setClusterProperties(delta);
        return true;
      }
    },
    ADD_REQ_HANDLER(CLUSTER_CMD,
        POST,
        "add-requesthandler") {
      @Override
      void call(ApiInfo info) throws Exception {
        if (addRequestHandler(info)) syncClusterProps(info);
      }

      boolean addRequestHandler(ApiInfo info) throws Exception {
        Map data = info.op.getDataMap();
        String name = (String) data.get("name");
        CoreContainer coreContainer = info.coreContainer;
        ClusterProperties clusterProperties = new ClusterProperties(coreContainer.getZkController().getZkClient());
        Map<String, Object> map = clusterProperties.getClusterProperties();
        if (Utils.getObjectByPath(map, false, asList(SolrRequestHandler.TYPE, name)) != null) {
          info.op.addError("A requestHandler already exists with the said name");
          return false;
        }
        Map m = new LinkedHashMap();
        Utils.setObjectByPath(m, asList(SolrRequestHandler.TYPE, name), data, true);
        clusterProperties.setClusterProperties(m);
        return true;
      }
    },
    DELETE_REQ_HANDLER(CLUSTER_CMD,
        POST,
        "delete-requesthandler") {
      @Override
      void call(ApiInfo info) throws Exception {
        if (deleteReqHandler(info)) syncClusterProps(info);
      }

      boolean deleteReqHandler(ApiInfo params) throws Exception {
        String name = params.op.getStr("");
        ClusterProperties clusterProperties = new ClusterProperties(params.coreContainer.getZkController().getZkClient());
        Map<String, Object> map = clusterProperties.getClusterProperties();
        if (Utils.getObjectByPath(map, false, asList(SolrRequestHandler.TYPE, name)) == null) {
          params.op.addError("NO such requestHandler with name :");
          return false;
        }
        Map m = new LinkedHashMap();
        Utils.setObjectByPath(m, asList(SolrRequestHandler.TYPE, name), null, true);
        clusterProperties.setClusterProperties(m);
        return true;
      }
    },

    ;

    private CollectionApiMapping.CommandMeta _meta;

    Commands(CollectionApiMapping.V2EndPoint endPoint, SolrRequest.METHOD method, String cmdName) {
      _meta = new CollectionApiMapping.CommandMeta() {
        @Override
        public String getName() {
          return cmdName;
        }

        @Override
        public SolrRequest.METHOD getHttpMethod() {
          return method;
        }

        @Override
        public CollectionApiMapping.V2EndPoint getEndPoint() {
          return endPoint;
        }
      };

    }

    @Override
    public CollectionApiMapping.CommandMeta meta() {
      return _meta;

    }

    @Override
    public void invoke(SolrQueryRequest req, SolrQueryResponse rsp, BaseHandlerApiSupport apiHandler) throws Exception {
      CommandOperation op = null;
      if (meta().getHttpMethod() == SolrRequest.METHOD.POST) {
        if (_meta.getName() != null) {
          List<CommandOperation> commands = req.getCommands(true);
          if (commands == null || commands.size() != 1)
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "should have exactly one command");
          op = commands.get(0);
        }
      }

      call(new ApiInfo(req, rsp, apiHandler, op,
          ((CollectionHandlerApi) apiHandler).handler.coreContainer));
      if (op != null && op.hasError()) {
        throw new ApiBag.ExceptionWithErrObject(SolrException.ErrorCode.BAD_REQUEST, "error processing commands", captureErrors(singletonList(op)));
      }
    }

    abstract void call(ApiInfo info) throws Exception;


  }

  static boolean addUpdatePackage(ApiInfo params) throws Exception {
    if (!RuntimeLib.isEnabled()) {
      params.op.addError("node not started with enable.runtime.lib=true");
      return false;
    }

    CollectionHandlerApi handler = (CollectionHandlerApi) params.apiHandler;
    RuntimeLib lib = new RuntimeLib(handler.handler.coreContainer);
    CommandOperation op = params.op;
    String name = op.getStr("name");
    ClusterProperties clusterProperties = new ClusterProperties(((CollectionHandlerApi) params.apiHandler).handler.coreContainer.getZkController().getZkClient());
    Map<String, Object> props = clusterProperties.getClusterProperties();
    List<String> pathToLib = asList(CommonParams.PACKAGE, name);
    Map existing = (Map) Utils.getObjectByPath(props, false, pathToLib);
    Map<String, Object> dataMap = Utils.getDeepCopy(op.getDataMap(), 3);
    if (ClusterAPI.Commands.ADD_PACKAGE.meta().getName().equals(op.name)) {
      if (existing != null) {
        op.addError(StrUtils.formatString("The jar with a name ''{0}'' already exists ", name));
        return false;
      }
    } else {// this is an update command
      if (existing == null) {
        op.addError(StrUtils.formatString("The jar with a name ''{0}'' does not exist", name));
        return false;
      }
      if (Objects.equals(existing.get(SHA256), dataMap.get(SHA256))) {
        op.addError("Trying to update a jar with the same sha256");
        return false;
      }
      String oldSha256 = (String) Utils.getObjectByPath(existing, true, SHA256);
      if (oldSha256 != null) {
        dataMap.put("old_sha256", oldSha256);
      }
    }
    try {
      lib.init(new PluginInfo(RuntimeLib.TYPE, dataMap));
    } catch (SolrException e) {
      log.error("Error loading runtimelib ", e);
      op.addError(e.getMessage());
      return false;
    }

    Map delta = new LinkedHashMap();
    Utils.setObjectByPath(delta, pathToLib, dataMap, true);
    clusterProperties.setClusterProperties(delta);
    return true;

  }

  static class ApiInfo {
    final SolrQueryRequest req;
    final SolrQueryResponse rsp;
    final BaseHandlerApiSupport apiHandler;
    final CommandOperation op;
    final CoreContainer coreContainer;

    ApiInfo(SolrQueryRequest req, SolrQueryResponse rsp, BaseHandlerApiSupport apiHandler, CommandOperation op, CoreContainer coreContainer) {
      this.req = req;
      this.rsp = rsp;
      this.apiHandler = apiHandler;
      this.op = op;
      this.coreContainer = coreContainer;
    }
  }


  static class PerNodeCallable extends SolrConfigHandler.PerReplicaCallable {
    private final HttpClient httpClient;
    final String v2Url;

    static final List<String> path = Arrays.asList("metadata", CommonParams.VERSION);

    PerNodeCallable(HttpClient httpClient, String baseUrl, int expectedversion, int waitTime) {
      super(baseUrl, ZNODEVER, expectedversion, waitTime);
      this.httpClient = httpClient;
      v2Url = baseUrl.replace("/solr", "/api") + "/node/ext?wt=javabin&omitHeader=true";
    }

    @Override
    protected boolean verifyResponse(MapWriter mw, int attempts) {
      remoteVersion = (Number) mw._get(path, -1);
      if (remoteVersion.intValue() >= expectedZkVersion) return true;
      log.info(formatString("Could not get expectedVersion {0} from {1} , remote val= {2}   after {3} attempts", expectedZkVersion, coreUrl, remoteVersion, attempts));

      return false;
    }


    @Override
    public Boolean call() throws Exception {
      final RTimer timer = new RTimer();
      int attempts = 0;

      // eventually, this loop will get killed by the ExecutorService's timeout
      while (true) {
        try {
          long timeElapsed = (long) timer.getTime() / 1000;
          if (timeElapsed >= maxWait) {
            return false;
          }
          log.debug("Time elapsed : {} secs, maxWait {}", timeElapsed, maxWait);
          Thread.sleep(100);
          MapWriter resp = (MapWriter) Utils.executeGET(httpClient, v2Url, Utils.JAVABINCONSUMER);
          if (verifyResponse(resp, attempts)) {

            break;
          }
          attempts++;
        } catch (Exception e) {
          if (e instanceof InterruptedException) {
            break; // stop looping
          } else {
            log.warn("Failed to execute " + v2Url + " due to: " + e);
          }
        }
      }
      return true;
    }

  }
}
