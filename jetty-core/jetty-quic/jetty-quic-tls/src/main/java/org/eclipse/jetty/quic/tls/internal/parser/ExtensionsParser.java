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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;

/// A parser for a list of TLS extensions carried in a TLS message.
public class ExtensionsParser
{
    private final Map<Extension.Type, ExtensionParser> parsers = new EnumMap<>(Extension.Type.class);
    private final List<Extension>  extensions = new ArrayList<>();
    private State state = State.LENGTH;
    private int cursor;
    private int length;
    private int consumed;
    private int code;
    private Extension.Type type;

    public ExtensionsParser()
    {
        ExtensionParser.Listener listener = extensions::add;
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
        return parsers.put(parser.type(), parser);
    }

    public List<Extension> parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (true)
        {
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;
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
                        code = (byteBuffer.getShort() & 0xFFFF);
                        type = Extension.Type.from(code);
                        if (type == null)
                            throw new UnsupportedOperationException("could not parse unsupported TLS extension 0x" + Integer.toHexString(code));
                        consumed += 2;
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
                    code += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        type = Extension.Type.from(code);
                        if (type == null)
                            throw new UnsupportedOperationException("could not parse unsupported TLS extension 0x" + Integer.toHexString(code));
                        consumed += 2;
                        state = State.BODY;
                    }
                }
                case BODY ->
                {
                    ExtensionParser parser = parsers.get(type);
                    if (parser == null)
                        throw new UnsupportedOperationException("could not parse unsupported TLS extension 0x" + Integer.toHexString(code));
                    int parsed = parser.parse(buffer);
                    if (parsed < 0)
                        return null;
                    code = 0;
                    consumed += parsed;
                    if (consumed == length)
                    {
                        state = State.LENGTH;
                        length = 0;
                        consumed = 0;
                        List<Extension> result = List.copyOf(extensions);
                        extensions.clear();
                        return result;
                    }
                    else
                    {
                        state = State.TYPE;
                    }
                }
            }
        }
    }

    private enum State
    {
        LENGTH, LENGTH_BYTES, TYPE, TYPE_BYTES, BODY
    }
}
