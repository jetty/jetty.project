//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;

/**
 * Known HTTP Methods
 */
public enum HttpMethod
{
    // From https://www.iana.org/assignments/http-methods/http-methods.xhtml
    ACL(false, true),
    BASELINE_CONTROL(false, true),
    BIND(false, true),
    CHECKIN(false, true),
    CHECKOUT(false, true),
    CONNECT(false, false),
    COPY(false, true),
    DELETE(false, true),
    GET(true, true),
    HEAD(true, true),
    LABEL(false, true),
    LINK(false, true),
    LOCK(false, false),
    MERGE(false, true),
    MKACTIVITY(false, true),
    MKCALENDAR(false, true),
    MKCOL(false, true),
    MKREDIRECTREF(false, true),
    MKWORKSPACE(false, true),
    MOVE(false, true),
    OPTIONS(true, true),
    ORDERPATCH(false, true),
    PATCH(false, false),
    POST(false, false),
    PRI(true, true),
    PROPFIND(true, true),
    PROPPATCH(false, true),
    PUT(false, true),
    REBIND(false, true),
    REPORT(true, true),
    SEARCH(true, true),
    TRACE(true, true),
    UNBIND(false, true),
    UNCHECKOUT(false, true),
    UNLINK(false, true),
    UNLOCK(false, true),
    UPDATE(false, true),
    UPDATEREDIRECTREF(false, true),
    VERSION_CONTROL(false, true),

    // Other methods
    PROXY(false, false);

    private final String _method;
    private final byte[] _bytes;
    private final ByteBuffer _buffer;
    private final boolean _safe;
    private final boolean _idempotent;

    HttpMethod(boolean safe, boolean idempotent)
    {
        _method = toString().replace('_', '-');
        _safe = safe;
        _idempotent = idempotent;
        _bytes = StringUtil.getBytes(_method);
        _buffer = ByteBuffer.wrap(_bytes);
    }

    public byte[] getBytes()
    {
        return _bytes;
    }

    public boolean is(String s)
    {
        return toString().equalsIgnoreCase(s);
    }

    public boolean isSafe()
    {
        return _safe;
    }

    public boolean isIdempotent()
    {
        return _idempotent;
    }

    public ByteBuffer asBuffer()
    {
        return _buffer.asReadOnlyBuffer();
    }

    public String asString()
    {
        return _method;
    }

    public String toString()
    {
        return _method;
    }

    public static final Trie<HttpMethod> INSENSITIVE_CACHE = new ArrayTrie<>(252);
    public static final Trie<HttpMethod> CACHE = new ArrayTernaryTrie<>(false, 300);
    public static final Trie<HttpMethod> LOOK_AHEAD = new ArrayTernaryTrie<>(false, 330);
    static
    {
        for (HttpMethod method : HttpMethod.values())
        {
            if (!INSENSITIVE_CACHE.put(method.asString(), method))
                throw new IllegalStateException("INSENSITIVE_CACHE too small: " + method);

            if (!CACHE.put(method.asString(), method))
                throw new IllegalStateException("CACHE too small: " + method);

            if (!LOOK_AHEAD.put(method.asString() + ' ', method))
                throw new IllegalStateException("LOOK_AHEAD too small: " + method);
        }
    }

    /**
     * Optimized lookup to find a method name and trailing space in a byte array.
     *
     * @param bytes Array containing ISO-8859-1 characters
     * @param position The first valid index
     * @param limit The first non valid index
     * @return An HttpMethod if a match or null if no easy match.
     */
    public static HttpMethod lookAheadGet(byte[] bytes, final int position, int limit)
    {
        int len = limit - position;
        if (limit > 3)
        {
            // Short cut for GET
            if (bytes[position] == 'G' && bytes[position + 1] == 'E' && bytes[position + 2] == 'T' && bytes[position + 3] == ' ')
                return GET;
            // Otherwise lookup in the Trie
            return LOOK_AHEAD.getBest(bytes, position, len);
        }
        return null;
    }

    /**
     * Optimized lookup to find a method name and trailing space in a byte array.
     *
     * @param buffer buffer containing ISO-8859-1 characters, it is not modified.
     * @return An HttpMethod if a match or null if no easy match.
     * @deprecated Not used
     */
    @Deprecated
    public static HttpMethod lookAheadGet(ByteBuffer buffer)
    {
        return LOOK_AHEAD.getBest(buffer, 0, buffer.remaining());
    }

    /**
     * Converts the given String parameter to an HttpMethod.
     * The string may differ from the Enum name as a '-'  in the method
     * name is represented as a '_' in the Enum name.
     *
     * @param method the String to get the equivalent HttpMethod from
     * @return the HttpMethod or null if the parameter method is unknown
     */
    public static HttpMethod fromString(String method)
    {
        return CACHE.get(method);
    }
}
