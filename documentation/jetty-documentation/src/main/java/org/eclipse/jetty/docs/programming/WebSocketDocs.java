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

package org.eclipse.jetty.docs.programming;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jetty.ee10.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee10.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.ee10.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.ee10.websocket.api.WriteCallback;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.IteratingCallback;

@SuppressWarnings("unused")
public class WebSocketDocs
{
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::listenerEndpoint[]
    public class ListenerEndPoint implements WebSocketListener // <1>
    {
        private Session session;

        @Override
        public void onWebSocketConnect(Session session)
        {
            // The WebSocket connection is established.

            // Store the session to be able to send data to the remote peer.
            this.session = session;

            // You may configure the session.
            session.setMaxTextMessageSize(16 * 1024);

            // You may immediately send a message to the remote peer.
            session.getRemote().sendString("connected", WriteCallback.NOOP);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            // The WebSocket connection is closed.

            // You may dispose resources.
            disposeResources();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            // The WebSocket connection failed.

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
                session.getRemote().sendString(message.substring("echo:".length()), WriteCallback.NOOP);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int length)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload[offset + i])
                    return;
            }
            savePNGImage(payload, offset, length);
        }
    }
    // end::listenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamingListenerEndpoint[]
    public class StreamingListenerEndpoint implements WebSocketPartialListener
    {
        private Path textPath;

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            // Forward chunks to external REST service.
            forwardToREST(payload, fin);
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            // Save chunks to file.
            appendToFile(payload, fin);
        }
    }
    // end::streamingListenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedEndpoint[]
    @WebSocket // <1>
    public class AnnotatedEndPoint
    {
        private Session session;

        @OnWebSocketConnect // <2>
        public void onConnect(Session session)
        {
            // The WebSocket connection is established.

            // Store the session to be able to send data to the remote peer.
            this.session = session;

            // You may configure the session.
            session.setMaxTextMessageSize(16 * 1024);

            // You may immediately send a message to the remote peer.
            session.getRemote().sendString("connected", WriteCallback.NOOP);
        }

        @OnWebSocketClose // <3>
        public void onClose(int statusCode, String reason)
        {
            // The WebSocket connection is closed.

            // You may dispose resources.
            disposeResources();
        }

        @OnWebSocketError // <4>
        public void onError(Throwable cause)
        {
            // The WebSocket connection failed.

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
                session.getRemote().sendString(message.substring("echo:".length()), WriteCallback.NOOP);
        }

        @OnWebSocketMessage // <5>
        public void onBinaryMessage(byte[] payload, int offset, int length)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload[offset + i])
                    return;
            }
            savePNGImage(payload, offset, length);
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
    public class ConfigureEndpoint implements WebSocketListener
    {
        @Override
        public void onWebSocketConnect(Session session)
        {
            // Configure the max length of incoming messages.
            session.setMaxTextMessageSize(16 * 1024);

            // Configure the idle timeout.
            session.setIdleTimeout(Duration.ofSeconds(30));
        }
    }
    // end::sessionConfigure[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::sendBlocking[]
    @WebSocket
    public class BlockingSendEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            // Obtain the RemoteEndpoint APIs.
            RemoteEndpoint remote = session.getRemote();

            try
            {
                // Send textual data to the remote peer.
                remote.sendString("data");

                // Send binary data to the remote peer.
                ByteBuffer bytes = readImageFromFile();
                remote.sendBytes(bytes);

                // Send a PING frame to the remote peer.
                remote.sendPing(ByteBuffer.allocate(8).putLong(System.nanoTime()).flip());
            }
            catch (IOException x)
            {
                // No need to rethrow or close the session.
                System.getLogger("websocket").log(System.Logger.Level.WARNING, "could not send data", x);
            }
        }
    }
    // end::sendBlocking[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::sendNonBlocking[]
    @WebSocket
    public class NonBlockingSendEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            // Obtain the RemoteEndpoint APIs.
            RemoteEndpoint remote = session.getRemote();

            // Send textual data to the remote peer.
            remote.sendString("data", new WriteCallback() // <1>
            {
                @Override
                public void writeSuccess()
                {
                    // Send binary data to the remote peer.
                    ByteBuffer bytes = readImageFromFile();
                    remote.sendBytes(bytes, new WriteCallback() // <2>
                    {
                        @Override
                        public void writeSuccess()
                        {
                            // Both sends succeeded.
                        }

                        @Override
                        public void writeFailed(Throwable x)
                        {
                            System.getLogger("websocket").log(System.Logger.Level.WARNING, "could not send binary data", x);
                        }
                    });
                }

                @Override
                public void writeFailed(Throwable x)
                {
                    // No need to rethrow or close the session.
                    System.getLogger("websocket").log(System.Logger.Level.WARNING, "could not send textual data", x);
                }
            });

            // remote.sendString("wrong", WriteCallback.NOOP); // May throw WritePendingException! <3>
        }
    }
    // end::sendNonBlocking[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamSendBlocking[]
    @WebSocket
    public class StreamSendBlockingEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            try
            {
                RemoteEndpoint remote = session.getRemote();
                while (true)
                {
                    ByteBuffer chunk = readChunkToSend();
                    if (chunk == null)
                    {
                        // No more bytes, finish the WebSocket message.
                        remote.sendPartialBytes(ByteBuffer.allocate(0), true);
                        break;
                    }
                    else
                    {
                        // Send the chunk.
                        remote.sendPartialBytes(chunk, false);
                    }
                }
            }
            catch (IOException x)
            {
                x.printStackTrace();
            }
        }
    }
    // end::streamSendBlocking[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamSendNonBlocking[]
    @WebSocket
    public class StreamSendNonBlockingEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            RemoteEndpoint remote = session.getRemote();
            new Sender(remote).iterate();
        }

        private class Sender extends IteratingCallback implements WriteCallback // <1>
        {
            private final RemoteEndpoint remote;
            private boolean finished;

            private Sender(RemoteEndpoint remote)
            {
                this.remote = remote;
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
                    remote.sendPartialBytes(ByteBuffer.allocate(0), true, this); // <3>
                    finished = true;
                    return Action.SCHEDULED;
                }
                else
                {
                    // Send the chunk.
                    remote.sendPartialBytes(ByteBuffer.allocate(0), false, this); // <3>
                    return Action.SCHEDULED;
                }
            }

            @Override
            public void writeSuccess()
            {
                // When the send succeeds, succeed this IteratingCallback.
                succeeded();
            }

            @Override
            public void writeFailed(Throwable x)
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
    public class RoundTripListenerEndpoint implements WebSocketPingPongListener // <1>
    {
        @Override
        public void onWebSocketConnect(Session session)
        {
            // Send to the remote peer the local nanoTime.
            ByteBuffer buffer = ByteBuffer.allocate(8).putLong(System.nanoTime()).flip();
            session.getRemote().sendPing(buffer, WriteCallback.NOOP);
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            // The remote peer echoed back the local nanoTime.
            long start = payload.getLong();

            // Calculate the round-trip time.
            long roundTrip = System.nanoTime() - start;
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
                session.close(StatusCode.NORMAL, "bye");
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

    private static void savePNGImage(byte[] payload, int offset, int length)
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
