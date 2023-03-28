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

package org.eclipse.jetty.deploy;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.AttributesMap;

/**
 * The information about an App that is managed by the {@link DeploymentManager}
 */
public class App
{
    private final DeploymentManager _manager;
    private final AppProvider _provider;
    private final String _originId;
    private ContextHandler _context;

    /**
     * Create an App with specified Origin ID and archivePath
     *
     * @param manager the deployment manager
     * @param provider the app provider
     * @param originId the origin ID (The ID that the {@link AppProvider} knows
     * about)
     * @see App#getOriginId()
     * @see App#getContextPath()
     */
    public App(DeploymentManager manager, AppProvider provider, String originId)
    {
        _manager = manager;
        _provider = provider;
        _originId = originId;
    }

    /**
     * Create an App with specified Origin ID and archivePath
     *
     * @param manager the deployment manager
     * @param provider the app provider
     * @param originId the origin ID (The ID that the {@link AppProvider} knows
     * about)
     * @param context Some implementations of AppProvider might have to use an
     * already created ContextHandler.
     * @see App#getOriginId()
     * @see App#getContextPath()
     */
    public App(DeploymentManager manager, AppProvider provider, String originId, ContextHandler context)
    {
        this(manager, provider, originId);
        _context = context;
    }

    /**
     * @return The deployment manager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _manager;
    }

    /**
     * @return The AppProvider
     */
    public AppProvider getAppProvider()
    {
        return _provider;
    }

    /**
     * Get ContextHandler for the App.
     *
     * Create it if needed.
     *
     * @return the {@link ContextHandler} to use for the App when fully started.
     * (Portions of which might be ignored when App is not yet
     * {@link AppLifeCycle#DEPLOYED} or {@link AppLifeCycle#STARTED})
     * @throws Exception if unable to get the context handler
     */
    public ContextHandler getContextHandler() throws Exception
    {
        if (_context == null)
        {
            _context = getAppProvider().createContextHandler(this);

            AttributesMap attributes = _manager.getContextAttributes();
            if (attributes != null && attributes.size() > 0)
            {
                // Merge the manager attributes under the existing attributes
                attributes = new AttributesMap(attributes);
                attributes.addAll(_context.getAttributes());
                _context.setAttributes(attributes);
            }
        }
        return _context;
    }

    /**
     * The context path {@link App} relating to how it is installed on the
     * jetty server side.
     *
     * NOTE that although the method name indicates that this is a unique
     * identifier, it is not, as many contexts may have the same contextPath,
     * yet different virtual hosts.
     *
     * @return the context path for the App
     * @deprecated Use getContextPath instead.
     */
    public String getContextId()
    {
        return getContextPath();
    }

    /**
     * The context path {@link App} relating to how it is installed on the
     * jetty server side.
     *
     * @return the contextPath for the App
     */
    public String getContextPath()
    {
        if (this._context == null)
        {
            return null;
        }
        return this._context.getContextPath();
    }

    /**
     * The origin of this {@link App} as specified by the {@link AppProvider}
     *
     * @return String representing the origin of this app.
     */
    public String getOriginId()
    {
        return this._originId;
    }

    @Override
    public String toString()
    {
        return "App[" + _context + "," + _originId + "]";
    }
}
