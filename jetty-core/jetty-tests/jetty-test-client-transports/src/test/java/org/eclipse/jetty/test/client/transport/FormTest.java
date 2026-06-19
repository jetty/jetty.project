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

package org.eclipse.jetty.test.client.transport;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class FormTest extends AbstractTest
{
    private static final int MAX_FORM_CONTENT_SIZE = 128;
    private static final int MAX_FORM_KEYS = 4;

    public static Stream<Arguments> formContentSizeScenarios()
    {
        List<Arguments> results = new ArrayList<>();
        transportsNoFCGI().forEach(transportType ->
        {
            results.add(arguments(transportType, null, FormFields.MAX_LENGTH_DEFAULT + 1, HttpStatus.PAYLOAD_TOO_LARGE_413));
            results.add(arguments(transportType, -1, null, HttpStatus.OK_200));
            results.add(arguments(transportType, 0, null, HttpStatus.PAYLOAD_TOO_LARGE_413));
            results.add(arguments(transportType, MAX_FORM_CONTENT_SIZE, FormFields.MAX_LENGTH_DEFAULT + 1, HttpStatus.PAYLOAD_TOO_LARGE_413));
        });
        return results.stream();
    }

    @ParameterizedTest
    @MethodSource("formContentSizeScenarios")
    public void testMaxFormContentSizeExceeded(TransportType transportType, Integer maxFormContentSize, Integer contentSize, int expectedStatus) throws Exception
    {
        if (contentSize == null)
            contentSize = FormFields.MAX_LENGTH_DEFAULT;

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        if (maxFormContentSize != null)
            contextHandler.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, maxFormContentSize);
        contextHandler.setHandler(new EchoFieldsHandler());

        start(transportType, contextHandler);

        BytesRequestContent formContent = new BytesRequestContent(
            MimeTypes.Type.FORM_ENCODED.asString(),
            newContent(contentSize));
        assertThat(formContent.getLength(), equalTo((long)contentSize));

        AtomicReference<Result> resultRef = new AtomicReference<>();

        client.newRequest(newURI(transportType).resolve("/test/"))
            .method(HttpMethod.POST)
            .body(formContent)
            .timeout(5, TimeUnit.SECONDS)
            .send(resultRef::set);

        await().atMost(6, TimeUnit.SECONDS).until(() -> resultRef.get() != null);
        assertEquals(expectedStatus, resultRef.get().getResponse().getStatus());
    }

    private byte[] newContent(int size)
    {
        byte[] key = "foo=".getBytes(US_ASCII);
        byte[] buf = new byte[(size - key.length) + key.length];
        Arrays.fill(buf, (byte)'x');
        System.arraycopy(key, 0, buf, 0, key.length);
        return buf;
    }

    public static Stream<Arguments> formKeysScenarios()
    {
        List<Arguments> results = new ArrayList<>();
        transportsNoFCGI().forEach(transportType ->
        {
            results.add(arguments(transportType, null));
            results.add(arguments(transportType, -1));
            results.add(arguments(transportType, 0));
            results.add(arguments(transportType, MAX_FORM_KEYS));
        });
        return results.stream();
    }

    @ParameterizedTest
    @MethodSource("formKeysScenarios")
    public void testMaxFormKeysExceeded(TransportType transportType, Integer maxFormKeys) throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        if (maxFormKeys != null && maxFormKeys >= 0)
            contextHandler.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, maxFormKeys);
        contextHandler.setHandler(new EchoFieldsHandler());

        start(transportType, contextHandler);

        int keys = (maxFormKeys == null || maxFormKeys < 0)
            ? FormFields.MAX_FIELDS_DEFAULT
            : maxFormKeys;
        // always 1 more than configured max.
        keys = keys + 1;

        Fields fields = new Fields();
        for (int i = 0; i < keys; ++i)
        {
            fields.add("key_" + i, "value_" + i);
        }
        FormRequestContent formContent = new FormRequestContent(fields);

        ContentResponse response = client.newRequest(newURI(transportType).resolve("/test/"))
            .method(HttpMethod.POST)
            .body(formContent)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE_413, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testContentTypeWithNonCharsetParameter(TransportType transportType) throws Exception
    {
        String contentType = MimeTypes.Type.FORM_ENCODED.asString() + "; p=v";

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        contextHandler.setHandler(new EchoFieldsHandler());

        start(transportType, contextHandler);

        BytesRequestContent formContent = new BytesRequestContent(
            contentType,
            "name1=value1".getBytes(UTF_8));

        ContentResponse response = client.newRequest(newURI(transportType).resolve("/test/"))
            .method(HttpMethod.POST)
            .body(formContent)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        // confirm contents on response
        String responseBody = response.getContentAsString();
        assertThat(responseBody, containsString("fields.size=1\n"));
        assertThat(responseBody, containsString("field.name1=value1\n"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testContentTypeWithIso8859Charset(TransportType transportType) throws Exception
    {
        String contentType = MimeTypes.Type.FORM_ENCODED_8859_1.asString();

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        contextHandler.setHandler(new EchoFieldsHandler());

        start(transportType, contextHandler);

        BytesRequestContent formContent = new BytesRequestContent(
            contentType,
            "name=%e9".getBytes(UTF_8));

        ContentResponse response = client.newRequest(newURI(transportType).resolve("/test/"))
            .method(HttpMethod.POST)
            .body(formContent)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        // confirm contents on response
        String responseBody = response.getContentAsString();
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
            return true;
        }
    }
}
