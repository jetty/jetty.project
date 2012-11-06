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

package org.eclipse.jetty.websocket.server;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

public class ServletWebSocketRequest extends UpgradeRequest
{
    private List<ExtensionConfig> extensions;
    private Map<String, String> cookieMap;

    public ServletWebSocketRequest(HttpServletRequest request)
    {
        super(request.getRequestURI());
        // TODO: copy values over

        cookieMap = new HashMap<String, String>();
        for (Cookie cookie : request.getCookies())
        {
            cookieMap.put(cookie.getName(),cookie.getValue());
        }

        Enumeration<String> protocols = request.getHeaders("Sec-WebSocket-Protocol");
        List<String> subProtocols = new ArrayList<>();
        String protocol = null;
        while ((protocol == null) && (protocols != null) && protocols.hasMoreElements())
        {
            String candidate = protocols.nextElement();
            for (String p : parseProtocols(candidate))
            {
                subProtocols.add(p);
            }
        }
        setSubProtocols(subProtocols);

        extensions = new ArrayList<>();
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        while (e.hasMoreElements())
        {
            QuotedStringTokenizer tok = new QuotedStringTokenizer(e.nextElement(),",");
            while (tok.hasMoreTokens())
            {
                addExtensions(tok.nextToken());
            }
        }
    }

    @Override
    public void addExtensions(String... extConfigs)
    {
        for (String extConfig : extConfigs)
        {
            extensions.add(ExtensionConfig.parse(extConfig));
        }
    }

    public Map<String, String> getCookieMap()
    {
        return cookieMap;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]
            { null };
        }
        protocol = protocol.trim();
        if ((protocol == null) || (protocol.length() == 0))
        {
            return new String[]
            { null };
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public void setValidExtensions(List<Extension> valid)
    {
        if (this.extensions != null)
        {
            this.extensions.clear();
        }
        else
        {
            this.extensions = new ArrayList<>();
        }

        for (Extension ext : valid)
        {
            extensions.add(ext.getConfig());
        }
    }
}
