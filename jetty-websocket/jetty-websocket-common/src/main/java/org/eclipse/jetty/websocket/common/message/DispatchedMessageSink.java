//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.MessageSink;
import org.eclipse.jetty.websocket.common.MessageSinkImpl;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

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
 *
 * @param <T> the type of object to give to user function
 * @param <R> the type of object that user function will return
 */
public abstract class DispatchedMessageSink<T, R> extends MessageSinkImpl
{
    private final Executor executor;
    private final MethodHandle methodHandle;
    private CompletableFuture<Void> dispatchComplete;
    private MessageSink typeSink;

    public DispatchedMessageSink(WebSocketPolicy policy, Executor executor, MethodHandle methodHandle)
    {
        super(policy, executor, methodHandle);
        this.executor = executor;
        this.methodHandle = methodHandle;
    }

    public abstract MessageSink newSink(Frame frame);
    
    public void accept(Frame frame, final Callback callback)
    {
        if (typeSink == null)
        {
            typeSink = newSink(frame);
            // Dispatch to end user function (will likely start with blocking for data/accept)
            dispatchComplete = CompletableFuture.supplyAsync(() ->
            {
                final T dispatchedType = (T) typeSink;
                try
                {
                     methodHandle.invoke(dispatchedType);
                }
                catch (Throwable throwable)
                {
                    // TODO: handle error somehow?
                    throwable.printStackTrace();
                }
                return null;
            }, executor);
        }
        
        final Callback frameCallback;
        
        if (frame.isFin())
        {
            CompletableFuture<Void> finComplete = new CompletableFuture<>();
            frameCallback = new Callback()
            {
                @Override
                public void failed(Throwable cause)
                {
                    finComplete.completeExceptionally(cause);
                }
                
                @Override
                public void succeeded()
                {
                    finComplete.complete(null);
                }
            };
            CompletableFuture.allOf(dispatchComplete, finComplete).whenComplete(
                    (aVoid, throwable) ->
                    {
                        typeSink = null;
                        dispatchComplete = null;
                        if (throwable != null)
                            callback.failed(throwable);
                        else
                            callback.succeeded();
                    });
        }
        else
        {
            // Non-fin-frame
            frameCallback = callback;
        }
        
        typeSink.accept(frame, frameCallback);
    }
}
