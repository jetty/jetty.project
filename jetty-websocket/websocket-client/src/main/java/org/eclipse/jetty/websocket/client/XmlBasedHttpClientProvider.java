//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.client;

import java.net.URL;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.xml.XmlConfiguration;

class XmlBasedHttpClientProvider
{
    public static final Logger LOG = Log.getLogger(XmlBasedHttpClientProvider.class);

    public static HttpClient get(@SuppressWarnings("unused") WebSocketContainerScope scope)
    {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader == null)
            return null;

        URL resource = contextClassLoader.getResource("jetty-websocket-httpclient.xml");
        if (resource == null)
            return null;

        // Try to load a HttpClient from a jetty-websocket-httpclient.xml configuration file.
        // If WebAppClassLoader run with server class access, otherwise run normally.
        try
        {
            try
            {
                return WebAppClassLoader.runWithServerClassAccess(() -> newHttpClient(resource));
            }
            catch (NoClassDefFoundError | ClassNotFoundException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not use WebAppClassLoader to run with Server class access", e);
                return newHttpClient(resource);
            }
        }
        catch (Throwable t)
        {
            LOG.warn("Failure to load HttpClient from XML", t);
        }

        return null;
    }

    private static HttpClient newHttpClient(URL resource)
    {
        try
        {
            XmlConfiguration configuration = new XmlConfiguration(Resource.newResource(resource));
            return (HttpClient)configuration.configure();
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to load: {}", resource, t);
        }

        return null;
    }
}
