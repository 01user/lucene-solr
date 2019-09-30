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
package org.apache.solr.util;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.io.IOUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.packagemanager.SolrPackage;
import org.apache.solr.packagemanager.SolrPackage.SolrPackageRelease;
import org.apache.solr.packagemanager.SolrPackageInstance;
import org.apache.solr.packagemanager.SolrPackageManager;
import org.apache.solr.packagemanager.SolrPackageRepository;
import org.apache.solr.packagemanager.SolrUpdateManager;
import org.apache.solr.packagemanager.pf4j.PluginException;
import org.apache.solr.util.SolrCLI.StatusTool;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.fasterxml.jackson.databind.ObjectMapper;


public class PackageTool extends SolrCLI.ToolBase {
  @Override
  public String getName() {
    return "package";
  }


  @Override
  protected void runImpl(CommandLine cli) throws Exception {
    // Need a logging free, clean output going through to the user.
    Configurator.setRootLevel(Level.OFF);

    String zkHost = getZkHost(cli);

    String cmd = cli.getArgs()[0];

    if (cmd != null) {
      SolrPackageManager pluginManager = new SolrPackageManager(new File("./plugins"));
      SolrUpdateManager updateManager = new SolrUpdateManager(pluginManager,
          getRepositoriesJson(new SolrZkClient(zkHost, 30000)));


      switch (cmd) {
        case "add-repo":
          addRepo(pluginManager, updateManager, zkHost, cli.getArgs()[1], cli.getArgs()[2]);
          break;
        case "list":
          list(pluginManager, updateManager, cli.getArgList().subList(1, cli.getArgList().size()));
          break;
        case "list-available":
          try {
            available(pluginManager, updateManager, cli.getArgList().subList(1, cli.getArgList().size()));
          } catch (PluginException ex) {
            ex.printStackTrace();
          }
          break;
        case "install":
          install(pluginManager, updateManager, cli.getArgList().subList(1, cli.getArgList().size()));
          break;
        case "deploy":
          deploy(pluginManager, updateManager, cli.getArgList().subList(1, cli.getArgList().size()));
          break;
        case "redeploy":
          redeploy(pluginManager, updateManager, cli.getArgList().subList(1, cli.getArgList().size()));
          break;
        case "update":
          if (cli.getArgList().size()==1) {
            update(pluginManager, updateManager);
          } else {
            updatePlugin(pluginManager, updateManager, cli.getArgs()[1], cli.getArgList().subList(2, cli.getArgList().size()));
          }
          break;
        default:
          throw new RuntimeException("Unrecognized command: "+cmd);
      };
    }
  }

  protected void addRepo(SolrPackageManager pluginManager, SolrUpdateManager updateManager, String zkHost, String name, String uri) throws KeeperException, InterruptedException, MalformedURLException, IOException {
    SolrZkClient zkClient = new SolrZkClient(zkHost, 30000);

    String existingRepositoriesJson = getRepositoriesJson(zkClient);
    System.out.println(existingRepositoriesJson);

    List repos = new ObjectMapper().readValue(existingRepositoriesJson, List.class);
    repos.add(new SolrPackageRepository(name, uri));
    if (zkClient.exists("/repositories.json", true) == false) {
      zkClient.create("/repositories.json", new ObjectMapper().writeValueAsString(repos).getBytes(), CreateMode.PERSISTENT, true);
    } else {
      zkClient.setData("/repositories.json", new ObjectMapper().writeValueAsString(repos).getBytes(), true);
    }
    
    if (zkClient.exists("/keys", true)==false) zkClient.create("/keys", new byte[0], CreateMode.PERSISTENT, true);
    if (zkClient.exists("/keys/exe", true)==false) zkClient.create("/keys/exe", new byte[0], CreateMode.PERSISTENT, true);
    if (zkClient.exists("/keys/exe/"+"pub_key.der", true)==false) zkClient.create("/keys/exe/"+"pub_key.der", new byte[0], CreateMode.PERSISTENT, true);
    zkClient.setData("/keys/exe/"+"pub_key.der", IOUtils.toByteArray(new URL(uri+"/publickey.der").openStream()), true);
    
    System.out.println("Added repository: "+name);
    System.out.println(getRepositoriesJson(zkClient));
  }

  protected String getRepositoriesJson(SolrZkClient zkClient) throws UnsupportedEncodingException, KeeperException, InterruptedException {
    if (zkClient.exists("/repositories.json", true)) {
      return new String(zkClient.getData("/repositories.json", null, null, true), "UTF-8");
    }
    return "[]";
  }

