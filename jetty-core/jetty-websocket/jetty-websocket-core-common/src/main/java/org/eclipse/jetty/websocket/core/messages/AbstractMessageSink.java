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
 * <p>Partial implementation of {@link MessageSink}.</p>
 * <p>The application function passed to the constructor as a {@link MethodHandle}
 * can either explicitly demand for more WebSocket frames (by eventually calling
 * {@link CoreSession#demand(long)}) or implicitly delegate the demand for more
 * WebSocket frames to the implementation.
 * In the former case, the constructor parameter {@code autoDemand==false}; in
 * the latter case, the constructor parameter {@code autoDemand==true}.</p>
 * <p>The {@link MessageSink} implementation must manage the demand for WebSocket
 * frames even when the application function is not invoked.</p>
 * <p>For example, a {@link MessageSink} implementation that accumulates data frames
 * until a whole message is assembled must internally demand for more WebSocket
 * frames, regardless of the value of {@code autoDemand}, until the whole message
 * is assembled.
 * Then the {@link MessageSink} implementation invokes the application function,
 * that may explicitly manage the demand or may delegate the demand to the
 * {@link MessageSink} implementation.
 * When the invocation of the application function returns, the {@link MessageSink}
 * implementation must call {@link #autoDemand()}.</p>
 */
public abstract class AbstractMessageSink implements MessageSink
{
    private final CoreSession session;
    private final MethodHandle methodHandle;
    private final boolean autoDemand;

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
