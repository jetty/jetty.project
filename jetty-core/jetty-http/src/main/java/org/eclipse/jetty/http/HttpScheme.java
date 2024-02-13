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
import java.util.Locale;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;

/**
 * HTTP and WebSocket Schemes
 */
public enum HttpScheme
{
    HTTP("http", 80),
    HTTPS("https", 443),
    WS("ws", 80),
    WSS("wss", 443);

    public static final Index<HttpScheme> CACHE = new Index.Builder<HttpScheme>()
        .caseSensitive(false)
        .withAll(HttpScheme.values(), HttpScheme::asString)
        .build();

    private final String _string;
    private final ByteBuffer _buffer;
    private final int _defaultPort;

    HttpScheme(String s, int port)
    {
        _string = s;
        _buffer = BufferUtil.toBuffer(s);
        _defaultPort = port;
    }

    public ByteBuffer asByteBuffer()
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

    public int getDefaultPort()
    {
        return _defaultPort;
    }

    public int normalizePort(int port)
    {
        return port == _defaultPort ? 0 : port;
    }

    @Override
    public String toString()
    {
        return _string;
    }

    public static int getDefaultPort(String scheme)
    {
        HttpScheme httpScheme = scheme == null ? null : CACHE.get(scheme);
        return httpScheme == null ? HTTP.getDefaultPort() : httpScheme.getDefaultPort();
    }

    public static int normalizePort(String scheme, int port)
    {
        HttpScheme httpScheme = scheme == null ? null : CACHE.get(scheme);
        return httpScheme == null ? port : httpScheme.normalizePort(port);
    }

    /**
     * Normalize a provided scheme, server, and port into a String form.
     *
     * @param scheme the scheme, may not be blank or null.
     * @param server the server hostname, may not be blank or null.
     * @param port the port
     * @return the String representation following the general format {@code <scheme>://<server>:<port>}, where
     * the returned String might not include a port, and will not include the ending {@code /} character.
     * @see #appendNormalizedUri(StringBuilder, String, String, int)
     */
    public static String normalizeUri(String scheme, String server, int port)
    {
        return normalizeUri(scheme, server, port, null, null);
    }

    /**
     * Normalize a provided scheme, server, and port into a String form.
     *
     * @param scheme the scheme, may not be blank or null.
     * @param server the server hostname, may not be blank or null.
     * @param port the port
     * @param path the optional path, may be null
     * @param query the optional query, may be null (not included in output if path is null)
     * @return the String representation following the general format {@code <scheme>://<server>:<port>/<path>?<query>}, where
     * the returned String might not include a port.
     * @see #appendNormalizedUri(StringBuilder, String, String, int)
     */
    public static String normalizeUri(String scheme, String server, int port, String path, String query)
    {
        StringBuilder builder = new StringBuilder();
        appendNormalizedUri(builder, scheme, server, port, path, query);
        return builder.toString();
    }

    /**
     * Normalize a provided scheme, server, and port and append to StringBuilder.
     *
     * <p>
     * Performing the following:
     * </p>
     * <ul>
     *     <li>Scheme is reduced to lowercase</li>
     *     <li>Server is normalized via {@link HostPort#normalizeHost(String)}</li>
     *     <li>Port is only added if provided and it does not match the scheme default.</li>
     * </ul>
     *
     * @param url the StringBuilder to build normalized URI into.
     * @param scheme the scheme, may not be blank or null.
     * @param server the server hostname, may not be blank or null.
     * @param port the port
     */
    public static void appendNormalizedUri(StringBuilder url, String scheme, String server, int port)
    {
        appendNormalizedUri(url, scheme, server, port, null, null);
    }

    public static void appendNormalizedUri(StringBuilder url, String scheme, String server, int port, String path, String query)
    {
        if (StringUtil.isBlank(scheme))
            throw new IllegalArgumentException("scheme is blank");
        if (StringUtil.isBlank(server))
            throw new IllegalArgumentException("server is blank");

        HttpScheme httpScheme = CACHE.get(scheme);

        if (httpScheme != null)
            url.append(httpScheme.asString());
        else
            url.append(scheme.toLowerCase(Locale.ENGLISH));

        url.append("://");
        url.append(HostPort.normalizeHost(server));

        if (port > 0)
        {
            boolean secure = isSecure(scheme);
            if ((secure && port != 443) ||
                (!secure && port != 80))
                url.append(':').append(Integer.toString(port));
        }

        if (path != null)
        {
            url.append(path);
            if (StringUtil.isNotBlank(query))
            {
                url.append('?').append(query);
            }
        }
    }

    public static boolean isSecure(String scheme)
    {
        return HttpScheme.HTTPS.is(scheme) || HttpScheme.WSS.is(scheme);
    }
}
