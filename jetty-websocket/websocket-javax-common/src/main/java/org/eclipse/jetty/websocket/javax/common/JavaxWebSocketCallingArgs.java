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

package org.eclipse.jetty.websocket.javax.common;

import java.nio.ByteBuffer;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.util.InvokerUtils;

// The different kind of @OnMessage method parameter signatures expected.
public class JavaxWebSocketCallingArgs
{
    static InvokerUtils.Arg[] getArgsFor(Class<?> objectType)
    {
        return new InvokerUtils.Arg[]{new InvokerUtils.Arg(Session.class), new InvokerUtils.Arg(objectType).required()};
    }

    static final InvokerUtils.Arg[] textPartialCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(String.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] binaryPartialBufferCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(ByteBuffer.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] binaryPartialArrayCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(byte[].class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] pongCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(PongMessage.class).required()
    };
}
