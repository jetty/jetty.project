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
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * Centralized logic for Dispatched Message Handling.
 * <p>
 * A Dispatched MessageSink can consist of 1 or more {@link #accept(Frame, Callback)} calls.
 * <p>
 * The first {@link #accept(Frame, Callback)} in a message will trigger a dispatch to the
 * function specified in the constructor.
 * <p>
 * The completion of the dispatched function call is the sign that the next message is suitable
 * for processing from the network. (The connection fillAndParse should remain idle for the
 * NEXT message until such time as the dispatched function call has completed)
 * </p>
 * <p>
 * There are a few use cases we need to handle.
 * </p>
 * <p>
 * <em>1. Normal Processing</em>
 * </p>
 * <pre>
 *     Connection Thread | DispatchedMessageSink | Thread 2
 *     TEXT                accept()
 *                          - dispatch -           function.read(stream)
 *     CONT                accept()                stream.read()
 *     CONT                accept()                stream.read()
 *     CONT=fin            accept()                stream.read()
 *                           EOF                   stream.read EOF
 *     IDLE
 *                                                 exit method
 *     RESUME(NEXT MSG)
 * </pre>
 * <p>
 * <em>2. Early Exit (with no activity)</em>
 * </p>
 * <pre>
 *     Connection Thread | DispatchedMessageSink | Thread 2
 *     TEXT                accept()
 *                          - dispatch -           function.read(stream)
 *     CONT                accept()                exit method (normal return)
 *     IDLE
 *     TIMEOUT
 * </pre>
 * <p>
 * <em>3. Early Exit (due to exception)</em>
 * </p>
 * <pre>
 *     Connection Thread | DispatchedMessageSink | Thread 2
 *     TEXT                accept()
 *                          - dispatch -           function.read(stream)
 *     CONT                accept()                exit method (throwable)
 *     callback.fail()
 *     endpoint.onError()
 *     close(error)
 * </pre>
 * <p>
 * <em>4. Early Exit (with Custom Threading)</em>
 * </p>
 * <pre>
 *     Connection Thread | DispatchedMessageSink | Thread 2              | Thread 3
 *     TEXT                accept()
 *                          - dispatch -           function.read(stream)
 *                                                 thread.new(stream)      stream.read()
 *                                                 exit method
 *     CONT                accept()                                        stream.read()
 *     CONT                accept()                                        stream.read()
 *     CONT=fin            accept()                                        stream.read()
 *                           EOF                                           stream.read EOF
 *     RESUME(NEXT MSG)
 * </pre>
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
