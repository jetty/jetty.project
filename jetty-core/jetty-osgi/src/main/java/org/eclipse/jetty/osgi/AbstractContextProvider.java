//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Objects;

import org.eclipse.jetty.deploy.ContextHandlerManagement;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

/**
 * AbstractContextProvider
 *
 * <p>
 * Base class for DeploymentManager Providers that can deploy ContextHandlers into
 * Jetty that have been discovered via OSGI either as bundles or services.
 * </p>
 */
public abstract class AbstractContextProvider extends AbstractLifeCycle
{
    private final ContextHandlerManagement _contextHandlerManagement;
    private ContextFactory _contextFactory;
    private String _environment;
    private final Attributes _attributes = new Attributes.Mapped();

    public AbstractContextProvider(ContextHandlerManagement contextHandlerManagement, String environment, ContextFactory contextFactory)
    {
        _contextHandlerManagement = contextHandlerManagement;
        _environment = Objects.requireNonNull(environment);
        _contextFactory = Objects.requireNonNull(contextFactory);
    }

    public Server getServer()
    {
        return _contextHandlerManagement.getServer();
    }

    public Attributes getAttributes()
    {
        return _attributes;
    }

    public ContextHandler createContextHandler(OSGiDeployableBundleMetadata metadata) throws Exception
    {
        if (metadata == null)
            return null;

        // Create a ContextHandler suitable to deploy in OSGi
        ContextHandler contextHandler = _contextFactory.createContextHandler(this, metadata);
        contextHandler.setID(metadata.getID());
        OSGiDeployableBundleMetadata.setBundle(contextHandler, metadata.getBundle());
        return contextHandler;
    }

    public String getEnvironmentName()
    {
        return _environment;
    }

    public ContextHandlerManagement getContextHandlerManagement()
    {
        return _contextHandlerManagement;
    }

    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the context instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
        _attributes.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, tldBundles);
    }

    /**
     * @return The list of bundles that contain tld jars that should be setup on
     * the contexts create here.
     */
    public String getTldBundles()
    {
        return (String)_attributes.getAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
    }
    
    public boolean isDeployable(Bundle bundle)
    {
        if (bundle == null)
            return false;
        
        //check environment matches
        if (getEnvironmentName().equalsIgnoreCase(bundle.getHeaders().get(OSGiWebappConstants.JETTY_ENVIRONMENT)))
            return true;
        
        return false;
    }

    public boolean isDeployable(ServiceReference<?> service)
    {
        if (service == null)
            return false;
        
        //has it been deployed before?
        if (!StringUtil.isBlank((String)service.getProperty(OSGiWebappConstants.WATERMARK)))
            return false;
        
        //destined for our environment?
        if (getEnvironmentName().equalsIgnoreCase((String)service.getProperty(OSGiWebappConstants.JETTY_ENVIRONMENT)))
            return true;

        return false;
    }
}
