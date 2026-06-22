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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ext.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A parser for a list of TLS extensions carried in a TLS message.
public class ExtensionsParser implements ExtensionParser.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(ExtensionsParser.class);

    private final Map<Integer, ExtensionParser> parsers = new HashMap<>();
    private final List<Extension> extensions = new ArrayList<>();
    private State state = State.LENGTH;
    private int cursor;
    private int length;
    private int consumed;
    private int code;

    public ExtensionsParser(boolean client)
    {
        put(new ALPNExtensionParser(this));
        put(new EarlyDataExtensionParser(this));
        put(new KeyShareExtensionParser(this, client));
        put(client ? new ServerPreSharedKeyExtensionParser(this) : new ClientPreSharedKeyExtensionParser(this));
        put(new ServerNameExtensionParser(this));
        put(new SignatureAlgorithmsExtensionParser(this));
        put(new SupportedGroupsExtensionParser(this));
        put(new SupportedVersionsExtensionParser(this, client));
    }

    @Override
    public void onExtension(Extension extension)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsed TLS extension: {}", extension);
        extensions.add(extension);
    }

    public ExtensionParser put(ExtensionParser parser)
    {
        return parsers.put(parser.type(), parser);
    }

    public List<Extension> parse(RetainableByteBuffer buffer)
    {
        return parse(buffer, _ -> {});
    }

    public List<Extension> parse(RetainableByteBuffer buffer, IntConsumer lengthConsumer)
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
                        if (length == 0)
                        {
                            lengthConsumer.accept(2);
                            return List.of();
                        }
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
                    {
                        if (length == 0)
                        {
                            lengthConsumer.accept(2);
                            return List.of();
                        }
                        state = State.TYPE;
                    }
                }
                case TYPE ->
                {
                    if (remaining > 1)
                    {
                        code = (byteBuffer.getShort() & 0xFFFF);
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
                        consumed += 2;
                        state = State.BODY;
                    }
                }
                case BODY ->
                {
                    ExtensionParser parser = parsers.computeIfAbsent(code, k -> new UnknownExtensionParser(code, this));
                    int parsed = parser.parse(buffer);
                    if (parsed < 0)
                        return null;
                    code = 0;
                    consumed += parsed;
                    if (consumed == length)
                    {
                        state = State.LENGTH;
                        lengthConsumer.accept(2 + length);
                        length = 0;
                        consumed = 0;
                        List<Extension> result = List.copyOf(extensions);
                        extensions.clear();

                        if (LOG.isDebugEnabled())
                            LOG.debug("parsed TLS extensions: {}", String.join(System.lineSeparator(), result.stream().map(String::valueOf).toList()));

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
