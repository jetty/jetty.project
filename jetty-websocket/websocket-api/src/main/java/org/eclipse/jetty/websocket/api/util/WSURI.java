//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Utility methods for converting a {@link URI} between a HTTP(S) and WS(S) URI.
 */
public final class WSURI
{
    /**
     * Convert to HTTP <code>http</code> or <code>https</code> scheme URIs.
     * <p>
     * Converting <code>ws</code> and <code>wss</code> URIs to their HTTP equivalent
     * 
     * @param inputUri
     *            the input URI
     * @return the HTTP scheme URI for the input URI.
     * @throws URISyntaxException
     *             if unable to convert the input URI
     */
    public static URI toHttp(final URI inputUri) throws URISyntaxException
    {
        Objects.requireNonNull(inputUri,"Input URI must not be null");
        String wsScheme = inputUri.getScheme();
        String httpScheme = null;
        if ("http".equalsIgnoreCase(wsScheme) || "https".equalsIgnoreCase(wsScheme))
        {
            // leave alone
            httpScheme = wsScheme;
        }
        else if ("ws".equalsIgnoreCase(wsScheme))
        {
            // convert to http
            httpScheme = "http";
        }
        else if ("wss".equalsIgnoreCase(wsScheme))
        {
            // convert to https
            httpScheme = "https";
        }
        else
        {
            throw new URISyntaxException(inputUri.toString(),"Unrecognized WebSocket scheme");
        }

        return new URI(httpScheme,inputUri.getUserInfo(),inputUri.getHost(),inputUri.getPort(),inputUri.getPath(),inputUri.getQuery(),inputUri.getFragment());
    }

    /**
     * Convert to WebSocket <code>ws</code> or <code>wss</code> scheme URIs
     * <p>
     * Converting <code>http</code> and <code>https</code> URIs to their WebSocket equivalent
     * 
     * @param inputUrl
     *            the input URI
     * @return the WebSocket scheme URI for the input URI.
     * @throws URISyntaxException
     *             if unable to convert the input URI
     */
    public static URI toWebsocket(CharSequence inputUrl) throws URISyntaxException
    {
        return toWebsocket(new URI(inputUrl.toString()));
    }

    /**
     * Convert to WebSocket <code>ws</code> or <code>wss</code> scheme URIs
     * <p>
     * Converting <code>http</code> and <code>https</code> URIs to their WebSocket equivalent
     * 
     * @param inputUrl
     *            the input URI
     * @param query
     *            the optional query string
     * @return the WebSocket scheme URI for the input URI.
     * @throws URISyntaxException
     *             if unable to convert the input URI
     */
    public static URI toWebsocket(CharSequence inputUrl, String query) throws URISyntaxException
    {
        if (query == null)
        {
            return toWebsocket(new URI(inputUrl.toString()));
        }
        return toWebsocket(new URI(inputUrl.toString() + '?' + query));
    }

    /**
     * Convert to WebSocket <code>ws</code> or <code>wss</code> scheme URIs
     * 
     * <p>
     * Converting <code>http</code> and <code>https</code> URIs to their WebSocket equivalent
     * 
     * @param inputUri
     *            the input URI
     * @return the WebSocket scheme URI for the input URI.
     * @throws URISyntaxException
     *             if unable to convert the input URI
     */
    public static URI toWebsocket(final URI inputUri) throws URISyntaxException
    {
        Objects.requireNonNull(inputUri,"Input URI must not be null");
        String httpScheme = inputUri.getScheme();
        String wsScheme = null;
        if ("ws".equalsIgnoreCase(httpScheme) || "wss".equalsIgnoreCase(httpScheme))
        {
            // keep as-is
            wsScheme = httpScheme;
        }
        else if ("http".equalsIgnoreCase(httpScheme))
        {
            // convert to ws
            wsScheme = "ws";
        }
        else if ("https".equalsIgnoreCase(httpScheme))
        {
            // convert to wss
            wsScheme = "wss";
        }
        else
        {
            throw new URISyntaxException(inputUri.toString(),"Unrecognized HTTP scheme");
        }
        return new URI(wsScheme,inputUri.getUserInfo(),inputUri.getHost(),inputUri.getPort(),inputUri.getPath(),inputUri.getQuery(),inputUri.getFragment());
    }
}
