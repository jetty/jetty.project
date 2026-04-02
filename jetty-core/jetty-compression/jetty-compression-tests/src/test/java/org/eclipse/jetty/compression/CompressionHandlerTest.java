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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.gzip.GzipEncoderConfig;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
     * Testing how CompressionHandler acts with a single compression implementation added.
     * Using default configuration which excludes {@code font/*} mime types from compression.
     * <p>
     * The test font file was generated using Python fonttools to avoid licensing issues:
     * </p>
     * <pre>
     * from fontTools.fontBuilder import FontBuilder
     * from fontTools.pens.ttGlyphPen import TTGlyphPen
     * fb = FontBuilder(1000, isTTF=True)
     * fb.setupGlyphOrder([".notdef", "space"])
     * fb.setupCharacterMap({32: "space"})
     * pen = TTGlyphPen(None)
     * emptyGlyph = pen.glyph()
     * fb.setupGlyf({".notdef": emptyGlyph, "space": emptyGlyph})
     * fb.setupHorizontalMetrics({".notdef": (500, 0), "space": (500, 0)})
     * fb.setupHorizontalHeader(ascent=800, descent=-200)
     * fb.setupNameTable({"familyName": "Test", "styleName": "Regular"})
     * fb.setupOS2(sTypoAscender=800, usWinAscent=800, usWinDescent=200)
     * fb.setupPost()
     * fb.setupHead(unitsPerEm=1000)
     * fb.font.flavor = "woff2"
     * fb.save("test.woff2")
     * </pre>
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDefaultCompressionExcludesFonts(Class<Compression> compressionClass) throws Exception
    {
        newCompression(compressionClass);
        Path resourcePath = MavenPaths.findTestResourceFile("fonts/test.woff2");
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        CompressionConfig config = CompressionConfig.builder()
            .defaults()
            .build();
        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "font/woff2");
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
            .path("/fonts/test.woff2")
            .send();
        dumpResponse(response);
        assertThat(response.getStatus(), is(200));
        // Font should NOT be compressed
        assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        byte[] content = response.getContent();
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
     * Test Default configuration, where all Compression implementations are discovered
     * via the ServiceLoader.
     */
    @ParameterizedTest
    @CsvSource(textBlock = """
        # type,
        br,
        zstandard,
        gzip,
        """)
    public void testETag(String compressionType) throws Exception
    {
        CompressionHandler compressionHandler = new CompressionHandler();
        newCompression(compressionType);
        compressionHandler.putCompression(compression);
        if (!compressionType.equals("gzip"))
            compressionHandler.putCompression(new GzipCompression());
        if (!compressionType.equals("br"))
            compressionHandler.putCompression(new BrotliCompression());

        CompressionConfig config = CompressionConfig.builder()
            .compressIncludeMethod("GET")
            .compressIncludePath("/compress/*")
            .build();
        compressionHandler.putConfiguration("/", config);

        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertThat(request.getHeaders().get(HttpHeader.IF_NONE_MATCH),
                    is("W/\"abc\", \"def--unknown\", \"ghi--unknown\" , *"));
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                response.getHeaders().put(HttpHeader.ETAG, "W/\"686897696a7c876b7e\"");
                Content.Sink.write(response, false, "Hello ", Callback.from(() ->
                    Content.Sink.write(response, true, "World", callback), callback::failed));
                return true;
            }
        });

        startServer(compressionHandler);
        URI serverURI = server.getURI();

        AtomicReference<String> contentEncoding = new AtomicReference<>();
        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/compress/hello")
            .headers(h ->
            {
                h.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName());
                h.put(HttpHeader.IF_NONE_MATCH, "W/\"abc--gzip\", \"def--br--unknown\", \"ghi--unknown\" , *");
            })
            .onResponseListener(new org.eclipse.jetty.client.Response.Listener()
            {
                @Override
                public boolean onHeader(org.eclipse.jetty.client.Response response, HttpField field)
                {
                    if (field.getHeader() == HttpHeader.CONTENT_ENCODING)
                        contentEncoding.compareAndSet(null, field.getValue());
                    return true;
                }
            })
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(contentEncoding.get(), is(compression.getEncodingName()));
        assertThat(new String(response.getContent(), UTF_8), is("Hello World"));
        assertThat(response.getHeaders().get(HttpHeader.ETAG), is("W/\"686897696a7c876b7e--" + compression.getEncodingName() + "\""));
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

    @ParameterizedTest
    @MethodSource("compressions")
    public void testContentSinkOutputStream(Class<Compression> compressionClass) throws Exception
    {
        newCompression(compressionClass);
        String message = "Hello Jetty!\n".repeat(10);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                try (OutputStream out = Content.Sink.asOutputStream(response))
                {
                    out.write(message.getBytes(UTF_8));
                }
                callback.succeeded();
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
     * Some status codes should never be compressed, even if they might have content.
     */
    @ParameterizedTest
    @ValueSource(ints = {
        // Status codes that cannot have content
        HttpStatus.SWITCHING_PROTOCOLS_101,
        HttpStatus.NO_CONTENT_204,
        HttpStatus.RESET_CONTENT_205,
        HttpStatus.NOT_MODIFIED_304,
        // Redirection status
        HttpStatus.MOVED_TEMPORARILY_302,
        HttpStatus.PERMANENT_REDIRECT_308,
        // Client failures
        HttpStatus.BAD_REQUEST_400,
        HttpStatus.FORBIDDEN_403,
        // Server failures
        HttpStatus.INTERNAL_SERVER_ERROR_500,
        HttpStatus.BAD_GATEWAY_502
    })
    public void testNoCompressBasedOnResponseStatus(int status) throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        gzipCompression.setByteBufferPool(pool);

        String resourceName = "texts/quotes.txt";
        String resourceContentType = "text/plain;charset=utf-8";
        String requestedPath = "/path/to/quotes.txt";

        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);

        CompressionConfig config = CompressionConfig.builder()
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(status);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                response.write(true, ByteBuffer.wrap(resourceBody), callback);
                return true;
            }
        });

        startServer(compressionHandler);

        client.getContentDecoderFactories().clear();
        client.getProtocolHandlers().remove("redirect");

        ContentResponse response = client.newRequest(server.getURI())
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip"))
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(status));
        assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING), "Status code " + status + " should not be compressed");
    }

    /**
     * <p>Test of a child handler that handles If-None-Match behavior.</p>
     *
     * <p>Child will call setStatus(304) first, then set the ETag header.</p>
     */
    @Test
    public void testIfNoneMatchNotModifiedEtag() throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        gzipCompression.setByteBufferPool(pool);

        String resourceName = "texts/quotes.txt";
        String resourceContentType = "text/plain;charset=utf-8";
        String requestedPath = "/path/to/quotes.txt";

        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);

        CompressionConfig config = CompressionConfig.builder()
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                String etag = request.getHeaders().get(HttpHeader.IF_NONE_MATCH);
                if (etag != null)
                {
                    // status first
                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                    response.getHeaders().put(HttpHeader.ETAG, etag);
                    // No write
                    callback.succeeded();
                }
                else
                {
                    response.getHeaders().put(HttpHeader.ETAG, "W\"deadbeef\"");
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap(resourceBody), callback);
                }
                return true;
            }
        });

        startServer(compressionHandler);

        client.getContentDecoderFactories().clear();

        // Initial request, to get actual etag value.
        ContentResponse response = client.newRequest(server.getURI())
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip"))
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        HttpField etagField = response.getHeaders().getField(HttpHeader.ETAG);
        assertNotNull(etagField);
        String etag = etagField.getValue();

        // Next request, using etag, should produce a 304 Not Modified response
        response = client.newRequest(server.getURI())
            .headers(h ->
            {
                h.put(HttpHeader.ACCEPT_ENCODING, "gzip");
                h.put(HttpHeader.IF_NONE_MATCH, etag);
            })
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        etagField = response.getHeaders().getField(HttpHeader.ETAG);
        assertNotNull(etagField);
        assertEquals(etag, etagField.getValue());
    }

    /**
     * <p>Test of a child handler that handles If-None-Match behavior.</p>
     *
     * <p>Child will set the ETag header first, then call setStatus(304).</p>
     */
    @Test
    public void testIfNoneMatchEtagNotModified() throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        gzipCompression.setByteBufferPool(pool);

        String resourceName = "texts/quotes.txt";
        String resourceContentType = "text/plain;charset=utf-8";
        String requestedPath = "/path/to/quotes.txt";

        Path resourcePath = MavenPaths.findTestResourceFile(resourceName);
        byte[] resourceBody = Files.readAllBytes(resourcePath);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);

        CompressionConfig config = CompressionConfig.builder()
            .build();

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, resourceContentType);
                String etag = request.getHeaders().get(HttpHeader.IF_NONE_MATCH);
                if (etag != null)
                {
                    // header first
                    response.getHeaders().put(HttpHeader.ETAG, etag);
                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                    // No write
                    callback.succeeded();
                }
                else
                {
                    response.getHeaders().put(HttpHeader.ETAG, "W\"deadbeef\"");
                    response.setStatus(HttpStatus.OK_200);
                    response.write(true, ByteBuffer.wrap(resourceBody), callback);
                }
                return true;
            }
        });

        startServer(compressionHandler);

        client.getContentDecoderFactories().clear();

        // Initial request, to get actual etag value.
        ContentResponse response = client.newRequest(server.getURI())
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip"))
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        HttpField etagField = response.getHeaders().getField(HttpHeader.ETAG);
        assertNotNull(etagField);
        String etag = etagField.getValue();

        // Next request, using etag, should produce a 304 Not Modified response
        response = client.newRequest(server.getURI())
            .headers(h ->
            {
                h.put(HttpHeader.ACCEPT_ENCODING, "gzip");
                h.put(HttpHeader.IF_NONE_MATCH, etag);
            })
            .path(requestedPath)
            .send();
        assertThat(response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        etagField = response.getHeaders().getField(HttpHeader.ETAG);
        assertNotNull(etagField);
        assertEquals(etag, etagField.getValue());
    }

    @Test
    public void testSyncFlush() throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        GzipEncoderConfig gzipEncoderConfig = new GzipEncoderConfig();
        gzipEncoderConfig.setSyncFlush(true);
        gzipCompression.setDefaultEncoderConfig(gzipEncoderConfig);
        gzipCompression.setByteBufferPool(pool);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);

        CompressionConfig config = CompressionConfig.builder()
            .build();

        CountDownLatch latch = new CountDownLatch(1);

        compressionHandler.putConfiguration("/", config);
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                try (OutputStream outputStream = Content.Sink.asOutputStream(response);
                    PrintStream writer = new PrintStream(outputStream))
                {
                    writer.print("This line should be flushed\n");
                    assertTrue(latch.await(5, TimeUnit.SECONDS), "Post-Flush Latch timed out");
                    writer.print("This line should be seen afterwards\n");
                    // trigger "last" write to allow Gzip to finish and write its trailers.
                    response.write(true, BufferUtil.EMPTY_BUFFER, callback);
                }
                catch (InterruptedException e)
                {
                    callback.failed(e);
                }
                return true;
            }
        });

        startServer(compressionHandler);

        client.getContentDecoderFactories().clear();

        URI serverURI = server.getURI();
        try (Socket socket = new Socket(serverURI.getHost(), serverURI.getPort());
              OutputStream out = socket.getOutputStream();
              InputStream in = socket.getInputStream())
        {
            String rawRequest = """
                GET /test HTTP/1.1\r
                Accept-Encoding: gzip\r
                Host: %s\r
                Connection: close\r
                \r
                """.formatted(serverURI.getAuthority());
            out.write(rawRequest.getBytes(UTF_8));
            out.flush();
            HttpTester.Response response;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
            {
                byte[] rawRespBytes = new byte[20];
                int readCount = in.read(rawRespBytes, 0, 17); // we should see the response headers at least, indicating a flush occurred.
                assertThat(readCount, is(17)); // oops we didn't get the whole line (did the network split it?)
                String respLine = new String(rawRespBytes, 0, 17, UTF_8);
                // proof that flush occurred.
                assertThat(respLine, is("HTTP/1.1 200 OK\r\n"));
                baos.write(rawRespBytes, 0, 17);
                // let servlet write again
                latch.countDown();
                // collect the rest of the body
                IO.copy(in, baos);
                response = HttpTester.parseResponse(ByteBuffer.wrap(baos.toByteArray()));
            }

            byte[] rawResponseBodyBytes = response.getContentBytes();

            try (
                InputStream encodedIn = new ByteArrayInputStream(rawResponseBodyBytes);
                GZIPInputStream gzipIn = new GZIPInputStream(encodedIn))
            {
                String decoded = IO.toString(gzipIn, UTF_8);
                assertThat(decoded, is("This line should be flushed\nThis line should be seen afterwards\n"));
            }
        }
    }

    /**
     * Test that Vary: Accept-Encoding header is present even when client does not
     * send Accept-Encoding header, as long as compression is possible for the path/method.
     * This is important for caching proxies.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testVaryHeaderWithoutAcceptEncoding(Class<Compression> compressionClass) throws Exception
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

        // Request WITHOUT Accept-Encoding header
        ContentResponse response = client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            // Intentionally NOT setting Accept-Encoding header
            .path("/hello")
            .send();

        assertThat(response.getStatus(), is(200));
        // Response should NOT be compressed (no Accept-Encoding sent)
        assertFalse(response.getHeaders().contains(HttpHeader.CONTENT_ENCODING));
        // But Vary header SHOULD be present (compression was possible)
        assertThat(response.getHeaders().get(HttpHeader.VARY), containsString("Accept-Encoding"));
        // Content should be uncompressed
        assertThat(response.getContentAsString(), is(message));
    }

    /**
     * Test that Vary header is NOT present when method does not support compression.
     */
    @Test
    public void testNoVaryHeaderForExcludedMethod() throws Exception
    {
        pool = new ArrayByteBufferPool.Tracking();
        GzipCompression gzipCompression = new GzipCompression();
        gzipCompression.setByteBufferPool(pool);

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(gzipCompression);
        // Default config only includes GET and POST for compression
        compressionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, "OK", callback);
                return true;
            }
        });

        startServer(compressionHandler);

        // OPTIONS request - not included in default compress methods
        ContentResponse response = client.newRequest(server.getURI())
            .method(HttpMethod.OPTIONS)
            .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "gzip"))
            .path("/hello")
            .send();

        assertThat(response.getStatus(), is(200));
        // Vary header should NOT be present because OPTIONS is not a compress method
        assertThat(response.getHeaders().get(HttpHeader.VARY), nullValue());
    }

    private void dumpResponse(org.eclipse.jetty.client.Response response)
    {
        Logger logger = LoggerFactory.getLogger(CompressionHandler.class);
        if (logger.isDebugEnabled())
        {
            logger.debug("{} {} {}", response.getVersion(), response.getStatus(), response.getReason());
            response.getHeaders().forEach((field) -> logger.debug("{}", field));
        }
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
