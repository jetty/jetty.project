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

package org.eclipse.jetty.websocket.core.client.internal;

import java.net.URL;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XmlHttpClientProvider implements HttpClientProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(XmlHttpClientProvider.class);

    @Override
    public HttpClient newHttpClient()
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
