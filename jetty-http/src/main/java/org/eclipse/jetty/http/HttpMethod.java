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
    ACL("ACL", Type.IDEMPOTENT),
    BASELINE_CONTROL("BASELINE-CONTROL", Type.IDEMPOTENT),
    BIND("BIND", Type.IDEMPOTENT),
    CHECKIN("CHECKIN", Type.IDEMPOTENT),
    CHECKOUT("CHECKOUT", Type.IDEMPOTENT),
    CONNECT("CONNECT", Type.NORMAL),
    COPY("COPY", Type.IDEMPOTENT),
    DELETE("DELETE", Type.IDEMPOTENT),
    GET("GET", Type.SAFE),
    HEAD("HEAD", Type.SAFE),
    LABEL("LABEL", Type.IDEMPOTENT),
    LINK("LINK", Type.IDEMPOTENT),
    LOCK("LOCK", Type.NORMAL),
    MERGE("MERGE", Type.IDEMPOTENT),
    MKACTIVITY("MKACTIVITY", Type.IDEMPOTENT),
    MKCALENDAR("MKCALENDAR", Type.IDEMPOTENT),
    MKCOL("MKCOL", Type.IDEMPOTENT),
    MKREDIRECTREF("MKREDIRECTREF", Type.IDEMPOTENT),
    MKWORKSPACE("MKWORKSPACE", Type.IDEMPOTENT),
    MOVE("MOVE", Type.IDEMPOTENT),
    OPTIONS("OPTIONS", Type.SAFE),
    ORDERPATCH("ORDERPATCH", Type.IDEMPOTENT),
    PATCH("PATCH", Type.NORMAL),
    POST("POST", Type.NORMAL),
    PRI("PRI", Type.SAFE),
    PROPFIND("PROPFIND", Type.SAFE),
    PROPPATCH("PROPPATCH", Type.IDEMPOTENT),
    PUT("PUT", Type.IDEMPOTENT),
    REBIND("REBIND", Type.IDEMPOTENT),
    REPORT("REPORT", Type.SAFE),
    SEARCH("SEARCH", Type.SAFE),
    TRACE("TRACE", Type.SAFE),
    UNBIND("UNBIND", Type.IDEMPOTENT),
    UNCHECKOUT("UNCHECKOUT", Type.IDEMPOTENT),
    UNLINK("UNLINK", Type.IDEMPOTENT),
    UNLOCK("UNLOCK", Type.IDEMPOTENT),
    UPDATE("UPDATE", Type.IDEMPOTENT),
    UPDATEREDIRECTREF("UPDATEREDIRECTREF", Type.IDEMPOTENT),
    VERSION_CONTROL("VERSION-CONTROL", Type.IDEMPOTENT),

    // Other methods
    PROXY("PROXY", Type.NORMAL);

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

    HttpMethod(String method, Type type)
    {
        _method = method;
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
    private static final int ACL_AS_INT = ('A' & 0xff) << 24 | ('C' & 0xFF) << 16 | ('L' & 0xFF) << 8 | (' ' & 0xFF);
    private static final int GET_AS_INT = ('G' & 0xff) << 24 | ('E' & 0xFF) << 16 | ('T' & 0xFF) << 8 | (' ' & 0xFF);
    private static final int PRI_AS_INT = ('P' & 0xff) << 24 | ('R' & 0xFF) << 16 | ('I' & 0xFF) << 8 | (' ' & 0xFF);
    private static final int PUT_AS_INT = ('P' & 0xff) << 24 | ('U' & 0xFF) << 16 | ('T' & 0xFF) << 8 | (' ' & 0xFF);
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
     * @deprecated Not used
     */
    @Deprecated
    public static HttpMethod lookAheadGet(byte[] bytes, final int position, int limit)
    {
        return LOOK_AHEAD.getBest(bytes, position, limit - position);
    }

    /**
     * Optimized lookup to find a method name and trailing space in a byte array.
     *
     * @param buffer buffer containing ISO-8859-1 characters, it is not modified.
     * @return An HttpMethod if a match or null if no easy match.
     */
    public static HttpMethod lookAheadGet(ByteBuffer buffer)
    {
        int len = buffer.remaining();
        // Short cut for 3 char methods, mostly for GET optimisation
        if (len > 3)
        {
            switch (buffer.getInt(buffer.position()))
            {
                case ACL_AS_INT:
                    return ACL;
                case GET_AS_INT:
                    return GET;
                case PRI_AS_INT:
                    return PRI;
                case PUT_AS_INT:
                    return PUT;
                default:
                    break;
            }
        }
        return LOOK_AHEAD.getBest(buffer, 0, len);
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
