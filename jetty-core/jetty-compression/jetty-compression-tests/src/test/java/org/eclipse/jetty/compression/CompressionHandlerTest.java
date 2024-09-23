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

package org.eclipse.jetty.compression;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class CompressionHandlerTest extends AbstractCompressionTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    /**
     * Testing how CompressionHandler acts with a single compression implementation added.
     * Configuration is only using {@code compressPath} excluding {@code *.png} paths, and including {@code /path/*}
     */
    @ParameterizedTest
    @CsvSource(textBlock = """
        # type,    resourceName,     resourceContentType,      requestedPath,              expectedIsCompressed
        br,        texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        true
        br,        texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          true
        br,        texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          true
        br,        images/logo.png,  image/png,                /images/logo.png,           false
        br,        images/logo.png,  image/png,                /path/deep/images/logo.png, false
        zstandard, texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        true
        zstandard, texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          true
        zstandard, texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          true
        zstandard, images/logo.png,  image/png,                /images/logo.png,           false
        zstandard, images/logo.png,  image/png,                /path/deep/images/logo.png, false
        gzip,      texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        true
        gzip,      texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          true
        gzip,      texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          true
        gzip,      images/logo.png,  image/png,                /images/logo.png,           false
        gzip,      images/logo.png,  image/png,                /path/deep/images/logo.png, false
        """)
    public void testCompressPathConfig(String compressionType,
                                       String resourceName,
                                       String resourceContentType,
                                       String requestedPath,
                                       boolean expectedIsCompressed) throws Exception
    {
        newCompression(compressionType);
        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.addCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .compressPathInclude("/path/*")
            .compressPathExclude("*.png")
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                response.write(true, ByteBuffer.wrap(resourceBody), callback);
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();
        client.getContentDecoderFactories().clear();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .headers((headers) ->
            {
                headers.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName());
            })
            .path(requestedPath)
            .send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));
        if (expectedIsCompressed)
        {
            assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), is(compression.getEncodingName()));
            byte[] content = decompress(response.getContent());
            assertThat(content, is(resourceBody));
        }
        else
        {
            assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
            byte[] content = response.getContent();
            assertThat(content, is(resourceBody));
        }
    }

    /**
     * Testing how CompressionHandler acts with a single compression implementation added.
     * Using all defaults for both the compression impl, and the CompressionHandler.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDefaultCompressionConfiguration(Class<Compression> compressionClass) throws Exception
    {
        newCompression(compressionClass);
        String message = "Hello Jetty!";

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.addCompression(compression);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, message, callback);
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();
        client.getContentDecoderFactories().clear();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .headers((headers) ->
            {
                headers.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName());
            })
            .path("/hello")
            .send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), is(compression.getEncodingName()));
        String content = new String(decompress(response.getContent()), UTF_8);
        assertThat(content, is(message));
    }

    /**
     * Testing how CompressionHandler acts with a single compression implementation added.
     * Using all defaults for both the compression impl, and the CompressionHandler.
     */
    @ParameterizedTest
    @MethodSource("textInputs")
    public void testDefaultCompressionConfigurationText(Class<Compression> compressionClass, String resourceName) throws Exception
    {
        newCompression(compressionClass);
        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        String resourceBody = Files.readString(resourcePath, UTF_8);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.addCompression(compression);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, resourceBody, callback);
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();
        client.getContentDecoderFactories().clear();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .headers((headers) ->
            {
                headers.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName());
            })
            .path("/textbody")
            .send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), is(compression.getEncodingName()));
        String content = new String(decompress(response.getContent()), UTF_8);
        assertThat(content, is(resourceBody));
    }

    @Test
    public void testDefaultConfiguration() throws Exception
    {
        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, "Hello World", callback);
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();

        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/hello")
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("Hello World"));
    }

    private void dumpResponse(org.eclipse.jetty.client.Response response)
    {
        System.out.printf("  %s %d %s%n", response.getVersion(), response.getStatus(), response.getReason());
        response.getHeaders().forEach((field) -> System.out.printf("  %s%n", field));
    }

    private void startServer(Handler rootHandler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(rootHandler);
        server.start();
    }
}
