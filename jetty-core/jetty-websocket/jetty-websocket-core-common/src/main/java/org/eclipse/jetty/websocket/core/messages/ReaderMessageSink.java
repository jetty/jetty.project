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

import org.eclipse.jetty.websocket.core.CoreSession;

public class ReaderMessageSink extends DispatchedMessageSink
{
    public ReaderMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        super(session, methodHandle, autoDemand);
    }

    @Override
    public MessageReader newMessageSink()
    {
        return new MessageReader(getCoreSession());
    }
}
