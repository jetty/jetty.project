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
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

/**
 * Allowing a generate from a UpgradeRequest
 */
public class ClientUpgradeRequest implements UpgradeRequest
{
    public static final String COOKIE_DELIM = "\"\\\n\r\t\f\b%+ ;=";
    private final String key;

    public ClientUpgradeRequest()
    {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        this.key = new String(B64Code.encode(bytes));
    }

    public String generate(URI uri)
    {
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
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");

        if (StringUtil.isNotBlank(getOrigin()))
        {
            request.append("Origin: ").append(getOrigin()).append("\r\n");
        }

        request.append("Sec-WebSocket-Version: 13\r\n"); // RFC-6455 specified version

        Map<String, String> cookies = getCookieMap();
        if ((cookies != null) && (cookies.size() > 0))
        {
            for (String cookie : cookies.keySet())
            {
                request.append("Cookie: ");
                request.append(QuotedStringTokenizer.quoteIfNeeded(cookie,COOKIE_DELIM));
                request.append("=");
                request.append(QuotedStringTokenizer.quoteIfNeeded(cookies.get(cookie),COOKIE_DELIM));
                request.append("\r\n");
            }
        }

        request.append("\r\n");
        return request.toString();
    }

    @Override
    public Map<String, String> getCookieMap()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getHeader(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getHost()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getHttpEndPointName()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public String getKey()
    {
        return key;
    }

    @Override
    public String getOrigin()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<String> getSubProtocols()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isOrigin(String test)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void setSubProtocols(String string)
    {
        // TODO Auto-generated method stub

    }

}
