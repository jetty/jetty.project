//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CloseTest extends AbstractServerTest
{
    @Test
    public void testClientAbruptlyClosesConnection() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    sessionRef.set(stream.getSession());
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                    // Reply with HEADERS.
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    closeLatch.await(5, TimeUnit.SECONDS);
                    return null;
                }
                catch (InterruptedException x)
                {
                    return null;
                }
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    try
                    {
                        // Close the connection just after
                        // receiving the response headers.
                        client.close();
                        closeLatch.countDown();
                    }
                    catch (IOException x)
                    {
                        throw new RuntimeIOException(x);
                    }
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            // We need to give some time to the server to receive and process the TCP FIN.
            Thread.sleep(1000);

            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }

    @Test
    public void testClientSendsGoAwayButDoesNotCloseConnectionServerCloses() throws Exception
    {
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));
        generator.control(lease, new GoAwayFrame(1, ErrorCode.NO_ERROR.code, "OK".getBytes("UTF-8")));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            // Don't close the connection; the server should close.

            RecordingParserListener parserListener = new RecordingParserListener();
            Parser parser = new Parser(byteBufferPool, parserListener, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            parserListener.takeEvent("onHeaders streamId=1, isEndStream=true");

            // Wait for the server to close.
            Thread.sleep(1000);

            // Client received the TCP FIN from server.
            assertEquals(-1, client.getInputStream().read());

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }

    @Test
    public void testServerSendsGoAwayClientDoesNotCloseServerIdleTimeout() throws Exception
    {
        final long idleTimeout = 1000;
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                stream.getSession().close(ErrorCode.NO_ERROR.code, "OK", Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            RecordingParserListener parserListener = new RecordingParserListener();
            Parser parser = new Parser(byteBufferPool, parserListener, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            parserListener.takeEvent("onHeaders streamId=1, isEndStream=true");
            parserListener.takeEvent("onGoAway lastStreamId=1");

            // Don't close the connection.

            // Wait for the server to idle timeout.
            Thread.sleep(2 * idleTimeout);

            // Client received the TCP FIN from server.
            assertEquals(-1, client.getInputStream().read());

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }

    /**
     * Servers should continue to service existing streams after a GOAWAY is sent.
     * https://github.com/eclipse/jetty.project/issues/2788
     */
    @Test
    public void testServerShutdownIsGraceful() throws Exception
    {
        final long idleTimeout = 1000;
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);
                if (stream.getId() == 3) {
                    stream.getSession().close(ErrorCode.NO_ERROR.code, "OK", Callback.NOOP);
                }
                return new Stream.Listener.Adapter() {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback) {
                        // This echo server just echos back whatever the caller sent.
                        stream.data(frame, Callback.NOOP);
                    }
                };
            }
        });
        connector.setIdleTimeout(idleTimeout);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("POST", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, false));
        generator.data(lease, new DataFrame(1, ByteBuffer.wrap("hello 1".getBytes()), false), 7);
        generator.control(lease, new HeadersFrame(3, metaData, null, false));
        generator.data(lease, new DataFrame(3, ByteBuffer.wrap("hello 3".getBytes()), false), 7);
        generator.data(lease, new DataFrame(1, ByteBuffer.wrap("goodbye 1".getBytes()), true), 9);
        generator.data(lease, new DataFrame(3, ByteBuffer.wrap("goodbye 3".getBytes()), true), 9);

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            RecordingParserListener parserListener = new RecordingParserListener();
            Parser parser = new Parser(byteBufferPool, parserListener, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            parserListener.takeEvent("onHeaders streamId=1, isEndStream=false");
            parserListener.takeEvent("onData data='hello 1', isEndStream=false");
            parserListener.takeEvent("onHeaders streamId=3, isEndStream=false");
            parserListener.takeEvent("onGoAway lastStreamId=3");
            parserListener.takeEvent("onData data='hello 3', isEndStream=false");
            parserListener.takeEvent("onData data='goodbye 1', isEndStream=true");
            parserListener.takeEvent("onData data='goodbye 3', isEndStream=true");

            // Wait for the server to idle timeout.
            Thread.sleep(2 * idleTimeout);

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());

            // Client gets EOF.
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    public void noNewStreamAfterGoaway() throws Exception
    {
        final long idleTimeout = 1000;
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                if (stream.getId() == 1) {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    stream.getSession().close(ErrorCode.NO_ERROR.code, "OK", Callback.NOOP);
                } else {
                    // TODO: this should happen in HTTP2ServerSession, which should remember the
                    //     lowest-sent lastStreamId and refuse streams with a greater stream ID.
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code), Callback.NOOP);
                }
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));
        generator.control(lease, new HeadersFrame(3, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            RecordingParserListener parserListener = new RecordingParserListener();
            Parser parser = new Parser(byteBufferPool, parserListener, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            parserListener.takeEvent("onHeaders streamId=1, isEndStream=true");
            parserListener.takeEvent("onGoAway lastStreamId=1");
            parserListener.takeEvent("onReset streamId=3, error=7");

            // Wait for the server to idle timeout.
            Thread.sleep(2 * idleTimeout);

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());

            // Client gets EOF.
            assertEquals(-1, client.getInputStream().read());
        }
    }

    class RecordingParserListener extends Parser.Listener.Adapter
    {
        final BlockingDeque<String> events = new LinkedBlockingDeque<>();

        void takeEvent(String expected) throws InterruptedException
        {
            String event = events.poll(5, TimeUnit.SECONDS);
            if (event == null)
            {
                throw new AssertionError("timeout awaiting expected event:\n  " + expected);
            }
            assertEquals(expected, event);
        }

        @Override public void onHeaders(HeadersFrame frame)
        {
            events.add("onHeaders streamId=" + frame.getStreamId()
                + ", isEndStream=" + frame.isEndStream());
        }

        @Override public void onData(DataFrame frame)
        {
            byte[] bytes = new byte[frame.remaining()];
            frame.getData().get(bytes);
            events.add("onData data='" + new String(bytes)
                + "', isEndStream=" + frame.isEndStream());
        }

        @Override public void onGoAway(GoAwayFrame frame)
        {
            events.add("onGoAway lastStreamId=" + frame.getLastStreamId());
        }

        @Override public void onConnectionFailure(int error, String reason)
        {
            events.add("onConnectionFailure error=" + error + ", reason=" + reason);
        }

        @Override public void onReset(ResetFrame frame) {
            events.add("onReset streamId=" + frame.getStreamId() + ", error=" + frame.getError());
        }

        @Override public void onStreamFailure(int streamId, int error, String reason)
        {
            events.add("onStreamFailure streamId=" + streamId
                + ", error=" + error + ", reason=" + reason);
        }
    }
}
