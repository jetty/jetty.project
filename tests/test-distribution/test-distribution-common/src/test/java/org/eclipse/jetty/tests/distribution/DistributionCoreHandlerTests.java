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

package org.eclipse.jetty.tests.distribution;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionCoreHandlerTests extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DistributionCoreHandlerTests.class);

    @Test
    public void testInetAccessHandler() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=inetaccess,http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int httpPort = Tester.freePort();
            List<String> args = List.of(
                "jetty.inetaccess.exclude=|/excludedPath/*",
                "jetty.http.port=" + httpPort);
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();

                // Excluded path returns 403 response.
                ContentResponse response = client.newRequest("http://localhost:" + httpPort + "/excludedPath")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

                // Other paths return 404 response.
                response = client.newRequest("http://localhost:" + httpPort + "/path")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    public void testEagerContentHandler(HttpVersion httpVersion) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=resources,test-keystore,http,http2c,ee11-deploy,ee11-annotations,eager-content"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-simple-webapp:war:" + jettyVersion);
            String contextPath = "ctx";
            distribution.installWar(war, contextPath);

            int port = Tester.freePort();
            int maxRetainedBytes = 128;
            String[] properties = {
                "jetty.http.selectors=1",
                "jetty.http.port=" + port,
                "jetty.eager.content.framingOverhead=16",
                "jetty.eager.content.maxRetainedBytes=" + maxRetainedBytes,
                "jetty.eager.content.rejectWhenExceeded=false"
            };
            try (JettyHomeTester.Run run2 = distribution.start(properties))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(() ->
                {
                    ClientConnector connector = new ClientConnector();
                    HTTP2Client h2Client = new HTTP2Client(connector);
                    return new HttpClient(new HttpClientTransportDynamic(connector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client)));
                });

                IntStream.of(maxRetainedBytes / 2, maxRetainedBytes * 10).forEach(contentLength ->
                {
                    try
                    {
                        ContentResponse response = client.newRequest("http://localhost:" + port + "/" + contextPath + "/echo/content")
                            .method("POST")
                            .version(httpVersion)
                            .body(new BytesRequestContent(new byte[contentLength]))
                            .send();

                        assertEquals(HttpStatus.OK_200, response.getStatus());
                        assertEquals(contentLength, response.getContent().length);
                    }
                    catch (Exception x)
                    {
                        throw new RuntimeException(x);
                    }
                });
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    public void testEagerFormContentHandler(HttpVersion httpVersion) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=resources,test-keystore,http,http2c,ee11-deploy,ee11-annotations,eager-content"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-simple-webapp:war:" + jettyVersion);
            String contextPath = "ctx";
            distribution.installWar(war, contextPath);

            int port = Tester.freePort();
            String[] properties = {
                "jetty.http.selectors=1",
                "jetty.http.port=" + port,
                "jetty.eager.form.maxFields=16",
                "jetty.eager.form.maxLength=128"
            };
            try (JettyHomeTester.Run run2 = distribution.start(properties))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(() ->
                {
                    ClientConnector connector = new ClientConnector();
                    HTTP2Client h2Client = new HTTP2Client(connector);
                    return new HttpClient(new HttpClientTransportDynamic(connector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client)));
                });

                Map<String, List<String>> inMap = new LinkedHashMap<>();
                inMap.put("greet", List.of("Hello World"));
                inMap.put("currency", List.of("€"));
                Charset charset = StandardCharsets.UTF_8;
                ContentResponse response = client.newRequest("http://localhost:" + port + "/" + contextPath + "/echo/form")
                    .method("POST")
                    .version(httpVersion)
                    .body(new FormRequestContent(new Fields(new MultiMap<>(inMap)), charset))
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                LinkedHashMap<String, List<String>> outMap = new LinkedHashMap<>();
                UrlEncoded.decodeTo(response.getContentAsString(), (name, value) -> outMap.computeIfAbsent(name, k -> new ArrayList<>()).add(value), charset);
                assertEquals(inMap, outMap);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    public void testEagerMultiPartContentHandler(HttpVersion httpVersion) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=resources,test-keystore,http,http2c,ee11-deploy,ee11-annotations,eager-content"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);
            long fileLength = Files.size(jettyLogging);

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-simple-webapp:war:" + jettyVersion);
            String contextPath = "ctx";
            distribution.installWar(war, contextPath);

            Path work = distribution.getJettyBase().resolve("work");

            int port = Tester.freePort();
            String[] properties = {
                "jetty.http.selectors=1",
                "jetty.http.port=" + port,
                "jetty.eager.multipart.location=" + work.toAbsolutePath(),
                "jetty.eager.multipart.maxParts=3",
                "jetty.eager.multipart.maxSize=1024",
                "jetty.eager.multipart.maxMemoryPartSize=0",
                "jetty.eager.multipart.maxHeadersSize=1024",
                "jetty.eager.multipart.useFilesForPartsWithoutFileName=true"
            };
            try (JettyHomeTester.Run run2 = distribution.start(properties))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(() ->
                {
                    ClientConnector connector = new ClientConnector();
                    HTTP2Client h2Client = new HTTP2Client(connector);
                    return new HttpClient(new HttpClientTransportDynamic(connector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client)));
                });

                MultiPartRequestContent content = new MultiPartRequestContent();
                content.addPart(new MultiPart.ByteBufferPart("part1", null, HttpFields.EMPTY, StandardCharsets.UTF_8.encode("13-bytes-long")));
                content.addPart(new MultiPart.PathPart("part2", null, HttpFields.EMPTY, distribution.getJettyBase().resolve("resources/jetty-logging.properties")));
                content.close();
                ContentResponse response = client.newRequest("http://localhost:" + port + "/" + contextPath + "/echo/multipart")
                    .method("POST")
                    .version(httpVersion)
                    .body(content)
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                String[] lines = StringUtil.csvSplit(response.getContentAsString());
                assertEquals(2, lines.length);
                assertEquals(lines[0], "name=part1&length=13");
                assertEquals(lines[1], "name=part2&length=" + fileLength);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee8", "ee9", "ee10", "ee11"})
    public void testLimitHandlers(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        String[] modules = {
            "http",
            "qos",
            "size-limit",
            "thread-limit",
            "accept-rate-limit",
            "connection-limit",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env)
        };
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            String coordinates = "org.eclipse.jetty.demos:jetty-%s-demo-simple-webapp:war:%s".formatted(
                "ee8".equals(env) ? "servlet4" : "servlet5",
                jettyVersion
            );
            distribution.installWar(distribution.resolveArtifact(coordinates), "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.selectors=1", "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                URI serverUri = URI.create("http://localhost:" + port + "/test/");
                ContentResponse response = client.newRequest(serverUri)
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"brotli", "gzip", "zstandard", "all"})
    public void testCompressionHandler(String compressionName) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        String encoding = switch (compressionName)
        {
            case "brotli" -> "br";
            case "gzip" -> "gzip";
            case "zstandard" -> "zstd";
            case "all" -> "br;q=0.5, gzip;q=1, zstd;q=0.1";
            default -> throw new IllegalArgumentException();
        };

        String expected = switch (compressionName)
        {
            case "brotli" -> "br";
            case "gzip" -> "gzip";
            case "zstandard" -> "zstd";
            case "all" -> "gzip";
            default -> throw new IllegalArgumentException();
        };

        String[] modules = {
            "resources",
            "http",
            "compression-" + compressionName,
            "ee11-webapp",
            "ee11-deploy"
        };
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + String.join(",", modules)))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue(), run1.logs());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            String coordinates = "org.eclipse.jetty.demos:jetty-servlet5-demo-simple-webapp:war:" + jettyVersion;
            distribution.installWar(distribution.resolveArtifact(coordinates), "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--approve-all-licenses", "jetty.http.selectors=1", "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                URI serverUri = URI.create("http://localhost:" + port + "/test/");
                AtomicReference<String> contentEncoding = new AtomicReference<>();
                ContentResponse response = client.newRequest(serverUri)
                    .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, encoding))
                    .onResponseHeader((r, f) ->
                    {
                        if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                            contentEncoding.set(f.getValue());
                        return true;
                    })
                    .timeout(15, TimeUnit.SECONDS)
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(contentEncoding.get(), is(expected));
                assertThat(response.getContentAsString(), containsStringIgnoringCase("Hello World"));
            }
        }
    }
}
