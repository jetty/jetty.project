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

package org.eclipse.jetty.client.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class TypedContentProviderTest extends AbstractHttpClientServerTest
{
    public TypedContentProviderTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testFormContentProvider() throws Exception
    {
        final String name1 = "a";
        final String value1 = "1";
        final String name2 = "b";
        final String value2 = "2";
        final String value3 = "\u20AC";

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals("POST", request.getMethod());
                Assert.assertEquals(MimeTypes.Type.FORM_ENCODED.asString(), request.getContentType());
                Assert.assertEquals(value1, request.getParameter(name1));
                String[] values = request.getParameterValues(name2);
                Assert.assertNotNull(values);
                Assert.assertEquals(2, values.length);
                Assert.assertThat(values, Matchers.arrayContainingInAnyOrder(value2, value3));
            }
        });

        Fields fields = new Fields();
        fields.put(name1, value1);
        fields.add(name2, value2);
        fields.add(name2, value3);
        ContentResponse response = client.FORM(scheme + "://localhost:" + connector.getLocalPort(), fields);

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testFormContentProviderWithDifferentContentType() throws Exception
    {
        final String name1 = "a";
        final String value1 = "1";
        final String name2 = "b";
        final String value2 = "2";
        Fields fields = new Fields();
        fields.put(name1, value1);
        fields.add(name2, value2);
        final String content = FormContentProvider.convert(fields);
        final String contentType = "text/plain;charset=UTF-8";

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals("POST", request.getMethod());
                Assert.assertEquals(contentType, request.getContentType());
                Assert.assertEquals(content, IO.toString(request.getInputStream()));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(new FormContentProvider(fields))
                .header(HttpHeader.CONTENT_TYPE, contentType)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testTypedContentProviderWithNoContentType() throws Exception
    {
        final String content = "data";

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals("GET", request.getMethod());
                Assert.assertNotNull(request.getContentType());
                Assert.assertEquals(content, IO.toString(request.getInputStream()));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(new StringContentProvider(null, content, StandardCharsets.UTF_8))
                .send();

        Assert.assertEquals(200, response.getStatus());
    }
}
