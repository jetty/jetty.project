//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import java.io.Closeable;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;

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
    private CompletableFuture<Void> dispatchComplete;
    private MessageSink typeSink;

    public DispatchedMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
    }

    public abstract MessageSink newSink(Frame frame);

    public void accept(Frame frame, final Callback callback)
    {
        if (typeSink == null)
        {
            typeSink = newSink(frame);
            dispatchComplete = new CompletableFuture<>();

            // Dispatch to end user function (will likely start with blocking for data/accept).
            // If the MessageSink can be closed do this after invoking and before completing the CompletableFuture.
            new Thread(() ->
            {
                try
                {
                    methodHandle.invoke(typeSink);
                    if (typeSink instanceof Closeable)
                        IO.close((Closeable)typeSink);

                    dispatchComplete.complete(null);
                }
                catch (Throwable throwable)
                {
                    if (typeSink instanceof Closeable)
                        IO.close((Closeable)typeSink);

                    dispatchComplete.completeExceptionally(throwable);
                }
            }).start();
        }

        Callback frameCallback = callback;
        if (frame.isFin())
        {
            // This is the final frame we should wait for the frame callback and the dispatched thread.
            Callback.Completable completableCallback = new Callback.Completable();
            frameCallback = completableCallback;
            CompletableFuture.allOf(dispatchComplete, completableCallback).whenComplete((aVoid, throwable) ->
            {
                typeSink = null;
                dispatchComplete = null;
                if (throwable != null)
                    callback.failed(throwable);
                else
                    callback.succeeded();
            });
        }

        typeSink.accept(frame, frameCallback);
    }
}
