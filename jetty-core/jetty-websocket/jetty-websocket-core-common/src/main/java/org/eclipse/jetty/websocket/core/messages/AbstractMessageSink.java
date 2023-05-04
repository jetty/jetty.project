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

import java.lang.invoke.MethodHandle;
import java.util.Objects;

import org.eclipse.jetty.websocket.core.CoreSession;

/**
 * <p>Abstract implementation of {@link MessageSink}.</p>
 * <p>Management of demand for WebSocket frames may either be entirely managed
 * by the {@link MessageSink} implementation ({@code autoDemand==true}); or
 * it may be managed collaboratively between the application and the
 * {@link MessageSink} implementation ({@code autoDemand==true}).</p>
 * <p>{@link MessageSink} implementations must handle the demand for WebSocket
 * frames in this way:</p>
 * <ul>
 * <li>If {@code autoDemand==false}, the {@link MessageSink} manages the
 * demand until the conditions to invoke the application function are met;
 * when the {@link MessageSink} invokes the application function, then the
 * application is responsible to demand for more WebSocket frames.</li>
 * <li>If {@code autoDemand==true}, only the {@link MessageSink} manages the
 * demand for WebSocket frames. If the {@link MessageSink} invokes the application
 * function, the {@link MessageSink} must demand for WebSocket frames after the
 * invocation of the application function returns successfully.</li>
 * </ul>
 * <p>Method {@link #autoDemand()} helps to manage the demand after the
 * invocation of the application function returns successfully.</p>
 */
public abstract class AbstractMessageSink implements MessageSink
{
    private final CoreSession session;
    private final MethodHandle methodHandle;
    private final boolean autoDemand;

    /**
     * Creates a new {@link MessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHandle the application function to invoke
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     * as explained in {@link AbstractMessageSink}
     */
    public AbstractMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        this.session = Objects.requireNonNull(session, "CoreSession");
        this.methodHandle = Objects.requireNonNull(methodHandle, "MethodHandle");
        this.autoDemand = autoDemand;
    }

    /**
     * @return the WebSocket session
     */
    public CoreSession getCoreSession()
    {
        return session;
    }

    /**
     * @return the application function
     */
    public MethodHandle getMethodHandle()
    {
        return methodHandle;
    }

    /**
     * @return whether this {@link MessageSink} automatically demands for more
     * WebSocket frames after the invocation of the application function has returned.
     */
    public boolean isAutoDemand()
    {
        return autoDemand;
    }

    /**
     * <p>If {@link #isAutoDemand()} then demands for one more WebSocket frame
     * via {@link CoreSession#demand(long)}; otherwise it is a no-operation,
     * because the demand is explicitly managed by the application function.</p>
     */
    protected void autoDemand()
    {
        if (isAutoDemand())
            getCoreSession().demand(1);
    }
}
