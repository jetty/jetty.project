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

package org.eclipse.jetty.docs.programming;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@SuppressWarnings("unused")
public class WebSocketDocs
{
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::listenerEndpoint[]
    public class ListenerEndPoint implements Session.Listener // <1>
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            // The WebSocket endpoint has been opened.

            // Store the session to be able to send data to the remote peer.
            this.session = session;

            // You may configure the session.
            session.setMaxTextMessageSize(16 * 1024);

            // You may immediately send a message to the remote peer.
            session.sendText("connected", Callback.NOOP);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            // The WebSocket endpoint has been closed.

            // You may dispose resources.
            disposeResources();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            // The WebSocket endpoint failed.

            // You may log the error.
            cause.printStackTrace();

            // You may dispose resources.
            disposeResources();
        }

        @Override
        public void onWebSocketText(String message)
        {
            // A WebSocket textual message is received.

            // You may echo it back if it matches certain criteria.
            if (message.startsWith("echo:"))
                session.sendText(message.substring("echo:".length()), Callback.NOOP);
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload.get(i))
                    return;
            }
            savePNGImage(payload);
            callback.succeed();
        }
    }
    // end::listenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamingListenerEndpoint[]
    public class StreamingListenerEndpoint implements Session.Listener
    {
        private Path textPath;

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            // Forward chunks to external REST service.
            forwardToREST(payload, fin);
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            // Save chunks to file.
            appendToFile(payload, fin);
            // Complete the callback.
            callback.succeed();
        }
    }
    // end::streamingListenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedEndpoint[]
    @WebSocket // <1>
    public class AnnotatedEndPoint
    {
        private Session session;

        @OnWebSocketOpen // <2>
        public void onOpen(Session session)
        {
            // The WebSocket endpoint has been opened.

            // Store the session to be able to send data to the remote peer.
            this.session = session;

            // You may configure the session.
            session.setMaxTextMessageSize(16 * 1024);

            // You may immediately send a message to the remote peer.
            session.sendText("connected", Callback.NOOP);
        }

        @OnWebSocketClose // <3>
        public void onClose(int statusCode, String reason)
        {
            // The WebSocket endpoint has been closed.

            // You may dispose resources.
            disposeResources();
        }

        @OnWebSocketError // <4>
        public void onError(Throwable cause)
        {
            // The WebSocket endpoint failed.

            // You may log the error.
            cause.printStackTrace();

            // You may dispose resources.
            disposeResources();
        }

        @OnWebSocketMessage // <5>
        public void onTextMessage(Session session, String message) // <3>
        {
            // A WebSocket textual message is received.

            // You may echo it back if it matches certain criteria.
            if (message.startsWith("echo:"))
                session.sendText(message.substring("echo:".length()), Callback.NOOP);
        }

        @OnWebSocketMessage // <5>
        public void onBinaryMessage(ByteBuffer payload, Callback callback)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload.get(i))
                    return;
            }
            savePNGImage(payload);
        }
    }
    // end::annotatedEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamingAnnotatedEndpoint[]
    @WebSocket
    public class StreamingAnnotatedEndpoint
    {
        @OnWebSocketMessage
        public void onTextMessage(Reader reader)
        {
            // Read chunks and forward.
            forwardToREST(reader);
        }

        @OnWebSocketMessage
        public void onBinaryMessage(InputStream stream)
        {
            // Save chunks to file.
            appendToFile(stream);
        }
    }
    // end::streamingAnnotatedEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::sessionConfigure[]
    public class ConfigureEndpoint implements Session.Listener
    {
        @Override
        public void onWebSocketOpen(Session session)
        {
            // Configure the max length of incoming messages.
            session.setMaxTextMessageSize(16 * 1024);

            // Configure the idle timeout.
            session.setIdleTimeout(Duration.ofSeconds(30));
        }
    }
    // end::sessionConfigure[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::sendNonBlocking[]
    @WebSocket
    public class NonBlockingSendEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            // Send textual data to the remote peer.
            session.sendText("data", new Callback() // <1>
            {
                @Override
                public void succeed()
                {
                    // Send binary data to the remote peer.
                    ByteBuffer bytes = readImageFromFile();
                    session.sendBinary(bytes, new Callback() // <2>
                    {
                        @Override
                        public void succeed()
                        {
                            // Both sends succeeded.
                        }

                        @Override
                        public void fail(Throwable x)
                        {
                            System.getLogger("websocket").log(System.Logger.Level.WARNING, "could not send binary data", x);
                        }
                    });
                }

                @Override
                public void fail(Throwable x)
                {
                    // No need to rethrow or close the session.
                    System.getLogger("websocket").log(System.Logger.Level.WARNING, "could not send textual data", x);
                }
            });

            // remote.sendString("wrong", Callback.NOOP); // May throw WritePendingException! <3>
        }
    }
    // end::sendNonBlocking[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamSendNonBlocking[]
    @WebSocket
    public class StreamSendNonBlockingEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            new Sender(session).iterate();
        }

        private class Sender extends IteratingCallback implements Callback // <1>
        {
            private final Session session;
            private boolean finished;

            private Sender(Session session)
            {
                this.session = session;
            }

            @Override
            protected Action process() throws Throwable // <2>
            {
                if (finished)
                    return Action.SUCCEEDED;

                ByteBuffer chunk = readChunkToSend();
                if (chunk == null)
                {
                    // No more bytes, finish the WebSocket message.
                    session.sendPartialBinary(ByteBuffer.allocate(0), true, this); // <3>
                    finished = true;
                    return Action.SCHEDULED;
                }
                else
                {
                    // Send the chunk.
                    session.sendPartialBinary(ByteBuffer.allocate(0), false, this); // <3>
                    return Action.SCHEDULED;
                }
            }

            @Override
            public void succeed()
            {
                // When the send succeeds, succeed this IteratingCallback.
                succeeded();
            }

            @Override
            public void fail(Throwable x)
            {
                // When the send fails, fail this IteratingCallback.
                failed(x);
            }

            @Override
            protected void onCompleteFailure(Throwable x)
            {
                x.printStackTrace();
            }
        }
    }
    // end::streamSendNonBlocking[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::pingPongListener[]
    public class RoundTripListenerEndpoint implements Session.Listener // <1>
    {
        @Override
        public void onWebSocketOpen(Session session)
        {
            // Send to the remote peer the local nanoTime.
            ByteBuffer buffer = ByteBuffer.allocate(8).putLong(NanoTime.now()).flip();
            session.sendPing(buffer, Callback.NOOP);
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            // The remote peer echoed back the local nanoTime.
            long start = payload.getLong();

            // Calculate the round-trip time.
            long roundTrip = NanoTime.since(start);
        }
    }
    // end::pingPongListener[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::sessionClose[]
    @WebSocket
    public class CloseEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            if ("close".equalsIgnoreCase(text))
                session.close(StatusCode.NORMAL, "bye", Callback.NOOP);
        }
    }
    // end::sessionClose[]

    private static void forwardToREST(String payload, boolean fin)
    {
    }

    private static void forwardToREST(Reader reader)
    {
    }

    private static void appendToFile(ByteBuffer payload, boolean fin)
    {
    }

    private static void appendToFile(InputStream stream)
    {
    }

    private static void disposeResources()
    {
    }

    private static void savePNGImage(ByteBuffer byteBuffer)
    {
    }

    private static ByteBuffer readImageFromFile()
    {
        return null;
    }

    private static ByteBuffer readChunkToSend()
    {
        return null;
    }
}
