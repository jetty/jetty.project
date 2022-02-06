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

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
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
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(content);
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
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.asString(), mediaType);
                response.getOutputStream().write(content.getBytes("UTF-8"));
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
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.asString(), contentType);
                response.getOutputStream().write(content.getBytes(encoding));
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
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.asString(), contentType);
                response.getOutputStream().write(content.getBytes(encoding));
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
