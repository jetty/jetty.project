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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.jetty.websocket.client.io.ConnectionManager;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConnectionManagerTest
{
    private void assertToSocketAddress(String uriStr, String expectedHost, int expectedPort) throws URISyntaxException
    {
        URI uri = new URI(uriStr);

        InetSocketAddress addr = ConnectionManager.toSocketAddress(uri);
        assertThat("URI (" + uri + ").host", addr.getHostName(), is(expectedHost));
        assertThat("URI (" + uri + ").port", addr.getPort(), is(expectedPort));
    }

    @Test
    public void testToSocketAddressAltWsPort() throws Exception
    {
        assertToSocketAddress("ws://localhost:8099", "localhost", 8099);
    }

    @Test
    public void testToSocketAddressAltWssPort() throws Exception
    {
        assertToSocketAddress("wss://localhost", "localhost", 443);
    }

    @Test
    public void testToSocketAddressDefaultWsPort() throws Exception
    {
        assertToSocketAddress("ws://localhost", "localhost", 80);
    }

    @Test
    public void testToSocketAddressDefaultWsPortPath() throws Exception
    {
        assertToSocketAddress("ws://localhost/sockets/chat", "localhost", 80);
    }

    @Test
    public void testToSocketAddressDefaultWssPort() throws Exception
    {
        assertToSocketAddress("wss://localhost:9443", "localhost", 9443);
    }

    @Test
    public void testToSocketAddressDefaultWssPortPath() throws Exception
    {
        assertToSocketAddress("wss://localhost/sockets/chat", "localhost", 443);
    }
}
