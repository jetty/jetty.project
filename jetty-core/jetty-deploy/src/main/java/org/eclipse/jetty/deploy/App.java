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

package org.eclipse.jetty.deploy;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;

/**
 * The information about an App that is managed by the {@link DeploymentManager}
 */
public class App
{
    private final DeploymentManager _manager;
    private final AppProvider _provider;
    private final String _environmentName;
    private final String _filename;
    private final Map<String, String> _properties = new HashMap<>();
    private ContextHandler _context;

    /**
     * Create an App with specified Origin ID and archivePath
     *
     * @param manager the deployment manager
     * @param provider the app provider
     * @param filename the filename of the base resource of the application
     * @see App#getFilename()
     * @see App#getContextPath()
     */
    public App(DeploymentManager manager, AppProvider provider, String filename)
    {
        _manager = manager;
        _provider = provider;
        _filename = filename;

        try
        {
            String basename = FileID.getDot3Basename(filename);
            File properties = new File(basename + ".properties");
            if (properties.exists())
            {
                Properties p = new Properties();
                p.load(new FileInputStream(properties));
                p.keySet().stream().map(Object::toString).forEach(k -> _properties.put(k, p.getProperty(k)));
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        _environmentName = _properties.computeIfAbsent("environment", k -> _provider.getEnvironmentName());
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

    public Map<String, String> getProperties()
    {
        return _properties;
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
            _context = getAppProvider().createContextHandler(this);
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
        return _context == null ? null : _context.getContextPath();
    }

    public String getEnvironmentName()
    {
        return _environmentName;
    }

    /**
     * The origin of this {@link App} as specified by the {@link AppProvider}
     *
     * @return String representing the origin of this app.
     */
    public String getFilename()
    {
        return this._filename;
    }

    @Override
    public String toString()
    {
        return "App[" + _context + "," + _filename + "]";
    }
}
