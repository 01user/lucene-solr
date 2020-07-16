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

package org.apache.solr.pkg;

import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrClassLoader;
import org.apache.solr.core.SolrResourceLoader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
/**
 * A {@link SolrClassLoader} that is designed to listen to a set of packages.
 * This class would register a listener each package that is loaded through this
 * if any of those packages are updated , the onReload runnable is executed
 * */
public class PackageListeningClassLoader implements SolrClassLoader , PackageListeners.Listener {
    private final CoreContainer coreContainer;
    private final SolrResourceLoader fallbackResourceLoader;
    private final Function<String, String> pkgVersionSupplier;
    /** package name and the versions that we are tracking
     */
    private Map<String ,PackageAPI.PkgVersion> packageVersions =  new HashMap<>(1);
    private final Runnable onReload;

    /**
     * @param fallbackResourceLoader The {@link SolrResourceLoader} to use if no package is specified
     * @param pkgVersionSupplier Get the version configured for a given package
     * @param onReload The callback function that should be run if a package is updated
     */
    public PackageListeningClassLoader(CoreContainer coreContainer,
                                       SolrResourceLoader fallbackResourceLoader,
                                       Function<String, String> pkgVersionSupplier,
                                       Runnable onReload) {
        this.coreContainer = coreContainer;
        this.fallbackResourceLoader = fallbackResourceLoader;
        this.pkgVersionSupplier = pkgVersionSupplier;
        this.onReload = () -> {
            //clear our current versions. When new classes are loaded from packages, fresh data can be populated
            packageVersions = new HashMap<>();
            onReload.run();
        };
    }


    @Override
    public <T> T newInstance(String cname, Class<T> expectedType, String... subpackages) {
        PluginInfo.ClassName cName = new PluginInfo.ClassName(cname);
        if(cName.pkg == null){
            return fallbackResourceLoader.newInstance(cname, expectedType, subpackages);
        } else {
            PackageLoader.Package.Version version = findPackageVersion(cName, true);
            return applyResourceLoaderAware(version, version.getLoader().newInstance(cName.className, expectedType, subpackages));
        }
    }

    /**
     * This looks up for package and also listens for that package if required
     * @param cName The class name
     */
    public PackageLoader.Package.Version findPackageVersion(PluginInfo.ClassName cName, boolean registerListener) {
        PackageLoader.Package.Version theVersion = coreContainer.getPackageLoader().getPackage(cName.pkg).getLatest(pkgVersionSupplier.apply(cName.pkg));
        if(registerListener) {
            packageVersions.put(cName.pkg, theVersion.getPkgVersion());
        }
        return theVersion;
    }

    @Override
    public MapWriter getPackageVersion(PluginInfo.ClassName cName) {
        if (cName.pkg == null) return null;
        PackageAPI.PkgVersion p = packageVersions.get(cName.pkg);
        return p == null ? null : p::writeMap;
    }

    private <T> T applyResourceLoaderAware(PackageLoader.Package.Version version, T obj) {
        if (obj instanceof ResourceLoaderAware) {
            SolrResourceLoader.assertAwareCompatibility(ResourceLoaderAware.class, obj);
            try {
                ((ResourceLoaderAware) obj).inform(version.getLoader());
                return obj;
            } catch (IOException e) {
                throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
            }
        }
        return obj;
    }

    @Override
    @SuppressWarnings({"rawtypes"})
    public <T> T newInstance(String cname, Class<T> expectedType, String[] subPackages, Class[] params, Object[] args) {
        PluginInfo.ClassName cName = new PluginInfo.ClassName(cname);
        if (cName.pkg == null) {
            return fallbackResourceLoader.newInstance(cname, expectedType, subPackages, params, args);
        } else {
            PackageLoader.Package.Version version = findPackageVersion(cName, true);
            return applyResourceLoaderAware(version, version.getLoader().newInstance(cName.className, expectedType, subPackages, params, args));
        }
    }

    @Override
    public <T> Class<? extends T> findClass(String cname, Class<T> expectedType) {
        PluginInfo.ClassName cName = new PluginInfo.ClassName(cname);
        if (cName.pkg == null) {
            return fallbackResourceLoader.findClass(cname, expectedType);
        } else {
            PackageLoader.Package.Version version = findPackageVersion(cName, true);
            return version.getLoader().findClass(cName.className, expectedType);
        }
    }

    @Override
    public String packageName() {
        return null;
    }

    @Override
    public PluginInfo pluginInfo() {
        return null;
    }

    @Override
    public void changed(PackageLoader.Package pkg, Ctx ctx) {
        PackageAPI.PkgVersion currVer = packageVersions.get(pkg.name);
        if (currVer == null) {
            //not watching this
            return;
        }
        String latestSupportedVersion = pkgVersionSupplier.apply(pkg.name);
        if (latestSupportedVersion == null) {
            //no specific version configured. use the latest
            latestSupportedVersion = pkg.getLatest().getVersion();
        }
        if (Objects.equals(currVer.version, latestSupportedVersion)) {
            //no need to update
            return;
        }
        ctx.runLater(null, onReload);
    }
}