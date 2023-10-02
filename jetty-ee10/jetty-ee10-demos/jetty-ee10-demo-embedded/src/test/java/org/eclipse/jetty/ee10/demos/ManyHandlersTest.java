//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.demos;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@Disabled //TODO
public class ManyHandlersTest extends AbstractEmbeddedTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        //TODO fix me
        //server = ManyHandlers.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetParams() throws Exception
    {
        URI uri = server.getURI().resolve("/params?a=b&foo=bar");

        AtomicReference<String> contentEncoding = new AtomicReference<>();
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .onResponseHeader((r, field) ->
            {
                if (field.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncoding.set(field.getValue());
                return true;
            })
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test gzip
        // Test that Gzip was used to produce the response
        assertThat("Content-Encoding", contentEncoding.get(), containsString("gzip"));

        // test response content
        String responseBody = response.getContentAsString();
        Object jsonObj = new JSON().fromJSON(responseBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonMap = (Map<String, Object>)jsonObj;
        assertThat("Response JSON keys.size", jsonMap.keySet().size(), is(2));
    }

    @Test
    public void testGetHello() throws Exception
    {
        URI uri = server.getURI().resolve("/hello");

        AtomicReference<String> contentEncoding = new AtomicReference<>();
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .onResponseHeader((r, field) ->
            {
                if (field.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncoding.set(field.getValue());
                return true;
            })
            .send();

        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test gzip
        // Test that Gzip was used to produce the response
        assertThat("Content-Encoding", contentEncoding.get(), containsString("gzip"));

        // test expected header from wrapper
        String welcome = response.getHeaders().get("X-Welcome");
        assertThat("X-Welcome header", welcome, containsString("Greetings from WelcomeWrapHandler"));

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("Hello"));
    }
}
