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

package org.eclipse.jetty.server.handler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiParts;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartHandlerTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testSimpleMultiPart() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String boundary = MultiParts.extractBoundary(request.getHeaders().get(HttpHeader.CONTENT_TYPE));
                new MultiParts(boundary).parse(request)
                    .whenComplete((parts, failure) ->
                    {
                        if (parts != null)
                            Content.copy(parts.get(0).getContent(), response, callback);
                        else
                            Response.writeError(request, response, callback, failure);
                    });
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            String content = """
                --A1B2C3
                Content-Disposition: form-data; name="part"
                
                0123456789ABCDEF
                --A1B2C3--
                """;
            String header = """
                POST / HTTP/1.1
                Host: localhost
                Content-Type: multipart/form-data; boundary=A1B2C3
                Content-Length: $L
                
                """.replace("$L", String.valueOf(content.length()));

            client.write(UTF_8.encode(header));
            client.write(UTF_8.encode(content));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("0123456789ABCDEF", response.getContent());
        }
    }

    @Test
    @Disabled("Re-enable when Chunk has a retain semantic, as now it fails for buffer corruption.")
    public void testDelayedUntilMultiPart() throws Exception
    {
        DelayedHandler.UntilMultiPart delayedHandler = new DelayedHandler.UntilMultiPart();
        CountDownLatch processLatch = new CountDownLatch(1);
        delayedHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                processLatch.countDown();
                MultiParts multiParts = (MultiParts)request.getAttribute(MultiParts.class.getName());
                assertNotNull(multiParts);
                MultiPart.Part part = multiParts.get().get(0);
                Content.copy(part.getContent(), response, callback);
            }
        });
        start(delayedHandler);

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            String contentBegin = """
                --A1B2C3
                Content-Disposition: form-data; name="part"
                                
                """;
            String contentMiddle = """
                0123456789\
                """;
            String contentEnd = """
                ABCDEF
                --A1B2C3--
                """;
            String header = """
                POST / HTTP/1.1
                Host: localhost
                Content-Type: multipart/form-data; boundary=A1B2C3
                Content-Length: $L
                                
                """.replace("$L", String.valueOf(contentBegin.length() + contentMiddle.length() + contentEnd.length()));

            client.write(UTF_8.encode(header));
            client.write(UTF_8.encode(contentBegin));

            // Verify that the processor has not been called yet.
            assertFalse(processLatch.await(1, TimeUnit.SECONDS));

            client.write(UTF_8.encode(contentMiddle));

            // Verify that the processor has not been called yet.
            assertFalse(processLatch.await(1, TimeUnit.SECONDS));

            // Finish to send the content.
            client.write(UTF_8.encode(contentEnd));

            // Verify that the processor has been called.
            assertTrue(processLatch.await(5, TimeUnit.SECONDS));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("0123456789ABCDEF", response.getContent());
        }
    }

    @Test
    public void testEchoMultiPart() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String boundary = MultiParts.extractBoundary(request.getHeaders().get(HttpHeader.CONTENT_TYPE));
                new MultiParts(boundary).parse(request)
                    .whenComplete((parts, failure) ->
                    {
                        if (parts != null)
                        {
                            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "multipart/form-data; boundary=\"%s\"".formatted(parts.getBoundary()));
                            MultiPart.ContentSource source = parts.toContentSource();
                            source.setByteBufferPool(request.getComponents().getByteBufferPool());
                            source.setUseDirectByteBuffers(true);
                            source.setHeadersMaxLength(1024);
                            Content.copy(source, response, callback);
                        }
                        else
                        {
                            Response.writeError(request, response, callback, failure);
                        }
                    });
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            String requestContent = """
                --A1B2C3\r
                Content-Disposition: form-data; name="part1"\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                0123456789ABCDEF\r
                --A1B2C3\r
                Content-Disposition: form-data; name="part2"\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                FEDCBA9876543210\r
                --A1B2C3--\r
                """;
            String requestHeader = """
                POST / HTTP/1.1
                Host: localhost
                Content-Type: multipart/form-data; boundary=A1B2C3
                Content-Length: $L
                
                """.replace("$L", String.valueOf(requestContent.length()));

            client.write(UTF_8.encode(requestHeader));
            client.write(UTF_8.encode(requestContent));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String responseContent = response.getContent();
            assertEquals(requestContent, responseContent);
        }
    }

    @Test
    public void testAsyncMultiPartResponse() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String boundary = "A1B2C3";
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "multipart/form-data; boundary=\"%s\"".formatted(boundary));

                MultiPart.ContentSource source = new MultiPart.ContentSource(boundary);
                HttpFields.Mutable headers1 = HttpFields.build().put(HttpHeader.CONTENT_TYPE, "text/plain");
                assertTrue(source.addPart(new MultiPart.ByteBufferPart("part1", null, headers1, UTF_8.encode("hello"))));

                Content.copy(source, response, callback);

                new Thread(() ->
                {
                    try
                    {
                        // Allow method process(...) to return.
                        Thread.sleep(1000);

                        // Add another part and close the multipart content.
                        HttpFields.Mutable headers2 = HttpFields.build().put(HttpHeader.CONTENT_TYPE, "application/octet-stream");
                        ByteBufferContentSource content2 = new ByteBufferContentSource(ByteBuffer.allocate(32));
                        source.addPart(new MultiPart.ContentSourcePart("part2", "file2.bin", headers2, content2));
                    }
                    catch (InterruptedException x)
                    {
                        x.printStackTrace();
                    }
                    finally
                    {
                        source.close();
                    }
                }).start();
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            String request = """
                GET / HTTP/1.1
                Host: localhost
                
                """;
            client.write(UTF_8.encode(request));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String value = response.get(HttpHeader.CONTENT_TYPE);
            String contentType = HttpField.valueParameters(value, null);
            assertEquals("multipart/form-data", contentType);
            String boundary = MultiParts.extractBoundary(value);
            assertNotNull(boundary);

            MultiParts multiParts = new MultiParts(boundary).parse(new ByteBufferContentSource(ByteBuffer.wrap(response.getContentBytes())));
            MultiParts.Parts parts = multiParts.join();

            assertEquals(2, parts.size());
            MultiPart.Part part1 = parts.get(0);
            assertEquals("part1", part1.getName());
            assertEquals("hello", part1.getContentAsString(UTF_8));
            MultiPart.Part part2 = parts.get(1);
            assertEquals("part2", part2.getName());
            assertEquals("file2.bin", part2.getFileName());
            HttpFields headers2 = part2.getHttpFields();
            assertEquals(2, headers2.size());
            assertEquals("application/octet-stream", headers2.get(HttpHeader.CONTENT_TYPE));
            assertEquals(32, part2.getContent().getLength());
        }
    }
}
