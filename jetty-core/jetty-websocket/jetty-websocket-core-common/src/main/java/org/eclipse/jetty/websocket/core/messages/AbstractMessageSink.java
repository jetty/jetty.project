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

public abstract class AbstractMessageSink implements MessageSink
{
    private final CoreSession session;
    protected final MethodHandle methodHandle;
    private final boolean autoDemand;

    public AbstractMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        this.session = Objects.requireNonNull(session, "CoreSession");
        this.methodHandle = Objects.requireNonNull(methodHandle, "MethodHandle");
        this.autoDemand = autoDemand;
    }

    public CoreSession getCoreSession()
    {
        return session;
    }

    public MethodHandle getMethodHandle()
    {
        return methodHandle;
    }

    public boolean isAutoDemand()
    {
        return autoDemand;
    }

    protected void autoDemand()
    {
        if (isAutoDemand())
            getCoreSession().demand(1);
    }
}