  protected void list(SolrPackageManager packageManager, SolrUpdateManager updateManager, List args) {
    for (SolrPackageInstance pkg: packageManager.getPlugins()) {
      System.out.println(pkg.getPluginId()+" ("+pkg.getVersion()+")");
    }
  }
  protected void available(SolrPackageManager pluginManager, SolrUpdateManager updateManager, List args) throws PluginException {
    System.out.println("Available packages:\n-----");
    for (SolrPackage i: updateManager.getPackages()) {
      SolrPackage plugin = (SolrPackage)i;
      System.out.println(plugin.id + " \t\t"+plugin.description);
      for (SolrPackageRelease version: plugin.versions) {
        System.out.println("\tVersion: "+version.version);
      }
    }
  }
  protected void install(SolrPackageManager pluginManager, SolrUpdateManager updateManager, List args) throws PluginException {
    updateManager.installPackage(args.get(0).toString(), args.get(1).toString());
    System.out.println(args.get(0).toString() + " installed.");
  }
  protected void deploy(SolrPackageManager pluginManager, SolrUpdateManager updateManager, List args) throws PluginException {
    System.out.println(pluginManager.deployInstallPackage(args.get(0).toString(), args.subList(1, args.size())));
  }

  protected void redeploy(SolrPackageManager pluginManager, SolrUpdateManager updateManager, List args) throws PluginException {
    System.out.println(pluginManager.deployUpdatePackage(args.get(0).toString(), args.subList(1, args.size())));
  }

  protected void update(SolrPackageManager pluginManager, SolrUpdateManager updateManager) throws PluginException {
    if (updateManager.hasUpdates()) {
      System.out.println("Available updates:\n-----");

      for (SolrPackage i: updateManager.getUpdates()) {
        SolrPackage plugin = (SolrPackage)i;
        System.out.println(plugin.id + " \t\t"+plugin.description);
        for (SolrPackageRelease version: plugin.versions) {
          System.out.println("\tVersion: "+version.version);
        }
      }

    } else {
      System.out.println("No updates found. System is up to date.");
    }
  }

  protected void updatePlugin(SolrPackageManager pluginManager, SolrUpdateManager updateManager, String pluginName, List args) throws PluginException {
    if (updateManager.hasUpdates()) {
      String latestVersion = updateManager.getLastPackageRelease(pluginName).version;
      System.out.println("Updating ["+pluginName+"] to version: "+latestVersion);
      updateManager.updatePackage(pluginName, latestVersion);
    } else {
      System.out.println("Package "+pluginName+" is already up to date.");
    }
  }

  
  @SuppressWarnings("static-access")
  public Option[] getOptions() {
    return new Option[] {
        OptionBuilder
        .withArgName("URL")
        .hasArg()
        .isRequired(false)
        .withDescription("Address of the Solr Web application, defaults to: "+SolrCLI.DEFAULT_SOLR_URL)
        .create("solrUrl"),
    };
  }


  private String getZkHost(CommandLine cli) throws Exception {
    String zkHost = cli.getOptionValue("zkHost");
    if (zkHost != null)
      return zkHost;

    // find it using the localPort
    String solrUrl = cli.getOptionValue("solrUrl", SolrCLI.DEFAULT_SOLR_URL);

    if (!solrUrl.endsWith("/"))
      solrUrl += "/";

    String systemInfoUrl = solrUrl+"admin/info/system";
    CloseableHttpClient httpClient = SolrCLI.getHttpClient();
    try {
      // hit Solr to get system info
      Map<String,Object> systemInfo = SolrCLI.getJson(httpClient, systemInfoUrl, 2, true);

      // convert raw JSON into user-friendly output
      StatusTool statusTool = new StatusTool();
      Map<String,Object> status = statusTool.reportStatus(solrUrl, systemInfo, httpClient);
      Map<String,Object> cloud = (Map<String, Object>)status.get("cloud");
      if (cloud != null) {
        String zookeeper = (String) cloud.get("ZooKeeper");
        if (zookeeper.endsWith("(embedded)")) {
          zookeeper = zookeeper.substring(0, zookeeper.length() - "(embedded)".length());
        }
        zkHost = zookeeper;
      }
    } finally {
      HttpClientUtil.close(httpClient);
    }

    return zkHost;
  }
}