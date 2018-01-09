//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;

public class UntrustedWSClient extends WebSocketClient
{
    private static final Logger LOG = Log.getLogger(UntrustedWSClient.class);
    
    public UntrustedWSClient()
    {
        super();
        super.newSessionFunction = (connection) -> new UntrustedWSSession<>(connection);
    }
    
    public UntrustedWSClient(HttpClient httpClient)
    {
        super(httpClient);
        super.newSessionFunction = (connection) -> new UntrustedWSSession<>(connection);
    }
    
    public Future<UntrustedWSSession> connect(URI toUri, ClientUpgradeRequest req) throws IOException
    {
        final Future<Session> connectFut = super.connect(new UntrustedWSEndpoint(WebSocketBehavior.CLIENT.name()), toUri, req);
        return new CompletableFuture<UntrustedWSSession>() {
            @Override
            public UntrustedWSSession get() throws InterruptedException, ExecutionException
            {
                return (UntrustedWSSession) connectFut.get();
            }
    
            @Override
            public UntrustedWSSession get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
            {
                return (UntrustedWSSession) connectFut.get(timeout, unit);
            }
        };
    }
    
    public static String getStaticWebSocketKey()
    {
        return "dGhlIHNhbXBsZSBub25jZQ==";
    }
    
    public static String genRandomWebSocketKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new String(B64Code.encode(bytes));
    }
}
