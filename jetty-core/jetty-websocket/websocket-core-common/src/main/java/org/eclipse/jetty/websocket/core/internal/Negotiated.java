//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;

public class Negotiated
{
    private final URI requestURI;
    private final Map<String, List<String>> parameterMap;
    private final String subProtocol;
    private final boolean secure;
    private final ExtensionStack extensions;
    private final String protocolVersion;

    public Negotiated(URI requestURI, String subProtocol, boolean secure,
                      ExtensionStack extensions, String protocolVersion)
    {
        this.requestURI = toWebsocket(requestURI);
        this.subProtocol = subProtocol;
        this.secure = secure;
        this.extensions = extensions;
        this.protocolVersion = protocolVersion;

        String rawQuery = requestURI.getRawQuery();
        Map<String, List<String>> map;
        if (StringUtil.isBlank(rawQuery))
        {
            map = Collections.emptyMap();
        }
        else
        {
            map = new HashMap<>();
            MultiMap<String> params = new MultiMap<>();
            UrlEncoded.decodeUtf8To(rawQuery, params);
            for (String p : params.keySet())
            {
                map.put(p, Collections.unmodifiableList(params.getValues(p)));
            }
        }
        this.parameterMap = Collections.unmodifiableMap(map);
    }

    public URI getRequestURI()
    {
        return requestURI;
    }

    public Map<String, List<String>> getParameterMap()
    {
        return parameterMap;
    }

    public String getSubProtocol()
    {
        return subProtocol;
    }

    public boolean isSecure()
    {
        return secure;
    }

    public ExtensionStack getExtensions()
    {
        return extensions;
    }

    public String getProtocolVersion()
    {
        return protocolVersion;
    }

    @Override
    public String toString()
    {
        return String.format("[%s,%s,%b.%s]",
            requestURI,
            subProtocol,
            secure,
            extensions.getNegotiatedExtensions().stream().map(ExtensionConfig::getName).collect(Collectors.toList()));
    }

    public static Negotiated from(ExtensionStack extensions)
    {
        try
        {
            return new Negotiated(new URI("/"), "", false, extensions, WebSocketConstants.SPEC_VERSION_STRING);
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert to WebSocket {@code ws} or {@code wss} scheme URIs
     *
     * <p>
     * Converting {@code http} and {@code https} URIs to their WebSocket equivalent
     *
     * @param uri the input URI
     * @return the WebSocket scheme URI for the input URI.
     */
    public static URI toWebsocket(final URI uri)
    {
        try
        {
            Objects.requireNonNull(uri, "Input URI must not be null");
            String httpScheme = uri.getScheme();
            if (httpScheme == null)
                return uri;
            if (HttpScheme.WS.is(httpScheme) || HttpScheme.WSS.is(httpScheme))
                return uri;

            String afterScheme = uri.toString().substring(httpScheme.length());
            if (HttpScheme.HTTP.is(httpScheme))
                return new URI("ws" + afterScheme);
            if (HttpScheme.HTTPS.is(httpScheme))
                return new URI("wss" + afterScheme);

            throw new URISyntaxException(uri.toString(), "Unrecognized HTTP scheme");
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
}
