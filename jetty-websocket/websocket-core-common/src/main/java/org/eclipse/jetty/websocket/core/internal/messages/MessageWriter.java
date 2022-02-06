//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.OpCode;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Support for writing a single WebSocket TEXT message via a {@link Writer}
 * <p>
 * Note: Per WebSocket spec, all WebSocket TEXT messages must be encoded in UTF-8
 */
public class MessageWriter extends Writer
{
    private final MessageOutputStream outputStream;
    private final CharsetEncoder utf8Encoder = UTF_8.newEncoder()
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .onMalformedInput(CodingErrorAction.REPORT);

    public MessageWriter(CoreSession coreSession, ByteBufferPool bufferPool)
    {
        this.outputStream = new MessageOutputStream(coreSession, bufferPool);
        this.outputStream.setMessageType(OpCode.TEXT);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        CharBuffer charBuffer = CharBuffer.wrap(cbuf, off, len);
        outputStream.write(utf8Encoder.encode(charBuffer));
    }

    @Override
    public void flush() throws IOException
    {
        outputStream.flush();
    }

    @Override
    public void close() throws IOException
    {
        outputStream.close();
    }

    public void setCallback(Callback callback)
    {
        outputStream.setCallback(callback);
    }
}