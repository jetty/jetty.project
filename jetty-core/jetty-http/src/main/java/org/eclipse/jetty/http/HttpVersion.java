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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;

public enum HttpVersion
{
    HTTP_0_9("HTTP/0.9", 9),
    HTTP_1_0("HTTP/1.0", 10),
    HTTP_1_1("HTTP/1.1", 11),
    HTTP_2("HTTP/2.0", 20),
    HTTP_3("HTTP/3.0", 30);

    public static final Index<HttpVersion> CACHE = new Index.Builder<HttpVersion>()
        .caseSensitive(false)
        .withAll(HttpVersion.values(), HttpVersion::toString)
        .build();

    private final String _string;
    private final byte[] _bytes;
    private final ByteBuffer _buffer;
    private final int _version;

    HttpVersion(String s, int version)
    {
        _string = s;
        _bytes = StringUtil.getBytes(s);
        _buffer = ByteBuffer.wrap(_bytes);
        _version = version;
    }

    public byte[] toBytes()
    {
        return _bytes;
    }

    public ByteBuffer toBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    public int getVersion()
    {
        return _version;
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

    /**
     * Case insensitive fromString() conversion
     *
     * @param version the String to convert to enum constant
     * @return the enum constant or null if version unknown
     */
    public static HttpVersion fromString(String version)
    {
        return CACHE.get(version);
    }

    public static HttpVersion fromVersion(int version)
    {
        switch (version)
        {
            case 9:
                return HttpVersion.HTTP_0_9;
            case 10:
                return HttpVersion.HTTP_1_0;
            case 11:
                return HttpVersion.HTTP_1_1;
            case 20:
                return HttpVersion.HTTP_2;
            case 30:
                return HttpVersion.HTTP_3;
            default:
                throw new IllegalArgumentException();
        }
    }
}
