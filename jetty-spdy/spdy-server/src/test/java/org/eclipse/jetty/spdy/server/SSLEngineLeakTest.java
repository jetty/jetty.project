//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class SSLEngineLeakTest extends AbstractTest
{
    @Override
    protected SPDYServerConnector newSPDYServerConnector(Server server, ServerSessionFrameListener listener)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYServerConnector(server, sslContextFactory, listener);
    }

    @Override
    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYClient.Factory(threadPool, null, sslContextFactory);
    }

    @Test
    @Ignore
    public void testSSLEngineLeak() throws Exception
    {
        System.gc();
        Thread.sleep(1000);

        Field field = NextProtoNego.class.getDeclaredField("objects");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, NextProtoNego.Provider> objects = (Map<Object, NextProtoNego.Provider>)field.get(null);
        int initialSize = objects.size();

        avoidStackLocalVariables();
        // Allow the close to arrive to the server and the selector to process it
        Thread.sleep(1000);

        // Perform GC to be sure that the WeakHashMap is cleared
        Thread.sleep(1000);
        System.gc();

        // Check that the WeakHashMap is empty
        if (objects.size()!=initialSize)
        {
            System.err.println(objects);
            server.dumpStdErr();
        }

        Assert.assertEquals(initialSize, objects.size());
    }

    private void avoidStackLocalVariables() throws Exception
    {
        Session session = startClient(startServer(null), null);
        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }
}
