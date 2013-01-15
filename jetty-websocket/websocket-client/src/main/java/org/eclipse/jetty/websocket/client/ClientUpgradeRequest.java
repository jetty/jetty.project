//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Allowing a generate from a UpgradeRequest
 */
public class ClientUpgradeRequest extends UpgradeRequest
{
    private final static Logger LOG = Log.getLogger(ClientUpgradeRequest.class);
    private static final Set<String> FORBIDDEN_HEADERS;

    static
    {
        // headers not allowed to be set in ClientUpgradeRequest.headers
        FORBIDDEN_HEADERS = new HashSet<>();
        FORBIDDEN_HEADERS.add("cookie");
        FORBIDDEN_HEADERS.add("upgrade");
        FORBIDDEN_HEADERS.add("host");
        FORBIDDEN_HEADERS.add("connection");
        FORBIDDEN_HEADERS.add("sec-websocket-key");
        FORBIDDEN_HEADERS.add("sec-websocket-extensions");
        FORBIDDEN_HEADERS.add("sec-websocket-accept");
        FORBIDDEN_HEADERS.add("sec-websocket-protocol");
        FORBIDDEN_HEADERS.add("sec-websocket-version");
        FORBIDDEN_HEADERS.add("pragma");
        FORBIDDEN_HEADERS.add("cache-control");
    }

    private final String key;
    private CookieStore cookieStore;
    private int connectTimeout = 0;

    public ClientUpgradeRequest()
    {
        super();
        this.key = genRandomKey();
    }

    protected ClientUpgradeRequest(URI requestURI)
    {
        super(requestURI);
        this.key = genRandomKey();
    }

    public String generate()
    {
        URI uri = getRequestURI();

        StringBuilder request = new StringBuilder(512);
        request.append("GET ");
        if (StringUtil.isBlank(uri.getPath()))
        {
            request.append("/");
        }
        else
        {
            request.append(uri.getPath());
        }
        if (StringUtil.isNotBlank(uri.getRawQuery()))
        {
            request.append("?").append(uri.getRawQuery());
        }
        request.append(" HTTP/1.1\r\n");

        request.append("Host: ").append(uri.getHost());
        if (uri.getPort() > 0)
        {
            request.append(':').append(uri.getPort());
        }
        request.append("\r\n");

        // WebSocket specifics
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n"); // RFC-6455 specified version

        // (Per the hybi list): Add no-cache headers to avoid compatibility issue.
        // There are some proxies that rewrite "Connection: upgrade"
        // to "Connection: close" in the response if a request doesn't contain
        // these headers.
        request.append("Pragma: no-cache\r\n");
        request.append("Cache-Control: no-cache\r\n");

        // Extensions
        if (!getExtensions().isEmpty())
        {
            request.append("Sec-WebSocket-Extensions: ");
            boolean needDelim = false;
            for (ExtensionConfig ext : getExtensions())
            {
                if (needDelim)
                {
                    request.append(", ");
                }
                request.append(ext.getParameterizedName());
                needDelim = true;
            }
            request.append("\r\n");
        }

        // Sub Protocols
        if (!getSubProtocols().isEmpty())
        {
            request.append("Sec-WebSocket-Protocol: ");
            boolean needDelim = false;
            for (String protocol : getSubProtocols())
            {
                if (needDelim)
                {
                    request.append(", ");
                }
                request.append(protocol);
                needDelim = true;
            }
            request.append("\r\n");
        }

        // Cookies
        List<HttpCookie> cookies = getCookies();
        if ((cookies != null) && (cookies.size() > 0))
        {
            request.append("Cookie: ");
            boolean needDelim = false;
            for (HttpCookie cookie : cookies)
            {
                if (needDelim)
                {
                    request.append("; ");
                }
                request.append(cookie.toString());
                needDelim = true;
            }
            request.append("\r\n");
        }

        // Other headers
        for (String key : getHeaders().keySet())
        {
            if (FORBIDDEN_HEADERS.contains(key.toLowerCase()))
            {
                LOG.warn("Skipping forbidden header - {}",key);
                continue; // skip
            }
            request.append(key).append(": ");
            request.append(getHeader(key));
            request.append("\r\n");
        }

        // request header end
        request.append("\r\n");
        return request.toString();
    }

    private final String genRandomKey()
    {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        return new String(B64Code.encode(bytes));
    }

    public int getConnectTimeout()
    {
        return connectTimeout;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        if (cookieStore != null)
        {
            return cookieStore.get(getRequestURI());
        }

        return super.getCookies();
    }

    public CookieStore getCookieStore()
    {
        return cookieStore;
    }

    public String getKey()
    {
        return key;
    }

    public void setConnectTimeout(int connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        this.cookieStore = cookieStore;
    }
}
