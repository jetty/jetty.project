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

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * The information about an App that is managed by the {@link DeploymentManager}.
 */
public class App
{
    private final DeploymentManager _manager;
    private final AppProvider _provider;
    private final Path _path;
    private final Map<String, String> _properties = new HashMap<>();
    private ContextHandler _context;

    /**
     * Create an App with specified Origin ID and archivePath
     * <p>
     * Any properties file that exists with the same {@link FileID#getBasename(Path)} as the
     * filename passed will be used to initialize the properties returned by {@link #getProperties()}.
     * @param manager the deployment manager
     * @param provider the app provider
     * @param path the path to the application directory, war file or XML descriptor
     * @see App#getContextPath()
     */
    public App(DeploymentManager manager, AppProvider provider, Path path)
    {
        _manager = manager;
        _provider = provider;
        _path = path;

        try
        {
            String basename = FileID.getBasename(path);
            Path properties = path.getParent().resolve(basename + ".properties");
            if (Files.exists(properties))
            {
                Properties p = new Properties();
                p.load(new FileInputStream(properties.toFile()));
                p.keySet().stream().map(Object::toString).forEach(k -> _properties.put(k, p.getProperty(k)));
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
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
     * @return the contextPath for the App
     */
    public String getContextPath()
    {
        return _context == null ? null : _context.getContextPath();
    }

    /**
     * Get the environment name.
     * @return The {@link org.eclipse.jetty.util.component.Environment} name for the application
     * if set with the {@link Deployable#ENVIRONMENT} property, else null.
     */
    public String getEnvironmentName()
    {
        return getProperties().get(Deployable.ENVIRONMENT);
    }

    /**
     * The origin of this {@link App} as specified by the {@link AppProvider}
     *
     * @return String representing the origin of this app.
     */
    public Path getPath()
    {
        return _path;
    }

    @Override
    public String toString()
    {
        return "App@%x[%s,%s,%s]".formatted(hashCode(), getEnvironmentName(), _context, _path);
    }
}
