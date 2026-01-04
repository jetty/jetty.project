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

package org.eclipse.jetty.tls.common.parser;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.Message;

public class MessagesParser
{
    private final Map<Message.Type, MessageParser> parsers = new HashMap<>();
    private State state = State.TYPE;
    private int cursor;
    private Message.Type type;
    private int length; // TODO: remove this field?

    public MessagesParser()
    {
        ExtensionsParser extensionsParser = new ExtensionsParser();
        parsers.put(Message.Type.CLIENT_HELLO, new ClientHelloParser(extensionsParser));
        parsers.put(Message.Type.SERVER_HELLO, new ServerHelloParser(extensionsParser));
        parsers.put(Message.Type.ENCRYPTED_EXTENSIONS, new EncryptedExtensionsParser(extensionsParser));
    }

    public Message parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (true)
        {
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;
            switch (state)
            {
                case TYPE ->
                {
                    int messageType = byteBuffer.get() & 0xFF;
                    type = Message.Type.from(messageType);
                    if (type == null)
                        throw new UnsupportedOperationException("could not parse unsupported TLS message 0x" + Integer.toHexString(messageType));
                    state = State.LENGTH;
                }
                case LENGTH ->
                {
                    if (remaining > 2)
                    {
                        length = ((byteBuffer.getShort() & 0xFFFF) << 8) | (byteBuffer.get() & 0xFF);
                        state = State.MESSAGE;
                    }
                    else
                    {
                        cursor = 3;
                        state = State.LENGTH_BYTES;
                    }
                }
                case LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    length += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.MESSAGE;
                }
                case MESSAGE ->
                {
                    MessageParser messageParser = parsers.get(type);
                    if (messageParser == null)
                        throw new UnsupportedOperationException("could not parse unsupported TLS message " + type);
                    Message message = messageParser.parse(buffer);
                    if (message == null)
                        return null;
                    type = null;
                    state = State.TYPE;
                    return message;
                }
            }
        }
    }

    private enum State
    {
        TYPE,
        LENGTH,
        LENGTH_BYTES,
        MESSAGE
    }
}
