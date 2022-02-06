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

package org.eclipse.jetty.client.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TypedContentProviderTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFormContentProvider(Scenario scenario) throws Exception
    {
        final String name1 = "a";
        final String value1 = "1";
        final String name2 = "b";
        final String value2 = "2";
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        final String value3 = "\u20AC";

        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                assertEquals("POST", request.getMethod());
                assertEquals(MimeTypes.Type.FORM_ENCODED.asString(), request.getContentType());
                assertEquals(value1, request.getParameter(name1));
                String[] values = request.getParameterValues(name2);
                assertNotNull(values);
                assertEquals(2, values.length);
                assertThat(values, Matchers.arrayContainingInAnyOrder(value2, value3));
            }
        });

        Fields fields = new Fields();
        fields.put(name1, value1);
        fields.add(name2, value2);
        fields.add(name2, value3);
        ContentResponse response = client.FORM(scenario.getScheme() + "://localhost:" + connector.getLocalPort(), fields);

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFormContentProviderWithDifferentContentType(Scenario scenario) throws Exception
    {
        final String name1 = "a";
        final String value1 = "1";
        final String name2 = "b";
        final String value2 = "2";
        Fields fields = new Fields();
        fields.put(name1, value1);
        fields.add(name2, value2);
        final String content = FormRequestContent.convert(fields);
        final String contentType = "text/plain;charset=UTF-8";

        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                assertEquals("POST", request.getMethod());
                assertEquals(contentType, request.getContentType());
                assertEquals(content, IO.toString(request.getInputStream()));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(new FormRequestContent(fields))
            .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, contentType))
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTypedContentProviderWithNoContentType(Scenario scenario) throws Exception
    {
        final String content = "data";

        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                assertEquals("GET", request.getMethod());
                assertNotNull(request.getContentType());
                assertEquals(content, IO.toString(request.getInputStream()));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .body(new StringRequestContent(null, content, StandardCharsets.UTF_8))
            .send();

        assertEquals(200, response.getStatus());
    }
}
