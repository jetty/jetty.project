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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentLengthTest extends AbstractTest
{
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testZeroContentLengthAddedByServer(String method) throws Exception
    {
        start(new EmptyServerHandler());

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertEquals(0, contentLength);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testContentLengthAddedByServer(String method) throws Exception
    {
        byte[] data = new byte[512];
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(data);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertEquals(data.length, contentLength);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testGzippedContentLengthAddedByServer(String method) throws Exception
    {
        byte[] data = new byte[4096];

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMethods(method);
        gzipHandler.setMinGzipSize(data.length / 2);
        gzipHandler.setHandler(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentLength(data.length);
                response.getOutputStream().write(data);
            }
        });

        start(gzipHandler);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertTrue(0 < contentLength && contentLength < data.length);
    }
}
