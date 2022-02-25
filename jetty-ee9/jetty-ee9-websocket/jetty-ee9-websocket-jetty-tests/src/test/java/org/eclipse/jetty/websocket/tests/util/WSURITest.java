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

package org.eclipse.jetty.websocket.tests.util;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.jetty.websocket.api.util.WSURI;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WSURITest
{
    private void assertURI(URI actual, URI expected)
    {
        assertThat(actual.toASCIIString(), is(expected.toASCIIString()));
    }

    @Test
    public void testHttpsToHttps() throws URISyntaxException
    {
        assertURI(WSURI.toHttp(URI.create("https://localhost/")), URI.create("https://localhost/"));
    }

    @Test
    public void testHttpsToWss() throws URISyntaxException
    {
        assertURI(WSURI.toWebsocket(URI.create("https://localhost/")), URI.create("wss://localhost/"));
    }

    @Test
    public void testHttpToHttp() throws URISyntaxException
    {
        assertURI(WSURI.toHttp(URI.create("http://localhost/")), URI.create("http://localhost/"));
    }

    @Test
    public void testHttpToWs() throws URISyntaxException
    {
        assertURI(WSURI.toWebsocket(URI.create("http://localhost/")), URI.create("ws://localhost/"));
        assertURI(WSURI.toWebsocket(URI.create("http://localhost:8080/deeper/")), URI.create("ws://localhost:8080/deeper/"));
        assertURI(WSURI.toWebsocket("http://localhost/"), URI.create("ws://localhost/"));
        assertURI(WSURI.toWebsocket("http://localhost/", null), URI.create("ws://localhost/"));
        assertURI(WSURI.toWebsocket("http://localhost/", "a=b"), URI.create("ws://localhost/?a=b"));
    }

    @Test
    public void testWssToHttps() throws URISyntaxException
    {
        assertURI(WSURI.toHttp(URI.create("wss://localhost/")), URI.create("https://localhost/"));
    }

    @Test
    public void testWssToWss() throws URISyntaxException
    {
        assertURI(WSURI.toWebsocket(URI.create("wss://localhost/")), URI.create("wss://localhost/"));
    }

    @Test
    public void testWsToHttp() throws URISyntaxException
    {
        assertURI(WSURI.toHttp(URI.create("ws://localhost/")), URI.create("http://localhost/"));
    }

    @Test
    public void testWsToWs() throws URISyntaxException
    {
        assertURI(WSURI.toWebsocket(URI.create("ws://localhost/")), URI.create("ws://localhost/"));
    }
}
