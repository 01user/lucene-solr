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

package org.apache.solr.managed;

import java.io.Closeable;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.solr.common.SolrCloseable;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.schema.FieldType;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for resource management. It uses a flat model where there are named
 * resource pools of a given type, each pool with its own defined resource limits. Components can be added
 * to a pool for the management of a specific aspect of that component.
 */
public abstract class ResourceManager implements PluginInfoInitialized, SolrMetricProducer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String RESOURCE_MANAGER_PARAM = "resourceManager";
  public static final String POOL_CONFIGS_PARAM = "poolConfigs";
  public static final String POOL_LIMITS_PARAM = "poolLimits";
  public static final String POOL_PARAMS_PARAM = "poolParams";

  protected PluginInfo pluginInfo;
  protected SolrMetricsContext metricsContext;
  protected boolean isClosed = false;
  protected boolean enabled = true;
  protected boolean initialized = false;

  /**
   * Create a resource manager and optionally create configured pools.
   * @param loader SolrResourceLoader instance
   * @param timeSource time source instance
   * @param resourceManagerClass implementation class for the resource manager
   * @param pluginInfo resource manager plugin info
   * @param config resource manager and pool configurations
   */
  public static ResourceManager load(SolrResourceLoader loader, SolrMetricManager metricManager, TimeSource timeSource,
                                     Class<? extends ResourceManager> resourceManagerClass, PluginInfo pluginInfo,
                                     Map<String, Object> config) throws Exception {
    Map<String, Object> managerOverrides = (Map<String, Object>)config.getOrDefault(RESOURCE_MANAGER_PARAM, Collections.emptyMap());
    if (!managerOverrides.isEmpty()) {
      Map<String, Object> pluginMap = new HashMap<>();
      pluginInfo.toMap(pluginMap);
      pluginMap.putAll(managerOverrides);
      if (pluginMap.containsKey(FieldType.CLASS_NAME)) {
        resourceManagerClass = loader.findClass((String)pluginMap.get(FieldType.CLASS_NAME), ResourceManager.class);
      }
      pluginInfo = new PluginInfo(pluginInfo.type, pluginMap);
    }

    ResourceManager resourceManager = loader.newInstance(
        resourceManagerClass.getName(),
        resourceManagerClass,
        null,
        new Class[]{SolrResourceLoader.class, SolrMetricManager.class, TimeSource.class},
        new Object[]{loader, metricManager, timeSource});
    resourceManager.initializeMetrics(new SolrMetricsContext(metricManager, "node", SolrMetricProducer.getUniqueMetricTag(loader, null)), null);
    resourceManager.init(pluginInfo);
    Map<String, Object> poolConfigs = (Map<String, Object>)config.get(POOL_CONFIGS_PARAM);
    if (poolConfigs != null) {
      for (String poolName : poolConfigs.keySet()) {
        Map<String, Object> params = (Map<String, Object>)poolConfigs.get(poolName);
        if (params == null || params.isEmpty()) {
          throw new IllegalArgumentException("Pool '" + poolName + "' configuration missing: " + poolConfigs);
        }
        String type = (String)params.get(CommonParams.TYPE);
        if (type == null || type.isBlank()) {
          throw new IllegalArgumentException("Pool '" + poolName + "' type is missing: " + params);
        }
        Map<String, Object> poolLimits = (Map<String, Object>)params.getOrDefault(POOL_LIMITS_PARAM, Collections.emptyMap());
        Map<String, Object> poolParams = (Map<String, Object>)params.getOrDefault(POOL_PARAMS_PARAM, Collections.emptyMap());
        try {
          resourceManager.createPool(poolName, type, poolLimits, poolParams);
        } catch (Exception e) {
          log.warn("Failed to create resource manager pool '" + poolName + "'", e);
        }
      }
    }
    return resourceManager;
  }

  @Override
  public void init(PluginInfo info) {
    if (info != null) {
      this.pluginInfo = info.copy();
    }
    if (!enabled) {
      log.debug("Resource manager " + getClass().getSimpleName() + " disabled.");
      return;
    }
    try {
      doInit();
      initialized = true;
    } catch (Exception e) {
      log.warn("Exception initializing resource manager " + getClass().getSimpleName() + ", disabling!");
      try {
        close();
      } catch (Exception e1) {
        log.warn("Exception closing resource manager " + getClass().getSimpleName(), e1);
      }
    }
  }

  /**
   * Enable resource management, defaults to true. {@link #init(PluginInfo)} checks
   * this flag before calling {@link #doInit()}.
   * @param enabled - whether or not resource management is to be enabled
   */
  public void setEnabled(Boolean enabled) {
    if (enabled != null) {
      this.enabled = enabled;
    }
  }

  public PluginInfo getPluginInfo() {
    return pluginInfo;
  }

  protected abstract void doInit() throws Exception;

  @Override
  public void initializeMetrics(SolrMetricsContext parentContext, String childScope) {
    metricsContext = parentContext.getChildContext(this, "manager");
  }

  @Override
  public SolrMetricsContext getSolrMetricsContext() {
    return metricsContext;
  }

  public void updatePoolConfigs(Map<String, Object> newPoolConfigs) throws Exception {
    if (newPoolConfigs == null || newPoolConfigs.isEmpty()) {
      return;
    }

    for (Map.Entry<String, Object> entry : newPoolConfigs.entrySet()) {
      String poolName = entry.getKey();
      Map<String, Object> params = (Map<String, Object>)entry.getValue();
      ResourceManagerPool pool = getPool(poolName);
      if (pool == null) {
        log.warn("Cannot update config - pool '" + poolName + "' not found.");
        continue;
      }
      String type = (String)params.get(CommonParams.TYPE);
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("Pool '" + poolName + "' type is missing: " + params);
      }
      Map<String, Object> poolLimits = (Map<String, Object>)params.getOrDefault(POOL_LIMITS_PARAM, Collections.emptyMap());
      pool.setPoolLimits(poolLimits);
    }
  }

  public abstract ResourceManagerPoolFactory getResourceManagerPoolFactory();

  /**
   * Create a named resource management pool.
   * @param name pool name (must not be empty)
   * @param type pool type (one of the supported {@link ResourceManagerPool} types)
   * @param poolLimits pool limits (must not be null)
   * @param args other parameters (must not be null).
   * @return newly created and scheduled resource pool
   */
  public abstract ResourceManagerPool createPool(String name, String type, Map<String, Object> poolLimits, Map<String, Object> args) throws Exception;

  /**
   * List all currently existing pool names.
   */
  public abstract Collection<String> listPools();

  /** Get a named pool. */
  public abstract ResourceManagerPool getPool(String name);

  /**
   * Modify pool limits of an existing pool.
   * @param name existing pool name
   * @param poolLimits new pool limits. By convention only the values present in this map will be modified,
   *                   all other limits will remain unchanged. In order to remove a limit use null value.
   */
  public abstract void setPoolLimits(String name, Map<String, Object> poolLimits) throws Exception;

  /**
   * Remove pool. This also stops the management of resources registered with that pool.
   * @param name existing pool name
   */
  public abstract void removePool(String name) throws Exception;

  /**
   * Add managed components to a pool.
   * @param pool existing pool name.
   * @param managedComponents components to add
   */
  public void registerComponents(String pool, Collection<ManagedComponent> managedComponents) {
    ensureActive();
    for (ManagedComponent managedComponent : managedComponents) {
      registerComponent(pool, managedComponent);
    }
  }

  /**
   * Add a managed component to a pool.
   * @param pool existing pool name.
   * @param managedComponent managed component. The component must support the management type
   *                        used in the selected pool. The component must not be already managed by
   *                        another pool of the same type.
   */
  public abstract void registerComponent(String pool, ManagedComponent managedComponent);

  /**
   * Remove a managed component from a pool.
   * @param pool existing pool name.
   * @param componentId component id to remove
   * @return true if a component was actually registered and has been removed
   */
  public boolean unregisterComponent(String pool, ManagedComponentId componentId) {
    return unregisterComponent(pool, componentId.toString());
  }

  /**
   * Remove a managed component from a pool.
   * @param pool existing pool name.
   * @param componentId component id to remove
   * @return true if a component was actually registered and has been removed
   */
  public abstract boolean unregisterComponent(String pool, String componentId);

  protected void ensureActive() {
    if (isClosed) {
      throw new IllegalStateException("Already closed.");
    }
    if (!initialized) {
      throw new IllegalStateException("Not initialized.");
    }
  }

  @Override
  public void close() throws Exception {
    synchronized (this) {
      isClosed = true;
      SolrMetricProducer.super.close();
    }
  }
}
