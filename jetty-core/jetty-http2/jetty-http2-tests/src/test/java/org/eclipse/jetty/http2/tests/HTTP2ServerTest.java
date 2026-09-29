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
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ManagedSelector;
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
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
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
        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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
                response.write(true, RetainableByteBuffer.wrap(content), callback);
                return true;
            }
        });

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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
            assertArrayEquals(content, BufferUtil.toArray(responseData.acquire()));
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

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        RetainableByteBuffer.Accumulator ping = new RetainableByteBuffer.Accumulator();
        generator.control(ping, new PingFrame(new byte[8], false));
        try (RetainableByteBuffer buffer = ping.drain())
        {
            RetainableByteBuffer.Mutable copy = RetainableByteBuffer.Mutable.allocate((int)buffer.remaining(), buffer.isDirect());
            copy.put(buffer);
            // Modify the length of the ping frame by changing the length's msb.
            copy.put(0, (byte)0x07);
            accumulator.addRetained(copy);
        }

        CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        RetainableByteBuffer.Accumulator ping = new RetainableByteBuffer.Accumulator();
        generator.control(ping, new PingFrame(new byte[8], false));
        try (RetainableByteBuffer buffer = ping.drain())
        {
            RetainableByteBuffer.Mutable copy = RetainableByteBuffer.Mutable.allocate((int)buffer.remaining(), buffer.isDirect());
            copy.put(buffer);
            // Modify the streamId of the ping frame to non-zero.
            copy.putInt(5, 1);
            accumulator.addRetained(copy);
        }

        CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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
                    public void write(RetainableByteBuffer buffer, Callback callback) throws IllegalStateException
                    {
                        if (broken.get())
                            callback.failed(new IOException("explicitly_thrown_by_test"));
                        else
                            super.write(buffer, callback);
                    }
                };
            }
        };
        server.addConnector(connector2);
        server.start();

        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));
        try (Socket client = new Socket("localhost", connector2.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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

            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

            try (Socket client = new Socket("localhost", connector.getLocalPort()))
            {
                try (RetainableByteBuffer buffer = accumulator.drain())
                {
                    buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
                }

                AtomicInteger resetFrame = new AtomicInteger();
                Parser parser = new Parser(bufferPool, 8192);
                parser.init(new Parser.Listener()
                {
                    @Override
                    public void onReset(ResetFrame frame)
                    {
                        resetFrame.set(frame.getError());
                    }
                });
                boolean closed = parseResponse(client, parser);

                assertFalse(closed);
                assertThat(resetFrame.get(), equalTo(ErrorCode.CANCEL_STREAM_ERROR.code));
            }
        }
    }

    @Test
    public void testRequestWithContinuationFrames() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
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
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
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
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            try (RetainableByteBuffer.Mutable headers = generate(new HeadersFrame(1, metaData, null, true)))
            {
                // Remember the HEADERS frame length.
                int length = frameLength(headers, 0);

                // Set the HEADERS frame length to zero.
                headers.put(0, (byte)0x00)
                    .put(1, (byte)0x00)
                    .put(2, (byte)0x00);

                // The HEADERS frame header.
                accumulator.addRetained(headers.slice(0, Frame.HEADER_LENGTH));

                // Copy the CONTINUATION frame header that follows the HEADERS frame body,
                // so that the HEADERS frame body becomes a CONTINUATION frame body.
                accumulator.addRetained(headers.slice(Frame.HEADER_LENGTH + length, Frame.HEADER_LENGTH));

                // The HEADERS frame body and all the following frames.
                accumulator.addRetained(headers.slice(Frame.HEADER_LENGTH, headers.remaining() - Frame.HEADER_LENGTH));
            }
            return accumulator;
        });
    }

    @Test
    public void testRequestWithPriorityWithContinuationFramesWithEmptyHeadersFrame() throws Exception
    {
        PriorityFrame priority = new PriorityFrame(1, 13, 200, true);
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            try (RetainableByteBuffer.Mutable headers = generate(new HeadersFrame(1, metaData, priority, true)))
            {
                // Remember the HEADERS frame length.
                int length = frameLength(headers, 0);

                // Set the HEADERS frame length to just the priority.
                headers.put(0, (byte)0x00)
                    .put(1, (byte)0x00)
                    .put(2, (byte)PriorityFrame.PRIORITY_LENGTH);

                // The HEADERS frame header and the priority.
                accumulator.addRetained(headers.slice(0, Frame.HEADER_LENGTH + PriorityFrame.PRIORITY_LENGTH));

                // Copy the CONTINUATION frame header that follows the HEADERS frame body,
                // so that the rest of the HEADERS frame body becomes a CONTINUATION frame body.
                accumulator.addRetained(headers.slice(Frame.HEADER_LENGTH + length, Frame.HEADER_LENGTH));

                // The rest of the HEADERS frame body and all the following frames.
                long offset = Frame.HEADER_LENGTH + PriorityFrame.PRIORITY_LENGTH;
                accumulator.addRetained(headers.slice(offset, headers.remaining() - offset));
            }
            return accumulator;
        });
    }

    @Test
    public void testRequestWithContinuationFramesWithEmptyContinuationFrame() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            try (RetainableByteBuffer.Mutable headers = generate(new HeadersFrame(1, metaData, null, true)))
            {
                // The offset of the first CONTINUATION frame.
                long offset = Frame.HEADER_LENGTH + frameLength(headers, 0);

                // The HEADERS frame.
                accumulator.addRetained(headers.slice(0, offset));

                // Insert an empty CONTINUATION frame, copying the first CONTINUATION frame header with a zero length.
                RetainableByteBuffer.Mutable emptyContinuation = RetainableByteBuffer.Mutable.allocate(Frame.HEADER_LENGTH, false);
                emptyContinuation.put((byte)0x00)
                    .put((byte)0x00)
                    .put((byte)0x00);
                for (int i = 3; i < Frame.HEADER_LENGTH; ++i)
                {
                    emptyContinuation.put(headers.get(offset + i));
                }
                accumulator.addRetained(emptyContinuation);

                // The CONTINUATION frames.
                accumulator.addRetained(headers.slice(offset, headers.remaining() - offset));
            }
            return accumulator;
        });
    }

    @Test
    public void testRequestWithContinuationFramesWithEmptyLastContinuationFrame() throws Exception
    {
        testRequestWithContinuationFrames(null, () ->
        {
            RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
            RetainableByteBuffer.Mutable headers = generate(new HeadersFrame(1, metaData, null, true));
            // Look for the last CONTINUATION frame and reset the flag.
            long offset = 0;
            while (true)
            {
                int length = frameLength(headers, offset);
                if (headers.get(offset + 4) == Flags.END_HEADERS)
                {
                    headers.put(offset + 4, (byte)Flags.NONE);
                    break;
                }
                offset += Frame.HEADER_LENGTH + length;
            }
            accumulator.addRetained(headers);

            // Add a last, empty, CONTINUATION frame.
            accumulator.addRetained(RetainableByteBuffer.wrap(new byte[]{
                0, 0, 0, // Length
                (byte)FrameType.CONTINUATION.getType(),
                (byte)Flags.END_HEADERS,
                0, 0, 0, 1 // Stream ID
            }));

            return accumulator;
        });
    }

    private RetainableByteBuffer.Mutable generate(Frame frame) throws Exception
    {
        RetainableByteBuffer.Accumulator accumulator = new RetainableByteBuffer.Accumulator();
        generator.control(accumulator, frame);
        try (RetainableByteBuffer buffer = accumulator.drain())
        {
            // Copy the frames into a single buffer, so that they can be modified at absolute positions.
            RetainableByteBuffer.Mutable result = RetainableByteBuffer.Mutable.allocate(Math.toIntExact(buffer.remaining()), false);
            result.put(buffer);
            return result;
        }
    }

    private static int frameLength(RetainableByteBuffer buffer, long offset)
    {
        return (buffer.getByteAsInt(offset) << 16) + (buffer.getByteAsInt(offset + 1) << 8) + buffer.getByteAsInt(offset + 2);
    }

    private void testRequestWithContinuationFrames(PriorityFrame priorityFrame, Callable<RetainableByteBuffer.Accumulator> frames) throws Exception
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

        RetainableByteBuffer.Accumulator accumulator = frames.call();

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            try (RetainableByteBuffer buffer = accumulator.drain())
            {
                buffer.read(input -> BufferUtil.writeTo(input, client.getOutputStream()));
            }

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
