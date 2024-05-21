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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2ServerTest extends AbstractServerTest
{
    @Test
    public void testNoPrefaceBytes() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        // No preface bytes.
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            CountDownLatch latch = new CountDownLatch(1);
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    latch.countDown();
                }
            });

            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestResponseNoContent() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(3);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                latch.countDown();
                callback.succeeded();
                return true;
            }
        });

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            AtomicReference<HeadersFrame> frameRef = new AtomicReference<>();
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onSettings(SettingsFrame frame)
                {
                    latch.countDown();
                }

                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    frameRef.set(frame);
                    latch.countDown();
                }
            });

            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = frameRef.get();
            assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            assertEquals(200, responseMetaData.getStatus());
        }
    }

    @Test
    public void testRequestResponseContent() throws Exception
    {
        byte[] content = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        CountDownLatch latch = new CountDownLatch(4);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                latch.countDown();
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            AtomicReference<HeadersFrame> headersRef = new AtomicReference<>();
            AtomicReference<DataFrame> dataRef = new AtomicReference<>();
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onSettings(SettingsFrame frame)
                {
                    latch.countDown();
                }

                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    headersRef.set(frame);
                    latch.countDown();
                }

                @Override
                public void onData(DataFrame frame)
                {
                    dataRef.set(frame);
                    latch.countDown();
                }
            });

            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = headersRef.get();
            assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            assertEquals(200, responseMetaData.getStatus());

            DataFrame responseData = dataRef.get();
            assertNotNull(responseData);
            assertArrayEquals(content, BufferUtil.toArray(responseData.getByteBuffer()));
        }
    }

    @Test
    public void testBadPingWrongPayload() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        long offset = accumulator.size();
        generator.control(accumulator, new PingFrame(new byte[8], false));
        accumulator.put(offset, (byte)0x00).put(offset, (byte)0x07);

        CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, frame.getError());
                    latch.countDown();
                }
            });

            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBadPingWrongStreamId() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        long offset = accumulator.size();

        generator.control(accumulator, new PingFrame(new byte[8], false));

        // Modify the streamId of the frame to non zero.
        accumulator.put(offset + 5, (byte)0).put(offset + 6, (byte)0).put(offset + 7, (byte)0).put(offset + 8, (byte)1);

        CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    assertEquals(ErrorCode.PROTOCOL_ERROR.code, frame.getError());
                    latch.countDown();
                }
            });

            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testCommitFailure() throws Exception
    {
        long delay = 1000;
        AtomicBoolean broken = new AtomicBoolean();
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Wait for the SETTINGS frames to be exchanged.
                Thread.sleep(delay);
                broken.set(true);
                callback.succeeded();
                return true;
            }
        });
        server.stop();

        ServerConnector connector2 = new ServerConnector(server, new HTTP2ServerConnectionFactory(new HttpConfiguration()))
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                return new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    public void write(Callback callback, ByteBuffer... buffers) throws IllegalStateException
                    {
                        if (broken.get())
                            callback.failed(new IOException("explicitly_thrown_by_test"));
                        else
                            super.write(callback, buffers);
                    }
                };
            }
        };
        server.addConnector(connector2);
        server.start();

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));
        try (Socket client = new Socket("localhost", connector2.getLocalPort()))
        {
            accumulator.writeTo(Content.Sink.from(client.getOutputStream()), false);

            // The server will close the connection abruptly since it
            // cannot write and therefore cannot even send the GO_AWAY.
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener() {});
            boolean closed = parseResponse(client, parser, 2 * delay);
            assertTrue(closed);
        }
    }

    @Test
    public void testNonISOHeader() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpChannelState.class))
        {
            startServer(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
                    // Invalid header name, the connection must be closed.
                    response.getHeaders().put("Euro_(€)", "42");
                    callback.succeeded();
                    return true;
                }
            });

            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

            try (Socket client = new Socket("localhost", connector.getLocalPort()))
            {
                OutputStream output = client.getOutputStream();
                accumulator.writeTo(Content.Sink.from(output), false);
                output.flush();

                Parser parser = new Parser(bufferPool, 8192);
                parser.init(new Parser.Listener() {});
                boolean closed = parseResponse(client, parser);

                assertTrue(closed);
            }
        }
    }

    @Test
    public void testRequestWithContinuationFrames() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));
            return accumulator;
        });
    }

    @Test
    public void testRequestWithPriorityWithContinuationFrames() throws Exception
    {
        PriorityFrame priority = new PriorityFrame(1, 13, 200, true);
        testRequestWithContinuationFrames(priority, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            generator.control(accumulator, new HeadersFrame(1, metaData, priority, true));
            return accumulator;
        });
    }

    @Test
    public void testRequestWithContinuationFramesWithEmptyHeadersFrame() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            long offset = accumulator.size();
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

            // Remember the Headers frame size
            int dataSize = ((accumulator.get(offset) * 0xFF) << 16) + ((accumulator.get(offset + 1) & 0xFF) << 8) + (accumulator.get(offset + 2) & 0xFF);

            // Set the HeadersFrame length to zero.
            accumulator.put(offset, (byte)0x00);
            accumulator.put(offset + 1, (byte)0x00);
            accumulator.put(offset + 2, (byte)0x00);

            // Take the body of the headers frame and all following frames
            RetainableByteBuffer remainder = accumulator.takeFrom(offset + 9);

            // Copy the continuation frame after the first payload.
            for (int i = 0; i < 9; i++)
                accumulator.put(remainder.get(dataSize + i));

            // Add the remainder back
            accumulator.add(remainder);

            return accumulator;
        });
    }

    @Test
    public void testRequestWithPriorityWithContinuationFramesWithEmptyHeadersFrame() throws Exception
    {
        PriorityFrame priority = new PriorityFrame(1, 13, 200, true);
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            long offset = accumulator.size();
            generator.control(accumulator, new HeadersFrame(1, metaData, priority, true));

            // Remember the Headers frame size
            int dataSize = ((accumulator.get(offset) * 0xFF) << 16) + ((accumulator.get(offset + 1) & 0xFF) << 8) + (accumulator.get(offset + 2) & 0xFF);

            // Set the HeadersFrame length to just the priority.
            accumulator.put(offset, (byte)0x00)
                .put(offset + 1, (byte)0x00)
                .put(offset + 2, (byte)PriorityFrame.PRIORITY_LENGTH);

            // take the body of the headers frame and all following frames
            RetainableByteBuffer remainder = accumulator.takeFrom(offset + 9 + PriorityFrame.PRIORITY_LENGTH);

            // Copy the continuation frame after the first payload.
            for (int i = 0; i < 9; i++)
                accumulator.put(remainder.get(dataSize + i - PriorityFrame.PRIORITY_LENGTH));

            // Add the remainder back
            accumulator.add(remainder);

            return accumulator;
        });
    }

    @Test
    public void testRequestWithContinuationFramesWithEmptyContinuationFrame() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);

            long offset = accumulator.size();
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

            RetainableByteBuffer continuation = accumulator.slice(offset + 9);
            continuation.skip(offset);
            continuation = continuation.copy();

            continuation.asMutable().put(0, (byte)0x00).put(1, (byte)0x00).put(2, (byte)0x00);
            accumulator.add(continuation);
            return accumulator;
        });
    }

    @Test
    public void testRequestWithContinuationFramesWithEmptyLastContinuationFrame() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);

            long offset = accumulator.size();
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

            RetainableByteBuffer slice = accumulator.slice();
            slice.skip(offset);
            accumulator.limit(offset);
            RetainableByteBuffer headers = slice.copy();
            slice.release();

            // Look for the last CONTINUATION frame and reset the flag.
            offset = 0;
            while (true)
            {
                int frameLength = ((headers.get(offset) & 0xFF) << 16) + ((headers.get(offset + 1) & 0xFF) << 8) + (headers.get(offset + 2) & 0xFF);
                byte flag = headers.get(offset + 4);
                if (flag == 0x04)
                {
                    // this is the last continuation frame
                    RetainableByteBuffer last = headers.takeFrom(offset);
                    accumulator.add(headers);
                    last.asMutable().put(4, (byte)0);
                    accumulator.add(last);
                    break;
                }
                offset += 9 + frameLength;
            }

            // Add a last, empty, CONTINUATION frame.
            accumulator.add(
                ByteBuffer.wrap(new byte[]{
                    0, 0, 0, // Length
                    (byte)FrameType.CONTINUATION.getType(),
                    (byte)Flags.END_HEADERS,
                    0, 0, 0, 1 // Stream ID
                }));

            return accumulator;
        });
    }

    private void testRequestWithContinuationFrames(PriorityFrame priorityFrame, Callable<RetainableByteBuffer.Mutable> frames) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                if (priorityFrame != null)
                {
                    PriorityFrame priority = frame.getPriority();
                    assertNotNull(priority);
                    assertEquals(priorityFrame.getStreamId(), priority.getStreamId());
                    assertEquals(priorityFrame.getParentStreamId(), priority.getParentStreamId());
                    assertEquals(priorityFrame.getWeight(), priority.getWeight());
                    assertEquals(priorityFrame.isExclusive(), priority.isExclusive());
                }

                serverLatch.countDown();

                MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });
        generator = new Generator(bufferPool, 4);

        RetainableByteBuffer.Mutable accumulator = frames.call();

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            accumulator.writeTo(Content.Sink.from(output), false);
            output.flush();

            assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

            CountDownLatch clientLatch = new CountDownLatch(1);
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    if (frame.isEndStream())
                        clientLatch.countDown();
                }
            });
            boolean closed = parseResponse(client, parser);

            assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
            assertFalse(closed);
        }
    }
}
