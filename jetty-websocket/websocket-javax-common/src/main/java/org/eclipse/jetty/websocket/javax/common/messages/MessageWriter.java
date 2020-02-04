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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * Support for writing a single WebSocket TEXT message via a {@link Writer}
 * <p>
 * Note: Per WebSocket spec, all WebSocket TEXT messages must be encoded in UTF-8
 */
public class MessageWriter extends OutputStreamWriter
{
    private MessageOutputStream outputStream;

    public MessageWriter(CoreSession coreSession)
    {
        this(new MessageOutputStream(coreSession, new MappedByteBufferPool()));
    }

    private MessageWriter(MessageOutputStream outputStream)
    {
        super(outputStream, StandardCharsets.UTF_8);
        this.outputStream = outputStream;
        outputStream.setMessageType(OpCode.TEXT);
    }

    public void setCallback(Callback callback)
    {
        outputStream.setCallback(callback);
    }
}
