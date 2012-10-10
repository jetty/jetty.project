//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.UpgradeRequest;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;

/**
 * Allowing a generate from a UpgradeRequest
 */
public class ClientUpgradeRequest implements UpgradeRequest
{
    private final static Logger LOG = Log.getLogger(ClientUpgradeRequest.class);
    private static final String HEADER_VALUES_DELIM = "\"\\\n\r\t\f\b%+ ;=";
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
    }

    private final String key;
    private List<String> subProtocols;
    private List<ExtensionConfig> extensions;
    private Map<String, String> cookies;
    private Map<String, String> headers;
    private String httpEndPointName;
    private String host;

    public ClientUpgradeRequest()
    {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        this.key = new String(B64Code.encode(bytes));
        this.subProtocols = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.cookies = new HashMap<>();
        this.headers = new HashMap<>();
    }

    @Override
    public void addExtensions(String... extConfigs)
    {
        for (String extConfig : extConfigs)
        {
            extensions.add(ExtensionConfig.parse(extConfig));
        }
    }

    public String generate(URI uri)
    {
        this.httpEndPointName = uri.toASCIIString();
        this.host = uri.getHost();

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

        request.append("Host: ").append(this.host);
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
        if (!getCookieMap().isEmpty())
        {
            request.append("Cookie: ");
            boolean needDelim = false;
            for (String cookie : getCookieMap().keySet())
            {
                if (needDelim)
                {
                    request.append("; ");
                }
                request.append(QuotedStringTokenizer.quoteIfNeeded(cookie,HEADER_VALUES_DELIM));
                request.append("=");
                String val = cookies.get(cookie);
                request.append(QuotedStringTokenizer.quoteIfNeeded(val,HEADER_VALUES_DELIM));
                needDelim = true;
            }
            request.append("\r\n");
        }

        // Other headers
        for (String key : headers.keySet())
        {
            String value = headers.get(key);
            if (FORBIDDEN_HEADERS.contains(key.toLowerCase()))
            {
                LOG.warn("Skipping forbidden header - {}: {}",key,value);
                continue; // skip
            }
            request.append(key).append(": ");
            request.append(QuotedStringTokenizer.quoteIfNeeded(value,HEADER_VALUES_DELIM));
            request.append("\r\n");
        }

        // request header end
        request.append("\r\n");
        return request.toString();
    }

    @Override
    public Map<String, String> getCookieMap()
    {
        return cookies;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public String getHeader(String name)
    {
        return headers.get(name);
    }

    @Override
    public String getHost()
    {
        return this.host;
    }

    @Override
    public String getHttpEndPointName()
    {
        return httpEndPointName;
    }

    public String getKey()
    {
        return key;
    }

    @Override
    public String getOrigin()
    {
        return getHeader("Origin");
    }

    @Override
    public List<String> getSubProtocols()
    {
        return subProtocols;
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        for (String protocol : subProtocols)
        {
            if (protocol.equalsIgnoreCase(test))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isOrigin(String test)
    {
        return test.equalsIgnoreCase(getOrigin());
    }

    @Override
    public void setSubProtocols(String protocols)
    {
        this.subProtocols.clear();
        if (StringUtil.isBlank(protocols))
        {
            return;
        }
        for (String protocol : protocols.split("\\s*,\\s*"))
        {
            this.subProtocols.add(protocol);
        }
    }
}
