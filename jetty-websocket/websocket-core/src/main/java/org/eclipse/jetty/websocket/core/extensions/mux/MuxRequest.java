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

package org.eclipse.jetty.websocket.core.extensions.mux;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.api.UpgradeRequest;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;

public class MuxRequest implements UpgradeRequest
{
    public static final String HEADER_VALUE_DELIM="\"\\\n\r\t\f\b%+ ;=";

    public static UpgradeRequest merge(UpgradeRequest baseReq, UpgradeRequest deltaReq)
    {
        MuxRequest req = new MuxRequest(baseReq);

        req.method = overlay(deltaReq.getMethod(),req.getMethod());

        // TODO: finish

        return req;
    }

    private static String overlay(String val, String defVal)
    {
        if (val == null)
        {
            return defVal;
        }
        return val;
    }

    public static UpgradeRequest parse(ByteBuffer handshake)
    {
        MuxRequest req = new MuxRequest();
        // TODO Auto-generated method stub
        return req;
    }

    private String method;
    private String httpVersion;
    private String remoteURI;
    private String queryString;
    private List<String> subProtocols;
    private Map<String, String> cookies;
    private List<ExtensionConfig> extensions;
    private Map<String, List<String>> headers;
    private Map<String, String[]> parameterMap;

    public MuxRequest()
    {
        // TODO Auto-generated constructor stub
    }

    public MuxRequest(UpgradeRequest copy)
    {
        // TODO Auto-generated constructor stub
    }

    @Override
    public void addExtensions(String... extConfigs)
    {
        for (String extConfig : extConfigs)
        {
            extensions.add(ExtensionConfig.parse(extConfig));
        }
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
        List<String> values = headers.get(name);
        // not set
        if ((values == null) || (values.isEmpty()))
        {
            return null;
        }
        // only 1 value (most common scenario)
        if (values.size() == 1)
        {
            return values.get(0);
        }
        // merge multiple values together
        StringBuilder ret = new StringBuilder();
        boolean delim = false;
        for (String value : values)
        {
            if (delim)
            {
                ret.append(", ");
            }
            QuotedStringTokenizer.quoteIfNeeded(ret,value,HEADER_VALUE_DELIM);
            delim = true;
        }
        return ret.toString();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    @Override
    public String getHost()
    {
        return getHeader("Host");
    }

    @Override
    public String getHttpVersion()
    {
        return httpVersion;
    }

    @Override
    public String getMethod()
    {
        return method;
    }

    @Override
    public String getOrigin()
    {
        return getHeader("Origin");
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        return parameterMap;
    }

    @Override
    public String getQueryString()
    {
        return queryString;
    }

    @Override
    public String getRemoteURI()
    {
        return remoteURI;
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
