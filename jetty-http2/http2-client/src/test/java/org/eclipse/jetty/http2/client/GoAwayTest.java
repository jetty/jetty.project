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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.CloseState;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.SimpleFlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GoAwayTest extends AbstractTest
{
    @Test
    public void testClientGoAwayServerReplies() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientLatch.countDown();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientSession.close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
            }
        });

        Assertions.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertSame(CloseState.CLOSED, ((HTTP2Session)serverSessionRef.get()).getCloseState());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
        Assertions.assertSame(CloseState.CLOSED, ((HTTP2Session)clientSession).getCloseState());
    }

    @Test
    public void testServerGoAwayWithInFlightStreamClientFailsStream() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        MetaData.Request request1 = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        CountDownLatch streamFailureLatch = new CountDownLatch(1);
        clientSession.newStream(new HeadersFrame(request1, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                // Simulate the server closing while the client sends a second request.
                // The server sends a lastStreamId for the first request, and discards the second.
                serverSessionRef.get().close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
                // The client sends the second request and should eventually fail it
                // locally since it has a larger streamId, and the server discarded it.
                MetaData.Request request2 = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
                clientSession.newStream(new HeadersFrame(request2, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
                    {
                        streamFailureLatch.countDown();
                        callback.succeeded();
                    }
                });
            }
        });

        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(streamFailureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAway() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    clientCloseLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        });

        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // Send a graceful GOAWAY from the server.
        // Because the server had no pending streams, it will send also a non-graceful GOAWAY.
        ((HTTP2Session)serverSessionRef.get()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

        Assertions.assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                Session session = stream.getSession();
                serverSessionRef.set(session);

                // Send a graceful GOAWAY while processing a stream.
                ((HTTP2Session)session).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    clientCloseLatch.countDown();
            }
        });
        CountDownLatch clientLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        });

        // Wait for the graceful GOAWAY.
        Assertions.assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Now the client cannot create new streams.
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY), null, true), streamPromise, null);
        Assertions.assertThrows(ExecutionException.class, () -> streamPromise.get(5, TimeUnit.SECONDS));

        // The client must not reply to a graceful GOAWAY.
        Assertions.assertFalse(serverGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Previous streams must complete successfully.
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.headers(new HeadersFrame(serverStream.getId(), response, null, true), Callback.NOOP);

        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // The server should have sent the GOAWAY after the last stream completed.

        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGoAwayWithStalledStreamServerConsumesDataOfInFlightStream() throws Exception
    {
        int flowControlWindow = 32 * 1024;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
            {
                @Override
                public void onAccept(Session session)
                {
                    serverSessionRef.set(session);
                }

                @Override
                public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
                {
                    AtomicInteger dataFrames = new AtomicInteger();
                    return new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            // Do not consume the data for this stream (i.e. don't succeed the callback).
                            // Only send the response when receiving the first DATA frame.
                            if (dataFrames.incrementAndGet() == 1)
                            {
                                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                            }
                        }
                    };
                }

                @Override
                public void onGoAway(Session session, GoAwayFrame frame)
                {
                    serverGoAwayLatch.countDown();
                }

                @Override
                public void onClose(Session session, GoAwayFrame frame)
                {
                    serverCloseLatch.countDown();
                }
            }, h2 ->
            {
                // Use the simple, predictable, strategy for window updates.
                h2.setFlowControlStrategyFactory(SimpleFlowControlStrategy::new);
                h2.setInitialSessionRecvWindow(flowControlWindow);
                h2.setInitialStreamRecvWindow(flowControlWindow);
            });

        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                clientSettingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        // Wait for the server settings to be received by the client.
        // In particular, we want to wait for the initial stream flow
        // control window setting before we create the first stream below.
        Assertions.assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));

        // This is necessary because the server session window is smaller than the
        // default and the server cannot send a WINDOW_UPDATE with a negative value.
        ((ISession)clientSession).updateSendWindow(flowControlWindow - FlowControlStrategy.DEFAULT_WINDOW_SIZE);

        MetaData.Request request1 = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame headersFrame1 = new HeadersFrame(request1, null, false);
        DataFrame dataFrame1 = new DataFrame(ByteBuffer.allocate(flowControlWindow / 2), false);
        ((ISession)clientSession).newStream(new IStream.FrameList(headersFrame1, dataFrame1, null), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream clientStream1, HeadersFrame frame)
            {
                // Send the server GOAWAY frame.
                serverSessionRef.get().close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);

                // Send a second, in-flight, stream with data, which
                // will exhaust the client session flow control window.
                // The server should consume the data even if it will drop
                // this stream, so that the first stream can send more data.
                MetaData.Request request2 = newRequest("POST", HttpFields.EMPTY);
                HeadersFrame headersFrame2 = new HeadersFrame(request2, null, false);
                DataFrame dataFrame2 = new DataFrame(ByteBuffer.allocate(flowControlWindow / 2), true);
                ((ISession)clientStream1.getSession()).newStream(new IStream.FrameList(headersFrame2, dataFrame2, null), new Promise.Adapter<>()
                {
                    @Override
                    public void succeeded(Stream clientStream2)
                    {
                        // After the in-flight stream is sent, try to complete the first stream.
                        // The client should receive the window update from
                        // the server and be able to complete this stream.
                        clientStream1.data(new DataFrame(clientStream1.getId(), ByteBuffer.allocate(flowControlWindow / 2), true), Callback.NOOP);
                    }
                }, new Adapter());
            }
        });

        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientGoAwayWithStreamsServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                serverStreamLatch.countDown();
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                serverGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        });

        Assertions.assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));

        // The client sends a GOAWAY.
        clientSession.close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);

        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));

        // The client must not receive a GOAWAY until the all streams are completed.
        Assertions.assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream.
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.headers(new HeadersFrame(serverStream.getId(), response, null, true), Callback.NOOP);

        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverStream.getSession()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsClientGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                serverStreamLatch.countDown();

                // Send a graceful GOAWAY while processing a stream.
                ((HTTP2Session)stream.getSession()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                {
                    // Send a GOAWAY when receiving a graceful GOAWAY.
                    session.close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
                }
                else
                {
                    clientGoAwayLatch.countDown();
                }
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientLatch.countDown();
            }
        });

        // The server has a pending stream, so it does not send the non-graceful GOAWAY yet.
        Assertions.assertFalse(clientGoAwayLatch.await(1, TimeUnit.SECONDS));

        // Complete the stream, the server should send the non-graceful GOAWAY.
        Stream serverStream = serverStreamRef.get();
        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
        serverStream.headers(new HeadersFrame(serverStream.getId(), response, null, true), Callback.NOOP);

        // The server already received the client GOAWAY,
        // so completing the last stream produces a close event.
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        // The client should receive the server non-graceful GOAWAY.
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverStream.getSession()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientGracefulGoAwayWithStreamsServerGracefulGoAwayServerClosesWhenLastStreamCloses() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        if (frame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    }
                };
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                {
                    // Send a graceful GOAWAY.
                    ((HTTP2Session)session).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);
                }
                else
                {
                    serverGoAwayLatch.countDown();
                }
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        FuturePromise<Stream> promise = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(request, null, false), promise, new Stream.Listener.Adapter());
        Stream clientStream = promise.get(5, TimeUnit.SECONDS);

        // Send a graceful GOAWAY from the client.
        ((HTTP2Session)clientSession).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

        // The server should send a graceful GOAWAY.
        Assertions.assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Complete the stream.
        clientStream.data(new DataFrame(clientStream.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);

        // Both client and server should send a non-graceful GOAWAY.
        Assertions.assertTrue(serverGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverStreamRef.get().getSession()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientShutdownServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverSessionRef.set(session);
                serverCloseLatch.countDown();
            }
        });

        Session clientSession = newClient(new Session.Listener.Adapter());
        // TODO: get rid of sleep!
        // Wait for the SETTINGS frames to be exchanged.
        Thread.sleep(500);

        ((HTTP2Session)clientSession).getEndPoint().close();

        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAwayClientShutdownServerCloses() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                // Reply to the graceful GOAWAY from the server with a TCP close.
                ((HTTP2Session)session).getEndPoint().close();
            }
        });
        // Wait for the SETTINGS frames to be exchanged.
        Thread.sleep(500);

        // Send a graceful GOAWAY to the client.
        ((HTTP2Session)serverSessionRef.get()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
    }

    // TODO: add a shutdown test with pending stream.

    @Test
    public void testServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverIdleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public boolean onIdleTimeout(Session session)
            {
                serverIdleTimeoutLatch.countDown();
                return true;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (!frame.isGraceful())
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        Assertions.assertTrue(serverIdleTimeoutLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Server should send a GOAWAY to the client.
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        // The client replied to server's GOAWAY, but the server already closed.
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                // Send a graceful GOAWAY.
                ((HTTP2Session)stream.getSession()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });
        CountDownLatch clientResetLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        // Send request headers but not data.
        clientSession.newStream(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientResetLatch.countDown();
            }
        });

        Assertions.assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        // Server idle timeout sends a non-graceful GOAWAY.
        Assertions.assertTrue(clientResetLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientGracefulGoAwayWithStreamsServerIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;

        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                ((HTTP2Session)session).getEndPoint().setIdleTimeout(idleTimeout);
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    serverGracefulGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        CountDownLatch streamResetLatch = new CountDownLatch(1);
        clientSession.newStream(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                streamResetLatch.countDown();
            }
        });

        // Client sends a graceful GOAWAY.
        ((HTTP2Session)clientSession).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);

        Assertions.assertTrue(serverGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(streamResetLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }

    @Test
    public void testServerGoAwayWithStreamsThenStop() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                // Don't reply, don't reset the stream, just send the GOAWAY.
                stream.getSession().close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
            }
        });

        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        CountDownLatch clientResetLatch = new CountDownLatch(1);
        clientSession.newStream(new HeadersFrame(request, null, false), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientResetLatch.countDown();
            }
        });

        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));

        // Neither the client nor the server are finishing
        // the pending stream, so force the stop on the server.
        LifeCycle.stop(serverSessionRef.get());

        // The server should reset all the pending streams.
        Assertions.assertTrue(clientResetLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(((HTTP2Session)serverSessionRef.get()).getEndPoint().isOpen());
        Assertions.assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
    }
}
