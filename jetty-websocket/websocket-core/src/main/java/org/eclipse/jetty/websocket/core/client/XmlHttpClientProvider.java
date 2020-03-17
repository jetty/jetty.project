//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.client;

import java.net.URL;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class XmlHttpClientProvider implements HttpClientProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(XmlHttpClientProvider.class);

    @Override
    public HttpClient newHttpClient()
    {
        URL resource = Thread.currentThread().getContextClassLoader().getResource("jetty-websocket-httpclient.xml");
        if (resource == null)
        {
            return null;
        }

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
