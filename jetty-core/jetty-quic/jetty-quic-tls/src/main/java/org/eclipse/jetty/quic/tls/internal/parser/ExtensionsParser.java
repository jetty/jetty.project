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

package org.eclipse.jetty.quic.tls.internal.parser;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;

public class ExtensionsParser
{
    private final Map<Integer, ExtensionParser> parsers = new HashMap<>();
    private State state = State.LENGTH;
    private int length;
    private int type;
    private int cursor;

    public ExtensionsParser(Listener listener)
    {
        put(new ServerNameExtensionParser(listener));
        put(new ALPNExtensionParser(listener));
        put(new KeyShareExtensionParser(listener));
        put(new SignatureAlgorithmsExtensionParser(listener));
        put(new SupportedGroupsExtensionParser(listener));
        put(new SupportedVersionsExtensionParser(listener));
        put(new QuicTransportParametersExtensionParser(listener));
    }

    public ExtensionParser put(ExtensionParser parser)
    {
        return parsers.put(parser.getType(), parser);
    }

    public boolean parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (true)
        {
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return false;
            switch (state)
            {
                case LENGTH ->
                {
                    if (remaining > 1)
                    {
                        length = (byteBuffer.getShort() & 0xFFFF);
                        state = State.TYPE;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.LENGTH_BYTES;
                    }
                }
                case LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    length += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.TYPE;
                }
                case TYPE ->
                {
                    if (remaining > 1)
                    {
                        type = (byteBuffer.getShort() & 0xFFFF);
                        state = State.BODY;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TYPE_BYTES;
                    }
                }
                case TYPE_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    type += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.BODY;
                }
                case BODY ->
                {
                    ExtensionParser parser = parsers.get(type);
                    if (parser == null)
                        throw new UnsupportedOperationException("could not parse unsupported TLS extension 0x" + Integer.toHexString(type));
                    int parsed = parser.parse(buffer);
                    if (parsed < 0)
                        return false;
                    type = 0;
                    length -= parsed;
                    if (length == 0)
                    {
                        state = State.LENGTH;
                        return true;
                    }
                    else
                    {
                        state = State.TYPE;
                    }
                }
            }
        }
    }

    public interface Listener
    {
        void onExtension(Extension extension);
    }

    private enum State
    {
        LENGTH, LENGTH_BYTES, TYPE, TYPE_BYTES, BODY
    }
}
