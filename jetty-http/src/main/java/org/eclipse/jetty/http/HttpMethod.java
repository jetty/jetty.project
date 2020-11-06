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
    ACL(Type.IDEMPOTENT),
    BASELINE_CONTROL(Type.IDEMPOTENT),
    BIND(Type.IDEMPOTENT),
    CHECKIN(Type.IDEMPOTENT),
    CHECKOUT(Type.IDEMPOTENT),
    CONNECT(Type.NORMAL),
    COPY(Type.IDEMPOTENT),
    DELETE(Type.IDEMPOTENT),
    GET(Type.SAFE),
    HEAD(Type.SAFE),
    LABEL(Type.IDEMPOTENT),
    LINK(Type.IDEMPOTENT),
    LOCK(Type.NORMAL),
    MERGE(Type.IDEMPOTENT),
    MKACTIVITY(Type.IDEMPOTENT),
    MKCALENDAR(Type.IDEMPOTENT),
    MKCOL(Type.IDEMPOTENT),
    MKREDIRECTREF(Type.IDEMPOTENT),
    MKWORKSPACE(Type.IDEMPOTENT),
    MOVE(Type.IDEMPOTENT),
    OPTIONS(Type.SAFE),
    ORDERPATCH(Type.IDEMPOTENT),
    PATCH(Type.NORMAL),
    POST(Type.NORMAL),
    PRI(Type.SAFE),
    PROPFIND(Type.SAFE),
    PROPPATCH(Type.IDEMPOTENT),
    PUT(Type.IDEMPOTENT),
    REBIND(Type.IDEMPOTENT),
    REPORT(Type.SAFE),
    SEARCH(Type.SAFE),
    TRACE(Type.SAFE),
    UNBIND(Type.IDEMPOTENT),
    UNCHECKOUT(Type.IDEMPOTENT),
    UNLINK(Type.IDEMPOTENT),
    UNLOCK(Type.IDEMPOTENT),
    UPDATE(Type.IDEMPOTENT),
    UPDATEREDIRECTREF(Type.IDEMPOTENT),
    VERSION_CONTROL(Type.IDEMPOTENT),

    // Other methods
    PROXY(Type.NORMAL);

    // The type of the method
    private enum Type
    {
        NORMAL,
        IDEMPOTENT,
        SAFE
    }
    
    private final String _method;
    private final byte[] _bytes;
    private final ByteBuffer _buffer;
    private final Type _type;

    HttpMethod(Type type)
    {
        _method = toString().replace('_', '-');
        _type = type;
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

    /**
     * An HTTP method is safe if it doesn't alter the state of the server.
     * In other words, a method is safe if it leads to a read-only operation.
     * Several common HTTP methods are safe: GET , HEAD , or OPTIONS .
     * All safe methods are also idempotent, but not all idempotent methods are safe
     * @return if the method is safe.
     */
    public boolean isSafe()
    {
        return _type == Type.SAFE;
    }

    /**
     * An idempotent HTTP method is an HTTP method that can be called many times without different outcomes.
     * It would not matter if the method is called only once, or ten times over. The result should be the same.
     * @return true if the method is idempotent.
     */
    public boolean isIdempotent()
    {
        return _type.ordinal() >= Type.IDEMPOTENT.ordinal();
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
