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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class SupportedVersionsExtensionParser implements ExtensionParser
{
    private final List<TLSVersion> versions = new ArrayList<>();
    private final ExtensionParser.Listener listener;
    private final boolean client;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int version;
    private int cursor;

    public SupportedVersionsExtensionParser(Listener listener, boolean client)
    {
        this.listener = listener;
        this.client = client;
    }

    @Override
    public int type()
    {
        return SupportedVersionsExtension.CODE;
    }

    @Override
    public long parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            int remaining = Math.toIntExact(buffer.remaining());
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = buffer.getShortAsInt();
                        if (totalLength < (client ? 2 : 3))
                            throw new IllegalStateException("invalid supported versions extension length " + totalLength);
                        state = client ? State.VERSION : State.LIST_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = buffer.getByteAsInt();
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (totalLength < (client ? 2 : 3))
                            throw new IllegalStateException("invalid supported versions extension length " + totalLength);
                        state = client ? State.VERSION : State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    listLength = buffer.getByteAsInt();
                    if (listLength % 2 != 0)
                        throw new IllegalStateException("invalid supported versions list length " + listLength);
                    state = State.VERSION;
                }
                case VERSION ->
                {
                    if (remaining >= 2)
                    {
                        version = buffer.getShortAsInt();
                        if (!client)
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
                    int b = buffer.getByteAsInt();
                    --cursor;
                    version += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (!client)
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
            int result = 2 + totalLength;
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
