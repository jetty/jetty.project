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
import java.util.List;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
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
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

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
     * Configuration is only using {@code compressEncodings} excluding {@code zstd}, and including both
     * {@code br} and {@code gzip}
     */
    @ParameterizedTest
    @CsvSource(textBlock = """
        # type,    resourceName,     resourceContentType,      requestedPath,              expectedIsCompressed
        br,        texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        true
        br,        texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          true
        br,        texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          true
        zstandard, texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        false
        zstandard, texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          false
        zstandard, texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          false
        zstandard, images/logo.png,  image/png,                /images/logo.png,           false
        zstandard, images/logo.png,  image/png,                /path/deep/images/logo.png, false
        gzip,      texts/quotes.txt, text/plain;charset=utf-8, /path/to/quotes.txt,        true
        gzip,      texts/logo.svg,   image/svg+xml,            /path/to/logo.svg,          true
        gzip,      texts/long.txt,   text/plain;charset=utf-8, /path/to/long.txt,          true
        """)
    public void testCompressEncodingsConfig(String compressionType,
                                            String resourceName,
                                            String resourceContentType,
                                            String requestedPath,
                                            boolean expectedIsCompressed) throws Exception
    {
        newCompression(compressionType);
        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .compressIncludeEncoding("br")
            .compressIncludeEncoding("gzip")
            .compressExcludeEncoding("zstd")
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
     * Configuration is only using {@code compressMimeTypes} excluding {@code image/png}, and including both
     * {@code text/plain} and {@code image/svg+xml}
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
    public void testCompressMimeTypesConfig(String compressionType,
                                            String resourceName,
                                            String resourceContentType,
                                            String requestedPath,
                                            boolean expectedIsCompressed) throws Exception
    {
        newCompression(compressionType);
        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .compressIncludeMimeType("text/plain")
            .compressIncludeMimeType("image/svg+xml")
            .compressExcludeMimeType("image/png")
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
        String message = "Hello Jetty!\n".repeat(10);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
        compressionHandler.putCompression(compression);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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

    /**
     * Test Default configuration, where all Compression implementations are discovered
     * via the ServiceLoader.
     */
    @Test
    public void testDefaultConfiguration() throws Exception
    {
        CompressionHandler compressionHandler = new CompressionHandler();
        // Do not configure the compressions here, we want default behavior.

        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
        compressionHandler.putCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .compressIncludePath("/path/*")
            .compressExcludePath("*.png")
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
     * Configuration is only using {@code decompressMethods} excluding {@code PUT}, and including both
     * {@code GET} and {@code POST}.  This is focused on the decompression of request bodies.
     */
    @ParameterizedTest
    @CsvSource(textBlock = """
        # type,    resourceName,     resourceContentType,      requestMethod, requestedPath
        br,        texts/quotes.txt, text/plain;charset=utf-8, GET,           /path/to/quotes.txt
        br,        texts/logo.svg,   image/svg+xml,            POST,          /post/to/
        br,        texts/long.txt,   text/plain;charset=utf-8, PUT,           /put/to/
        zstandard, texts/quotes.txt, text/plain;charset=utf-8, GET,           /path/to/quotes.txt
        zstandard, texts/logo.svg,   image/svg+xml,            POST,          /post/to/
        zstandard, texts/long.txt,   text/plain;charset=utf-8, PUT,           /put/to/
        gzip,      texts/quotes.txt, text/plain;charset=utf-8, GET,           /path/to/quotes.txt
        gzip,      texts/logo.svg,   image/svg+xml,            POST,          /post/to/
        gzip,      texts/long.txt,   text/plain;charset=utf-8, PUT,           /put/to/
        """)
    public void testDecompressMethodsConfig(String compressionType,
                                            String resourceName,
                                            String resourceContentType,
                                            String requestMethod,
                                            String requestedPath) throws Exception
    {
        newCompression(compressionType);
        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .decompressIncludeMethod("GET")
            .decompressIncludeMethod("POST")
            .decompressExcludeMethod("PUT")
            .compressExcludeEncoding(compression.getEncodingName()) // don't compress the responses
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                switch (request.getMethod())
                {
                    case "GET" ->
                    {
                        response.setStatus(200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                        response.write(true, ByteBuffer.wrap(resourceBody), callback);
                    }
                    case "PUT", "POST" ->
                    {
                        ByteBuffer requestContent = Content.Source.asByteBuffer(request);
                        response.setStatus(200);
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                        response.getHeaders().put("X-Request-Content-Length", requestContent.remaining());
                        response.write(true, requestContent, callback);
                    }
                }
                return true;
            }
        });

        startServer(compressionHandler);

        URI serverURI = server.getURI();
        client.getContentDecoderFactories().clear();

        org.eclipse.jetty.client.Request request = client.newRequest(serverURI.getHost(), serverURI.getPort());
        switch (requestMethod)
        {
            case "GET" ->
            {
                request.method(HttpMethod.GET)
                    .headers((headers) ->
                    {
                        headers.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName());
                    });
            }
            case "POST" ->
            {
                byte[] compressed = compress(resourceBody);
                request.method(HttpMethod.POST)
                    .headers((headers) ->
                    {
                        headers.put(HttpHeader.CONTENT_ENCODING, compression.getEncodingName());
                    })
                    .body(new BytesRequestContent(resourceContentType, compressed));
            }
            case "PUT" ->
            {
                byte[] compressed = compress(resourceBody);
                request.method(HttpMethod.PUT)
                    .headers((headers) ->
                    {
                        headers.put(HttpHeader.CONTENT_ENCODING, compression.getEncodingName());
                    })
                    .body(new BytesRequestContent(resourceContentType, compressed));
            }
            default ->
            {
                fail("Unhandled request method: " + requestMethod);
            }
        }

        ContentResponse response = request.path(requestedPath).send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));

        assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        switch (requestMethod)
        {
            case "PUT" ->
            {
                // PUT was excluded, so expect no automatic decompression
                int originalLength = resourceBody.length;
                int responseLength = response.getContent().length;
                assertThat("Content Length", responseLength, lessThan(originalLength));
            }
            case "POST" ->
            {
                // POST was included, so expect a decompression
                String expectedLength = Integer.toString(resourceBody.length);
                assertThat("Original Request Content Length", response.getHeaders().get("X-Request-Content-Length"), is(expectedLength));
                byte[] content = response.getContent();
                assertThat(content, is(resourceBody));
            }
        }
    }

    /**
     * Testing how CompressionHandler acts with all compression implementations
     * and the {@link CompressionConfig#getCompressPreferredEncodings()} configuration,
     * with different values for {@code Accept-Encoding}, including {@code *}.
     */
    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, delimiterString = "|", textBlock = """
        acceptEncoding | preferredEncoding | expectedEncoding
                       |                   |
                       | zstd              |
                       | br, gzip          |
        gzip           |                   | gzip
        zstd, gzip     |                   | zstd
        br             | zstd              | br
        br             | gzip, br          | br
        br, gzip       | gzip, br          | gzip
        br, zstd       | gzip, br          | br
        gzip           | zstd, br          | gzip
        *              |                   | <any>
        *              | zstd, gzip        | zstd
        foo, *         |                   | <any>
        foo, *         | br                | br
        identity,*;q=0 |                   |
        identity,*;q=0 | br, gzip          |
        """)
    public void testPreferredCompressEncodings(String acceptEncodings, String preferredEncodings, String expectedEncoding) throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        gzipCompression.setByteBufferPool(pool);
        BrotliCompression brotliCompression = new BrotliCompression();
        brotliCompression.setByteBufferPool(pool);
        ZstandardCompression zstdCompression = new ZstandardCompression();
        zstdCompression.setByteBufferPool(pool);

        String resourceName = "texts/quotes.txt";
        String resourceContentType = "text/plain;charset=utf-8";
        String requestedPath = "/path/to/quotes.txt";

        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);
        compressionHandler.putCompression(brotliCompression);
        compressionHandler.putCompression(zstdCompression);

        preferredEncodings = preferredEncodings == null ? "" : preferredEncodings;
        CompressionConfig config = CompressionConfig.builder()
            .compressPreferredEncodings(List.of(StringUtil.csvSplit(preferredEncodings)))
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                response.write(true, ByteBuffer.wrap(resourceBody), callback);
                return true;
            }
        });

        startServer(compressionHandler);

        client.getContentDecoderFactories().clear();

        ContentResponse response = client.newRequest(server.getURI())
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, acceptEncodings))
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(200));
        if (StringUtil.isBlank(expectedEncoding))
            assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        else if ("<any>".equals(expectedEncoding))
            assertNotNull(response.getHeaders().get(HttpHeader.CONTENT_ENCODING));
        else
            assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), is(expectedEncoding));
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
