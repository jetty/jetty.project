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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class WebSocketDocs
{
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::autoDemand[]
    // Attribute autoDemand is true by default.
    @WebSocket(autoDemand = true)
    public class AutoDemandAnnotatedEndPoint
    {
        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            // No need to demand here, because this endpoint is auto-demanding.
        }

        @OnWebSocketMessage
        public void onText(String message)
        {
            System.getLogger("ws.message").log(INFO, message);
            // No need to demand here, because this endpoint is auto-demanding.
        }
    }

    public class AutoDemandListenerEndPoint implements Session.Listener.AutoDemanding
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            // No need to demand here, because this endpoint is auto-demanding.
        }

        @Override
        public void onWebSocketText(String message)
        {
            System.getLogger("ws.message").log(INFO, message);
            // No need to demand here, because this endpoint is auto-demanding.
        }
    }
    // end::autoDemand[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::autoDemandWrong[]
    public class WrongAutoDemandListenerEndPoint implements Session.Listener.AutoDemanding
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            // No need to demand here, because this endpoint is auto-demanding.
        }

        @Override
        public void onWebSocketText(String message)
        {
            // Perform an asynchronous operation, such as invoking
            // a third party service or just echoing the message back.
            session.sendText(message, Callback.NOOP);

            // Returning from this method will automatically demand,
            // so this method may be entered again before sendText()
            // has been completed, causing a WritePendingException.
        }
    }
    // end::autoDemandWrong[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::explicitDemand[]
    public class ExplicitDemandListenerEndPoint implements Session.Listener
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;

            // Explicitly demand here, otherwise no other event is received.
            session.demand();
        }

        @Override
        public void onWebSocketText(String message)
        {
            // Perform an asynchronous operation, such as invoking
            // a third party service or just echoing the message back.

            // We want to demand only when sendText() has completed,
            // which is notified to the callback passed to sendText().
            session.sendText(message, Callback.from(session::demand, failure ->
            {
                // Handle the failure, in this case just closing the session.
                session.close(StatusCode.SERVER_ERROR, "failure", Callback.NOOP);
            }));

            // Return from the method without demanding yet,
            // waiting for the completion of sendText() to demand.
        }
    }
    // end::explicitDemand[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::listenerEndpoint[]
    public class ListenerEndPoint implements Session.Listener
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
            session.sendText("connected", Callback.from(session::demand, Throwable::printStackTrace));
        }

        @Override
        public void onWebSocketText(String message)
        {
            // A WebSocket text message is received.

            // You may echo it back if it matches certain criteria.
            if (message.startsWith("echo:"))
            {
                // Only demand for more events when sendText() is completed successfully.
                session.sendText(message.substring("echo:".length()), Callback.from(session::demand, Throwable::printStackTrace));
            }
            else
            {
                // Discard the message, and demand for more events.
                session.demand();
            }
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            boolean isPNG = true;
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload.get(i))
                {
                    // Not a PNG image.
                    isPNG = false;
                    break;
                }
            }

            if (isPNG)
                savePNGImage(payload);

            // Complete the callback to release the payload ByteBuffer.
            callback.succeed();

            // Demand for more events.
            session.demand();
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
        public void onWebSocketClose(int statusCode, String reason)
        {
            // The WebSocket endpoint has been closed.

            // You may dispose resources.
            disposeResources();
        }
    }
    // end::listenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamingListenerEndpoint[]
    public class StreamingListenerEndpoint implements Session.Listener
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            session.demand();
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            // Forward chunks to external REST service, asynchronously.
            // Only demand when the forwarding completed successfully.
            CompletableFuture<Void> result = forwardToREST(payload, fin);
            result.whenComplete((ignored, failure) ->
            {
                if (failure == null)
                    session.demand();
                else
                    failure.printStackTrace();
            });
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            // Save chunks to file.
            appendToFile(payload, fin);

            // Complete the callback to release the payload ByteBuffer.
            callback.succeed();

            // Demand for more events.
            session.demand();
        }
    }
    // end::streamingListenerEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedEndpoint[]
    @WebSocket(autoDemand = false) // <1>
    public class AnnotatedEndPoint
    {
        @OnWebSocketOpen // <2>
        public void onOpen(Session session)
        {
            // The WebSocket endpoint has been opened.

            // You may configure the session.
            session.setMaxTextMessageSize(16 * 1024);

            // You may immediately send a message to the remote peer.
            session.sendText("connected", Callback.from(session::demand, Throwable::printStackTrace));
        }

        @OnWebSocketMessage // <3>
        public void onTextMessage(Session session, String message)
        {
            // A WebSocket textual message is received.

            // You may echo it back if it matches certain criteria.
            if (message.startsWith("echo:"))
            {
                // Only demand for more events when sendText() is completed successfully.
                session.sendText(message.substring("echo:".length()), Callback.from(session::demand, Throwable::printStackTrace));
            }
            else
            {
                // Discard the message, and demand for more events.
                session.demand();
            }
        }

        @OnWebSocketMessage // <3>
        public void onBinaryMessage(Session session, ByteBuffer payload, Callback callback)
        {
            // A WebSocket binary message is received.

            // Save only PNG images.
            boolean isPNG = true;
            byte[] pngBytes = new byte[]{(byte)0x89, 'P', 'N', 'G'};
            for (int i = 0; i < pngBytes.length; ++i)
            {
                if (pngBytes[i] != payload.get(i))
                {
                    // Not a PNG image.
                    isPNG = false;
                    break;
                }
            }

            if (isPNG)
                savePNGImage(payload);

            // Complete the callback to release the payload ByteBuffer.
            callback.succeed();

            // Demand for more events.
            session.demand();
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

        @OnWebSocketClose // <5>
        public void onClose(int statusCode, String reason)
        {
            // The WebSocket endpoint has been closed.

            // You may dispose resources.
            disposeResources();
        }
    }
    // end::annotatedEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::partialAnnotatedEndpoint[]
    @WebSocket(autoDemand = false)
    public class PartialAnnotatedEndpoint
    {
        @OnWebSocketMessage
        public void onTextMessage(Session session, String partialText, boolean fin)
        {
            // Forward the partial text.
            // Demand only when the forward completed.
            CompletableFuture<Void> result = forwardToREST(partialText, fin);
            result.whenComplete((ignored, failure) ->
            {
                if (failure == null)
                    session.demand();
                else
                    failure.printStackTrace();
            });
        }

        @OnWebSocketMessage
        public void onBinaryMessage(Session session, ByteBuffer partialPayload, boolean fin, Callback callback)
        {
            // Save partial payloads to file.
            appendToFile(partialPayload, fin);
            // Complete the callback to release the payload ByteBuffer.
            callback.succeed();
            // Demand for more events.
            session.demand();
        }
    }
    // end::partialAnnotatedEndpoint[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::streamingAnnotatedEndpoint[]
    @WebSocket
    public class StreamingAnnotatedEndpoint
    {
        @OnWebSocketMessage
        public void onTextMessage(Reader reader)
        {
            // Read from the Reader and forward.
            // Caution: blocking APIs.
            forwardToREST(reader);
        }

        @OnWebSocketMessage
        public void onBinaryMessage(InputStream stream)
        {
            // Read from the InputStream and save to file.
            // Caution: blocking APIs.
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

            // Demand for more events.
            session.demand();
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
    @WebSocket(autoDemand = false)
    public class StreamSendNonBlockingEndpoint
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            new Sender(session).iterate();
        }

        private class Sender extends IteratingCallback implements Callback // <1>
        {
            private final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
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
                    return Action.SUCCEEDED; // <4>

                int read = readChunkToSendInto(byteBuffer);
                if (read < 0)
                {
                    // No more bytes to send, finish the WebSocket message.
                    session.sendPartialBinary(byteBuffer, true, this); // <3>
                    finished = true;
                    return Action.SCHEDULED;
                }
                else
                {
                    // Send the chunk.
                    session.sendPartialBinary(byteBuffer, false, this); // <3>
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
            protected void onCompleteSuccess()
            {
                session.demand(); // <5>
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
    public class RoundTripListenerEndpoint implements Session.Listener
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            // Send to the remote peer the local nanoTime.
            ByteBuffer buffer = ByteBuffer.allocate(8).putLong(NanoTime.now()).flip();
            session.sendPing(buffer, Callback.NOOP);
            // Demand for more events.
            session.demand();
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            // The remote peer echoed back the local nanoTime.
            long start = payload.getLong();

            // Calculate the round-trip time.
            long roundTrip = NanoTime.since(start);

            // Demand for more events.
            session.demand();
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

    private static CompletableFuture<Void> forwardToREST(String payload, boolean fin)
    {
        return null;
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

    private static int readChunkToSendInto(ByteBuffer byteBuffer)
    {
        return 0;
    }
}
