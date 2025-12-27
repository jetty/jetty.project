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
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.SupportedVersionsExtension;
import org.eclipse.jetty.quic.tls.message.TLSVersion;

public class SupportedVersionsExtensionParser implements ExtensionParser
{
    private final List<TLSVersion> versions = new ArrayList<>();
    private final ExtensionsParser.Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int version;
    private int cursor;

    public SupportedVersionsExtensionParser(ExtensionsParser.Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int getType()
    {
        return SupportedVersionsExtension.TYPE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = byteBuffer.getShort() & 0xFFFF;
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid supported versions extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid supported versions extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    listLength = byteBuffer.get() & 0xFF;
                    if (listLength % 2 != 0)
                        throw new IllegalStateException("invalid supported versions list length " + listLength);
                    state = State.VERSION;
                }
                case VERSION ->
                {
                    if (remaining >= 2)
                    {
                        version = byteBuffer.getShort() & 0xFFFF;
                        listLength -= 2;
                        int result = versionComplete();
                        if (result > 0)
                            return result;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.VERSION_BYTES;
                    }
                }
                case VERSION_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    version += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        listLength -= 2;
                        int result = versionComplete();
                        if (result > 0)
                            return result;
                    }
                }
            }
        }
    }

    private int versionComplete()
    {
        versions.add(TLSVersion.from(version));
        version = 0;
        if (listLength == 0)
        {
            int result = totalLength;
            totalLength = 0;
            List<TLSVersion> tlsVersions = List.copyOf(versions);
            versions.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new SupportedVersionsExtension(tlsVersions));
            return result;
        }
        else
        {
            state = State.VERSION;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        VERSION,
        VERSION_BYTES
    }
}
