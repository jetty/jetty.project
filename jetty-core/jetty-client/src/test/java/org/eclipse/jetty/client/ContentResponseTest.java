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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ContentResponseTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseWithoutContentType(Scenario scenario) throws Exception
    {
        final byte[] content = new byte[1024];
        new Random().nextBytes(content);
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertArrayEquals(content, response.getContent());
        assertNull(response.getMediaType());
        assertNull(response.getEncoding());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseWithMediaType(Scenario scenario) throws Exception
    {
        final String content = "The quick brown fox jumped over the lazy dog";
        final String mediaType = "text/plain";
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, mediaType);
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        assertEquals(mediaType, response.getMediaType());
        assertNull(response.getEncoding());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseWithContentType(Scenario scenario) throws Exception
    {
        final String content = "The quick brown fox jumped over the lazy dog";
        final String mediaType = "text/plain";
        final String encoding = "UTF-8";
        final String contentType = mediaType + "; charset=" + encoding;
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
                response.write(true, ByteBuffer.wrap(content.getBytes(encoding)), callback);
                return true;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        assertEquals(mediaType, response.getMediaType());
        assertEquals(encoding, response.getEncoding());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseWithContentTypeWithQuotedCharset(Scenario scenario) throws Exception
    {
        final String content = "The quick brown fox jumped over the lazy dog";
        final String mediaType = "text/plain";
        final String encoding = "UTF-8";
        final String contentType = mediaType + "; charset=\"" + encoding + "\"";
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
                response.write(true, ByteBuffer.wrap(content.getBytes(encoding)), callback);
                return true;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        assertEquals(mediaType, response.getMediaType());
        assertEquals(encoding, response.getEncoding());
    }
}
