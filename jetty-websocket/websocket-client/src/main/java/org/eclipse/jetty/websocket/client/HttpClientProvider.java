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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public final class HttpClientProvider
{
    public static HttpClient get(WebSocketContainerScope scope)
    {
        Logger logger = Log.getLogger(HttpClientProvider.class);

        // Try to load a HttpClient from a jetty-websocket-httpclient.xml configuration file.
        // If WebAppClassLoader run with server class access, otherwise run normally.
        try
        {
            try
            {
                return WebAppClassLoader.runWithServerClassAccess(() -> XmlBasedHttpClientProvider.get(scope));
            }
            catch (NoClassDefFoundError | ClassNotFoundException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug("Could not use WebAppClassLoader to run with Server class access", e);
                return XmlBasedHttpClientProvider.get(scope);
            }
        }
        catch (Throwable t)
        {
            if (logger.isDebugEnabled())
                logger.debug("Failure to load HttpClient from XML", t);
        }

        return DefaultHttpClientProvider.newHttpClient(scope);
    }
}
