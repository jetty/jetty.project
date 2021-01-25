//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

        try
        {
            Thread.currentThread().setContextClassLoader(HttpClient.class.getClassLoader());
            return newHttpClient(resource);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
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
            LOG.warn("Failure to load HttpClient from XML {}", resource, t);
        }

        return null;
    }
}
