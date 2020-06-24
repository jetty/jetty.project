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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Trie;

/**
 *
 */
public enum HttpScheme
{
    HTTP("http"),
    HTTPS("https"),
    WS("ws"),
    WSS("wss");

    public static final Trie<HttpScheme> CACHE = new ArrayTrie<HttpScheme>();

    static
    {
        for (HttpScheme version : HttpScheme.values())
        {
            CACHE.put(version.asString(), version);
        }
    }

    private final String _string;
    private final ByteBuffer _buffer;

    HttpScheme(String s)
    {
        _string = s;
        _buffer = BufferUtil.toBuffer(s);
    }

    public ByteBuffer asByteBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    public boolean is(String s)
    {
        return s != null && _string.equalsIgnoreCase(s);
    }

    public String asString()
    {
        return _string;
    }

    @Override
    public String toString()
    {
        return _string;
    }
}
