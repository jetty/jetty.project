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
import java.util.EnumSet;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Index;

/**
 *
 */
public enum HttpHeaderValue
{
    CLOSE("close"),
    CHUNKED("chunked"),
    GZIP("gzip"),
    IDENTITY("identity"),
    KEEP_ALIVE("keep-alive"),
    CONTINUE("100-continue"),
    PROCESSING("102-processing"),
    TE("TE"),
    BYTES("bytes"),
    NO_CACHE("no-cache"),
    UPGRADE("Upgrade");

    public static final Index<HttpHeaderValue> CACHE = new Index.Builder<HttpHeaderValue>()
        .caseSensitive(false)
        .withAll(HttpHeaderValue.values(), HttpHeaderValue::toString)
        .build();

    private final String _string;
    private final ByteBuffer _buffer;

    HttpHeaderValue(String s)
    {
        _string = s;
        _buffer = BufferUtil.toBuffer(s);
    }

    public ByteBuffer toBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    public boolean is(String s)
    {
        return _string.equalsIgnoreCase(s);
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

    private static EnumSet<HttpHeader> __known =
        EnumSet.of(HttpHeader.CONNECTION,
            HttpHeader.TRANSFER_ENCODING,
            HttpHeader.CONTENT_ENCODING);

    public static boolean hasKnownValues(HttpHeader header)
    {
        if (header == null)
            return false;
        return __known.contains(header);
    }
}
