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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@SuppressWarnings("unused")
@WebSocket
public class EchoSocket extends EventSocket
{
    @Override
    public void onMessage(String message) throws IOException
    {
        super.onMessage(message);
        session.sendText(message, Callback.NOOP);
    }

    @Override
    public void onMessage(byte[] buf, int offset, int len) throws IOException
    {
        super.onMessage(buf, offset, len);
        ByteBuffer buffer = ByteBuffer.wrap(buf, offset, len);
        session.sendBinary(buffer, Callback.NOOP);
    }
}
