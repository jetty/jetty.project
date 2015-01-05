//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.examples;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class EchoWebSocketExample
{
    public static void main(String[] args)
    {
        try
        {
            WebSocketClientFactory factory = new WebSocketClientFactory();
            
            factory.getSslContextFactory().setTrustAll(true);
            factory.getSslContextFactory().addExcludeProtocols(
                    "SSL", "SSLv2", "SSLv2Hello", "SSLv3");
            factory.getSslContextFactory().addExcludeCipherSuites(
                    "SSL_RSA_WITH_DES_CBC_SHA",
                    "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                    "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                    "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA"
            );
            
            factory.start();

            WebSocketClient client = factory.newWebSocketClient();
            client.setMaxTextMessageSize(50000);
            
            URI wsUri = new URI("wss://echo.websocket.org/");
            
            EchoSocketHandler socket = new EchoSocketHandler();
            
            client.open(wsUri, socket).get(10, TimeUnit.SECONDS);
            socket.disconnectLatch.await(2L,TimeUnit.SECONDS);
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }
}
