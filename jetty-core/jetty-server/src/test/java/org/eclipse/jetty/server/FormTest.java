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

package org.eclipse.jetty.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FormTest
{
    private static final int MAX_FORM_CONTENT_SIZE = 128;
    private static final int MAX_FORM_KEYS = 4;

    private Server server;
    private LocalConnector connector;

    private void start(ContextHandler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(handler);
        server.setHandler(contextHandlerCollection);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
    }

    public static Stream<Arguments> formContentSizeScenarios()
    {
        return Stream.of(
            Arguments.of(null, FormFields.MAX_LENGTH_DEFAULT + 1, HttpStatus.BAD_REQUEST_400),
            Arguments.of(-1, null,  HttpStatus.OK_200),
            Arguments.of(0, null, HttpStatus.BAD_REQUEST_400),
            Arguments.of(MAX_FORM_CONTENT_SIZE, FormFields.MAX_LENGTH_DEFAULT + 1, HttpStatus.BAD_REQUEST_400)
        );
    }

    @ParameterizedTest
    @MethodSource("formContentSizeScenarios")
    public void testMaxFormContentSizeExceeded(Integer maxFormContentSize, Integer contentSize, int expectedStatus) throws Exception
    {
        if (contentSize == null)
            contentSize = FormFields.MAX_LENGTH_DEFAULT;

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        if (maxFormContentSize != null)
            contextHandler.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, maxFormContentSize);
        contextHandler.setHandler(new EchoFieldsHandler());

        start(contextHandler);

        String formContent = newContent(contentSize);
        String rawRequest = """
            POST /test/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Content-Type: %s\r
            Content-Length: %d\r
            \r
            %s
            """.formatted(
                MimeTypes.Type.FORM_ENCODED.asString(),
                formContent.length(),
                formContent
            );

        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(expectedStatus, response.getStatus());
    }

    private String newContent(int size)
    {
        byte[] key = "foo=".getBytes(StandardCharsets.US_ASCII);
        byte[] buf = new byte[size + key.length];
        Arrays.fill(buf, (byte)'x');
        System.arraycopy(key, 0, buf, 0, key.length);
        return new String(buf, StandardCharsets.UTF_8);
    }

    public static Stream<Integer> formKeysScenarios()
    {
        return Stream.of(null, -1, 0, MAX_FORM_KEYS);
    }

    @ParameterizedTest
    @MethodSource("formKeysScenarios")
    public void testMaxFormKeysExceeded(Integer maxFormKeys) throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        if (maxFormKeys != null && maxFormKeys >= 0)
            contextHandler.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, maxFormKeys);
        contextHandler.setHandler(new EchoFieldsHandler());

        start(contextHandler);

        int keys = (maxFormKeys == null || maxFormKeys < 0)
            ? FormFields.MAX_FIELDS_DEFAULT
            : maxFormKeys;
        // Have at least one key.
        keys = keys + 1;
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < keys; ++i)
        {
            if (!form.isEmpty())
                form.append('&');
            form.append("key_").append(i);
            form.append('=');
            form.append("value_").append(i);
        }
        String formContent = form.toString();
        String rawRequest = """
            POST /test/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Content-Type: %s\r
            Content-Length: %d\r
            \r
            %s
            """.formatted(
            MimeTypes.Type.FORM_ENCODED.asString(),
            formContent.length(),
            formContent
        );

        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testContentTypeWithNonCharsetParameter() throws Exception
    {
        String contentType = MimeTypes.Type.FORM_ENCODED.asString() + "; p=v";

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        contextHandler.setHandler(new EchoFieldsHandler());

        start(contextHandler);

        String formContent = "name1=value1";
        String rawRequest = """
            POST /test/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Content-Type: %s\r
            Content-Length: %d\r
            \r
            %s
            """.formatted(
            contentType,
            formContent.length(),
            formContent
        );

        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        // confirm contents on response
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("fields.size=1\n"));
        assertThat(responseBody, containsString("field.name1=value1\n"));
    }

    @Test
    public void testContentTypeWithIso8859Charset() throws Exception
    {
        String contentType = MimeTypes.Type.FORM_ENCODED_8859_1.asString();

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        contextHandler.setHandler(new EchoFieldsHandler());

        start(contextHandler);
        String formContent = "name=%e9";
        String rawRequest = """
            POST /test/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Content-Type: %s\r
            Content-Length: %d\r
            \r
            %s
            """.formatted(
            contentType,
            formContent.length(),
            formContent
        );

        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        // confirm contents on response
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("fields.size=1\n"));
        assertThat(responseBody, containsString("field.name=é\n"));
    }

    private static class EchoFieldsHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            try (StringWriter writer = new StringWriter();
                 PrintWriter out = new PrintWriter(writer))
            {
                Fields fields = Request.getParameters(request);
                out.printf("fields.size=%d\n", fields.getSize());
                fields.forEach((field) ->
                    out.printf("field.%s=%s\n", field.getName(), field.getValue()));
                out.flush();
                Content.Sink.write(response, true, writer.toString(), callback);
            }
            catch (IllegalStateException e)
            {
                Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "Bad Form", e);
            }
            return true;
        }
    }
}
