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
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.junit.Assert;
import org.junit.Test;

public class SSLEngineLeakTest extends AbstractNPNTest
{
    @Test
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

        // Perform GC to be sure that the map is cleared
        System.gc();
        Thread.sleep(1000);

        // Check that the map is empty
        if (objects.size() != initialSize)
        {
            System.err.println(objects);
            server.dumpStdErr();
        }

        Assert.assertEquals(initialSize, objects.size());
    }

    private void avoidStackLocalVariables() throws Exception
    {
        InetSocketAddress address = prepare();
        Session session = clientFactory.newSPDYClient(SPDY.V3).connect(address, null);
        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));
    }
}
