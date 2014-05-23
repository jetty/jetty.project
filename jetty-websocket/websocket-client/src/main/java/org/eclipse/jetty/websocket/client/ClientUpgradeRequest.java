//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

/**
 * Allowing a generate from a UpgradeRequest
 */
public class ClientUpgradeRequest extends UpgradeRequest
{
    private static final Logger LOG = Log.getLogger(ClientUpgradeRequest.class);
    private static final int MAX_KEYS = -1; // maximum number of parameter keys to decode
    private static final Set<String> FORBIDDEN_HEADERS;

    static
    {
        // Headers not allowed to be set in ClientUpgradeRequest.headers.
        FORBIDDEN_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Cookies are handled explicitly, avoid to add them twice.
        FORBIDDEN_HEADERS.add("cookie");
        // Headers that cannot be set by applications.
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
            if (FORBIDDEN_HEADERS.contains(key))
            {
                LOG.debug("Skipping forbidden header - {}",key);
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
        ThreadLocalRandom.current().nextBytes(bytes);
        return new String(B64Code.encode(bytes));
    }

    public String getKey()
    {
        return key;
    }

    public void setCookiesFrom(CookieStore cookieStore)
    {
        if (cookieStore == null)
        {
            return;
        }

        List<HttpCookie> existing = getCookies();
        List<HttpCookie> extra = cookieStore.get(getRequestURI());

        List<HttpCookie> cookies = new ArrayList<>();
        if (LazyList.hasEntry(existing))
        {
            cookies.addAll(existing);
        }
        if (LazyList.hasEntry(extra))
        {
            cookies.addAll(extra);
        }
        setCookies(cookies);
    }

    @Override
    public void setRequestURI(URI uri)
    {
        super.setRequestURI(uri);

        // parse parameter map
        Map<String, List<String>> pmap = new HashMap<>();

        String query = uri.getQuery();

        if (StringUtil.isNotBlank(query))
        {
            MultiMap<String> params = new MultiMap<String>();
            UrlEncoded.decodeTo(uri.getQuery(),params,StandardCharsets.UTF_8,MAX_KEYS);

            for (String key : params.keySet())
            {
                List<String> values = params.getValues(key);
                if (values == null)
                {
                    pmap.put(key,new ArrayList<String>());
                }
                else
                {
                    // break link to original
                    List<String> copy = new ArrayList<>();
                    copy.addAll(values);
                    pmap.put(key,copy);
                }
            }

            super.setParameterMap(pmap);
        }
    }
}
