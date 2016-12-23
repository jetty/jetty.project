//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.FilterInputStream;
import java.io.IOException;
import org.eclipse.jetty.server.HttpOutputTest.ContentHandler;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import static org.hamcrest.Matchers.containsString;
import org.junit.After;
import static org.junit.Assert.assertThat;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class ChunkEOFContentHttpOutputTest
{
    private Server _server;
    private LocalConnector _connector;
    private ContentHandler _handler;
    private HotSwapHandler _swap;

    @Before
    public void init() throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        http.getHttpConfiguration().setOutputBufferSize(4096);
        http.getHttpConfiguration().setChunkedResponsesOverNonPersistentConnectionsEnabled(true);

        _connector = new LocalConnector(_server,http,null);
        _server.addConnector(_connector);
        _swap=new HotSwapHandler();
        _handler=new ContentHandler();
        _swap.setHandler(_handler);
        _server.setHandler(_swap);
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testChunkedBig() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentInputStream = new FilterInputStream(big.getInputStream())
        {
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                int filled = super.read(b,off,len>2000?2000:len);
                return filled;
            }
        };
        String response = _connector.getResponses(
            "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("1\tThis is a big file"));
        assertThat(response, containsString("400\tThis is a big file"));
        assertThat(response, containsString("\r\n0\r\n"));
    }

    @Test
    public void testNoContent() throws Exception
    {
        String response = _connector.getResponses(
            "GET / HTTP/1.1\nHost: localhost:80\n\n"
        );

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Transfer-Encoding: chunked")));
        assertThat(response, containsString("Content-Length: 0"));
    }

    @Test
    public void testNoContentConnectionClose() throws Exception
    {
        String response = _connector.getResponses(
            "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Transfer-Encoding: chunked")));
        assertThat(response, containsString("Content-Length: 0"));
        assertThat(response, containsString("Connection: close"));
    }

    @Test
    public void testHttp1_0() throws Exception
    {
        String response = _connector.getResponses(
            "GET / HTTP/1.0\nHost: localhost:80\n\n"
        );

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Transfer-Encoding: chunked")));
        assertThat(response, containsString("Content-Length: 0"));
        assertThat(response, Matchers.not(containsString("Connection: close")));
    }
}