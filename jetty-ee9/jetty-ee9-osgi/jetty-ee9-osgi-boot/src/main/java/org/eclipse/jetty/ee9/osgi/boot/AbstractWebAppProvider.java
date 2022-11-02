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

package org.eclipse.jetty.ee9.osgi.boot;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee9.osgi.boot.internal.webapp.OSGiWebappClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.osgi.AbstractContextProvider;
import org.eclipse.jetty.osgi.AbstractOSGiApp;
import org.eclipse.jetty.osgi.ContextFactory;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractWebAppProvider
 * <p>
 * Base class for Jetty DeploymentManager Providers that are capable of deploying a webapp,
 * either from a bundle or an OSGi service.
 */
public abstract class AbstractWebAppProvider extends AbstractContextProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractWebAppProvider.class);

    private String _tldBundles;

    public AbstractWebAppProvider(String environment, Server server, ContextFactory contextFactory)
    {
        super(environment, server, contextFactory);
    }
}
