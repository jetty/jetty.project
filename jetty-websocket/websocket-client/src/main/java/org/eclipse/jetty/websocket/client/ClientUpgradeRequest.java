//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.UpgradeRequestAdapter;

/**
 * Allowing a generate from a UpgradeRequest
 */
public class ClientUpgradeRequest extends UpgradeRequestAdapter
{
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
    private Object localEndpoint;
    private long timeout;

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

    public ClientUpgradeRequest(WebSocketUpgradeRequest wsRequest)
    {
        this(wsRequest.getURI());
        // cookies
        this.setCookies(wsRequest.getCookies());
        // headers
        Map<String, List<String>> headers = new HashMap<>();
        HttpFields fields = wsRequest.getHeaders();
        for (HttpField field : fields)
        {
            String key = field.getName();
            List<String> values = headers.get(key);
            if (values == null)
            {
                values = new ArrayList<>();
            }
            values.addAll(Arrays.asList(field.getValues()));
            headers.put(key, values);
            // sub protocols
            if (key.equalsIgnoreCase("Sec-WebSocket-Protocol"))
            {
                for (String subProtocol : field.getValue().split(","))
                {
                    setSubProtocols(subProtocol);
                }
            }
            // extensions
            if (key.equalsIgnoreCase("Sec-WebSocket-Extensions"))
            {
                for (ExtensionConfig ext : ExtensionConfig.parseList(field.getValues()))
                {
                    addExtensions(ext);
                }
            }
        }
        super.setHeaders(headers);
        // sessions
        setHttpVersion(wsRequest.getVersion().toString());
        setMethod(wsRequest.getMethod());
    }

    private final String genRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public String getKey()
    {
        return key;
    }

    /**
     * @param cookieStore the cookie store to use
     * @deprecated use either {@link WebSocketClient#setCookieStore(CookieStore)} or {@link HttpClient#setCookieStore(CookieStore)} instead
     */
    @Deprecated
    public void setCookiesFrom(CookieStore cookieStore)
    {
        throw new UnsupportedOperationException("Request specific CookieStore no longer supported");
    }

    @Override
    public void setRequestURI(URI uri)
    {
        super.setRequestURI(uri);

        // parse parameter map
        Map<String, List<String>> pmap = new HashMap<>();

        String query = uri.getRawQuery();

        if (StringUtil.isNotBlank(query))
        {
            MultiMap<String> params = new MultiMap<>();
            UrlEncoded.decodeTo(query, params, StandardCharsets.UTF_8);

            for (String key : params.keySet())
            {
                List<String> values = params.getValues(key);
                if (values == null)
                {
                    pmap.put(key, new ArrayList<>());
                }
                else
                {
                    // break link to original
                    List<String> copy = new ArrayList<>();
                    copy.addAll(values);
                    pmap.put(key, copy);
                }
            }

            super.setParameterMap(pmap);
        }
    }

    /**
     * @param timeout the total timeout for the request/response conversation of the WebSocket handshake;
     * use zero or a negative value to disable the timeout
     * @param unit the timeout unit
     */
    public void setTimeout(long timeout, TimeUnit unit)
    {
        this.timeout = unit.toMillis(timeout);
    }

    /**
     * @return the total timeout for this request, in milliseconds;
     * zero or negative if the timeout is disabled
     */
    public long getTimeout()
    {
        return timeout;
    }

    public void setLocalEndpoint(Object websocket)
    {
        this.localEndpoint = websocket;
    }

    public Object getLocalEndpoint()
    {
        return localEndpoint;
    }
}
