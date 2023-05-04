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

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Support class for reading a (single) WebSocket TEXT message via a Reader.
 * <p>
 * In compliance to the WebSocket spec, this reader always uses the {@link StandardCharsets#UTF_8}.
 */
public class MessageReader extends Reader implements MessageSink
{
    private final ByteBuffer buffer;
    private final MessageInputStream stream;
    private final CharsetDecoder utf8Decoder = UTF_8.newDecoder()
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .onMalformedInput(CodingErrorAction.REPORT);

    public MessageReader(CoreSession coreSession)
    {
        this.stream = new MessageInputStream(coreSession);
        this.buffer = BufferUtil.allocate(coreSession.getInputBufferSize());
    }

    @Override
    public int read(char[] chars, int off, int len) throws IOException
    {
        CharBuffer charBuffer = CharBuffer.wrap(chars, off, len);
        boolean eof;
        while (true)
        {
            int read = stream.read(buffer);
            eof = read < 0;
            if (eof || read == 0)
                break;
        }

        CoderResult result = utf8Decoder.decode(buffer, charBuffer, eof);
        if (result.isError())
            result.throwException();

        if (eof && charBuffer.position() == off)
            return -1;

        return charBuffer.position() - off;
    }

    @Override
    public void fail(Throwable failure)
    {
        stream.fail(failure);
    }

    @Override
    public void close() throws IOException
    {
        stream.close();
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        stream.accept(frame, callback);
    }
}
