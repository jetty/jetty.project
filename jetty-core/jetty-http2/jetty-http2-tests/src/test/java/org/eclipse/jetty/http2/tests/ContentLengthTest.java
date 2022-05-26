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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentLengthTest extends AbstractTest
{
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testZeroContentLengthAddedByServer(String method) throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
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
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertEquals(data.length, contentLength);
    }

    // TODO
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    @Disabled("enable when GzipHandler is implemented")
    public void testGzippedContentLengthAddedByServer(String method) throws Exception
    {
        fail();

//        byte[] data = new byte[4096];
//
//        GzipHandler gzipHandler = new GzipHandler();
//        gzipHandler.addIncludedMethods(method);
//        gzipHandler.setMinGzipSize(data.length / 2);
//        gzipHandler.setHandler(new EmptyServerHandler()
//        {
//            @Override
//            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
//            {
//                response.setContentLength(data.length);
//                response.write(true, callback, ByteBuffer.wrap(data));
//            }
//        });
//
//        start(gzipHandler);
//
//        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
//            .method(method)
//            .send();
//
//        HttpFields responseHeaders = response.getHeaders();
//        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
//        assertTrue(0 < contentLength && contentLength < data.length);
    }
}
