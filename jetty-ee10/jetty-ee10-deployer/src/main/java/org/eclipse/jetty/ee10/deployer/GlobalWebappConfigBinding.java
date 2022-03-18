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

package org.eclipse.jetty.ee10.deployer;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a way of globally setting various aspects of webapp contexts.
 *
 * Adding this binding will allow the user to arbitrarily apply a file of
 * jetty-web.xml like settings to a webapp context.
 *
 * Example usage would be:
 * - adding a server or system class setting to all webapp contexts
 * - adding an override descriptor
 *
 * Note: Currently properties from startup will not be available for
 * reference.
 */
public class GlobalWebappConfigBinding implements AppLifeCycle.Binding
{
    private static final Logger LOG = LoggerFactory.getLogger(GlobalWebappConfigBinding.class);

    private String _jettyXml;

    public String getJettyXml()
    {
        return _jettyXml;
    }

    public void setJettyXml(String jettyXml)
    {
        this._jettyXml = jettyXml;
    }

    @Override
    public String[] getBindingTargets()
    {
        return new String[]{"deploying"};
    }
    
    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        if (handler == null)
        {
            throw new NullPointerException("No Handler created for App: " + app);
        }

        if (handler instanceof WebAppContext)
        {
            WebAppContext context = (WebAppContext)handler;

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Binding: Configuring webapp context with global settings from: {}", _jettyXml);
            }

            if (_jettyXml == null)
            {
                LOG.warn("Binding: global context binding is enabled but no jetty-web.xml file has been registered");
            }

            Resource globalContextSettings = Resource.newResource(_jettyXml);

            if (globalContextSettings.exists())
            {
                XmlConfiguration jettyXmlConfig = new XmlConfiguration(globalContextSettings);
                Resource resource = Resource.newResource(app.getOriginId());
                Server server = app.getDeploymentManager().getServer();
                jettyXmlConfig.setJettyStandardIdsAndProperties(server, resource);
                AppProvider appProvider = app.getAppProvider();
                if (appProvider instanceof WebAppProvider)
                {
                    WebAppProvider webAppProvider = ((WebAppProvider)appProvider);
                    if (webAppProvider.getConfigurationManager() != null)
                        jettyXmlConfig.getProperties().putAll(webAppProvider.getConfigurationManager().getProperties());
                }
                WebAppClassLoader.runWithServerClassAccess(() ->
                {
                    jettyXmlConfig.configure(context);
                    return null;
                });
            }
            else
            {
                LOG.info("Binding: Unable to locate global webapp context settings: {}", _jettyXml);
            }
        }
    }
}
