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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class HttpClientCompressionTest extends AbstractTest
{
    private static final String SAMPLE_CONTENT = """
        Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc.
        Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque
        habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas.
        Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam
        at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate
        velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum.
        Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum
        eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa
        sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam
        consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque.
        Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse
        et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.
        """;

    @ParameterizedTest
    @MethodSource("transports")
    public void testEmptyAcceptEncoding(TransportType transportType) throws Exception
    {
        start(transportType, new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertEquals("", request.getHeaders().get(HttpHeader.ACCEPT_ENCODING));
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        }));

        AtomicReference<String> contentEncodingRef = new AtomicReference<>();
        ContentResponse response = client.newRequest(newURI(transportType))
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, ""))
            .onResponseHeader((r, f) ->
            {
                if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncodingRef.set(f.getValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(contentEncodingRef.get());
        assertEquals(SAMPLE_CONTENT, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testZeroQualityAcceptEncoding(TransportType transportType) throws Exception
    {
        start(transportType, new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        }));

        AtomicReference<String> contentEncodingRef = new AtomicReference<>();
        ContentResponse response = client.newRequest(newURI(transportType))
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip;q=0"))
            .onResponseHeader((r, f) ->
            {
                if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncodingRef.set(f.getValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(contentEncodingRef.get());
        assertEquals(SAMPLE_CONTENT, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdentityAcceptEncoding(TransportType transportType) throws Exception
    {
        start(transportType, new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        }));

        AtomicReference<String> contentEncodingRef = new AtomicReference<>();
        ContentResponse response = client.newRequest(newURI(transportType))
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "identity"))
            .onResponseHeader((r, f) ->
            {
                if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncodingRef.set(f.getValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(contentEncodingRef.get());
        assertEquals(SAMPLE_CONTENT, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUnacceptableAcceptEncoding(TransportType transportType) throws Exception
    {
        CompressionHandler compressionHandler = new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        });
        // Support only gzip on the server.
        compressionHandler.putCompression(new GzipCompression());
        start(transportType, compressionHandler);

        AtomicReference<String> contentEncodingRef = new AtomicReference<>();
        ContentResponse response = client.newRequest(newURI(transportType))
            // Do not accept gzip.
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "br, gzip;q=0"))
            .onResponseHeader((r, f) ->
            {
                if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncodingRef.set(f.getValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(contentEncodingRef.get());
        assertEquals(SAMPLE_CONTENT, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUnsupportedAcceptEncoding(TransportType transportType) throws Exception
    {
        CompressionHandler compressionHandler = new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        });
        // Support only gzip on the server.
        compressionHandler.putCompression(new GzipCompression());
        start(transportType, compressionHandler);

        for (String v : List.of("br, identity;q=0", "br, identity;q=0, *;q=0"))
        {
            ContentResponse response = client.newRequest(newURI(transportType))
                // Do not accept identity, so the server won't be able to respond.
                .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, v))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE_415, response.getStatus(), "Accept-Encoding: " + v);
            response.getHeaders().contains(HttpHeader.ACCEPT_ENCODING, "gzip");
        }
    }

    private static List<Arguments> transportsAndEncodings()
    {
        List<Arguments> result = new ArrayList<>();
        List<String> encodings = List.of("br", "gzip", "zstd");
        Collection<TransportType> transports = transports();
        transports.forEach(transportType ->
            encodings.forEach(encoding -> result.add(Arguments.of(transportType, encoding))));
        return result;
    }

    @ParameterizedTest
    @MethodSource("transportsAndEncodings")
    public void testQualityAcceptEncoding(TransportType transportType, String encoding) throws Exception
    {
        start(transportType, new CompressionHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, SAMPLE_CONTENT, callback);
                return true;
            }
        }));

        AtomicReference<String> contentEncodingRef = new AtomicReference<>();
        ContentResponse response = client.newRequest(newURI(transportType))
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip;q=0.5, %s;q=1.0".formatted(encoding)))
            .onResponseHeader((r, f) ->
            {
                if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                    contentEncodingRef.set(f.getValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(encoding, contentEncodingRef.get());
        assertEquals(SAMPLE_CONTENT, response.getContentAsString());
    }
}
