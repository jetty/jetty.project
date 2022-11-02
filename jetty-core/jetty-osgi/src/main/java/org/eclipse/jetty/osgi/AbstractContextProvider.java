//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractContextProvider
 *
 * Base class for DeploymentManager Providers that can deploy ContextHandlers into
 * Jetty that have been discovered via OSGI either as bundles or services.
 */
public abstract class AbstractContextProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    private DeploymentManager _deploymentManager;
    private Server _server;
    private ContextFactory _contextFactory;
    private String _environment;
    private final Map<String, String> _properties = new HashMap<>();

    public AbstractContextProvider(String environment, Server server, ContextFactory contextFactory)
    {
        _environment = Objects.requireNonNull(environment);
        _server = Objects.requireNonNull(server);
        _contextFactory = Objects.requireNonNull(contextFactory);
    }

    public Server getServer()
    {
        return _server;
    }

    public Map<String, String> getProperties()
    {
        return _properties;
    }
    
    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;

        //Create a ContextHandler suitable to deploy in OSGi
        ContextHandler h = _contextFactory.createContextHandler(this, app);

        return h;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    @Override
    public String getEnvironmentName()
    {
        return _environment;
    }

    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }
    
    /**
     * Get the extractWars.
     * This is equivalent to getting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @return the extractWars
     */
    public boolean isExtractWars()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.EXTRACT_WARS));
    }

    /**
     * Set the extractWars.
     * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _properties.put(Deployable.EXTRACT_WARS, Boolean.toString(extractWars));
    }

    /**
     * Get the parentLoaderPriority.
     * This is equivalent to getting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.PARENT_LOADER_PRIORITY));
    }

    /**
     * Set the parentLoaderPriority.
     * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _properties.put(Deployable.PARENT_LOADER_PRIORITY, Boolean.toString(parentLoaderPriority));
    }

    /**
     * Get the defaultsDescriptor.
     * This is equivalent to getting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _properties.get(Deployable.DEFAULTS_DESCRIPTOR);
    }

    /**
     * Set the defaultsDescriptor.
     * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _properties.put(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names as a comma separated list
     */
    public void setConfigurationClasses(String configurations)
    {
        setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _properties.put(Deployable.CONFIGURATION_CLASSES, (configurations == null)
            ? null
            : String.join(",", configurations));
    }

    /**
     *
     * This is equivalent to getting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @return The configuration class names.
     */
    public String[] getConfigurationClasses()
    {
        String cc = _properties.get(Deployable.CONFIGURATION_CLASSES);
        return cc == null ? new String[0] : cc.split(",");
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(String directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory);
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory.getAbsolutePath());
    }

    /**
     * Get the temporary directory for deployment.
     * <p>
     * This is equivalent to getting the {@link Deployable#BASE_TEMP_DIR} property.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    public File getTempDir()
    {
        String tmpDir = _properties.get(Deployable.BASE_TEMP_DIR);
        return tmpDir == null ? null : new File(tmpDir);
    }
    
    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the context instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
       _properties.put(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, tldBundles);
    }

    /**
     * @return The list of bundles that contain tld jars that should be setup on
     * the contexts create here.
     */
    public String getTldBundles()
    {
        return _properties.get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
    }
    
    public boolean isDeployable(Bundle bundle)
    {
        if (bundle == null)
            return false;
        
        //check environment matches
        if (getEnvironmentName().equalsIgnoreCase(bundle.getHeaders().get(Deployable.ENVIRONMENT)))
            return true;
        
        return false;
    }
    
    public boolean isDeployable(ServiceReference service)
    {
        if (service == null)
            return false;
        
        //has it been deployed before?
        if (!StringUtil.isBlank((String)service.getProperty(OSGiWebappConstants.WATERMARK)))
            return false;
        
        //destined for our environment?
        if (getEnvironmentName().equalsIgnoreCase((String)service.getProperty(Deployable.ENVIRONMENT)))
            return true;

        return false;
    }
}
