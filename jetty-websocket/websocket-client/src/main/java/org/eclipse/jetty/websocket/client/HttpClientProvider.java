//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Method;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public final class HttpClientProvider
{
    public static HttpClient get(WebSocketContainerScope scope)
    {
        try
        {
            if (Class.forName("org.eclipse.jetty.xml.XmlConfiguration") != null)
            {
                Class<?> xmlClazz = Class.forName("org.eclipse.jetty.websocket.client.XmlBasedHttpClientProvider");
                Method getMethod = xmlClazz.getMethod("get", WebSocketContainerScope.class);
                Object ret = getMethod.invoke(null, scope);
                if ((ret != null) && (ret instanceof HttpClient))
                {
                    return (HttpClient) ret;
                }
            }
        }
        catch (Throwable ignore)
        {
            Log.getLogger(HttpClientProvider.class).ignore(ignore);
        }
        
        return DefaultHttpClientProvider.newHttpClient(scope);
    }
}
