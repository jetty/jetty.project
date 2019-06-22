//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import java.net.URL;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

class XmlHttpClientProvider implements HttpClientProvider
{
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
            Log.getLogger(XmlHttpClientProvider.class).warn("Unable to load: " + resource, t);
        }

        return null;
    }
}
