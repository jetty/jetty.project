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

package org.eclipse.jetty.websocket.core.messages;

import java.io.Closeable;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * <p>A partial implementation of {@link MessageSink} for methods that consume WebSocket
 * messages using blocking stream APIs, typically via {@link InputStream} or {@link Reader}.</p>
 * <p>The first call to {@link #accept(Frame, Callback)} triggers the application function
 * specified in the constructor to be invoked in a different thread.</p>
 * <p>Subsequent calls to {@link #accept(Frame, Callback)} feed a nested {@link MessageSink}
 * that in turns feeds the {@link InputStream} or {@link Reader} stream.</p>
 * <p>Implementations of this class must manage the demand for WebSocket frames, and
 * therefore must always be auto-demanding.</p>
 * <p>Upon return from the application function, the stream is closed.
 * This means that the stream must be consumed synchronously within the invocation of the
 * application function.</p>
 * <p>The demand for the next WebSocket message is performed when both the application
 * function has returned and the last frame has been consumed (signaled by completing the
 * callback associated with the frame).</p>
 * <p>Throwing from the application function results in the WebSocket connection to be
 * closed.</p>
 */
public abstract class DispatchedMessageSink extends AbstractMessageSink
{
    private final Executor executor;
    private volatile CompletableFuture<Void> dispatchComplete;
    private MessageSink typeSink;

    public DispatchedMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        super(session, methodHandle, autoDemand);
        if (!autoDemand)
            throw new IllegalArgumentException("%s must be auto-demanding".formatted(getClass().getSimpleName()));
        executor = session.getWebSocketComponents().getExecutor();
    }

    public abstract MessageSink newMessageSink();

    public void accept(Frame frame, final Callback callback)
    {
        if (typeSink == null)
        {
            typeSink = newMessageSink();
            dispatchComplete = new CompletableFuture<>();

            // Call the endpoint method in a different
            // thread, since it will use blocking APIs.
            executor.execute(() ->
            {
                try
                {
                    getMethodHandle().invoke(typeSink);
                    if (typeSink instanceof Closeable closeable)
                        IO.close(closeable);
                    dispatchComplete.complete(null);
                }
                catch (Throwable throwable)
                {
                    typeSink.fail(throwable);
                    dispatchComplete.completeExceptionally(throwable);
                }
            });
        }

        Callback frameCallback = callback;
        if (frame.isFin())
        {
            // Wait for both the frame callback and the dispatched thread.
            Callback.Completable frameComplete = Callback.Completable.from(callback);
            frameCallback = frameComplete;
            CompletableFuture.allOf(dispatchComplete, frameComplete).whenComplete((result, failure) ->
            {
                typeSink = null;
                dispatchComplete = null;

                // The nested MessageSink manages the demand until the last
                // frame, while this MessageSink manages the demand when both
                // the last frame and the dispatched thread are completed.
                if (failure == null)
                    autoDemand();
            });
        }

        typeSink.accept(frame, frameCallback);
    }

    public boolean isDispatched()
    {
        return dispatchComplete != null;
    }
}
