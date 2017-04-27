//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
     * Convert to HTTP {@code http} or {@code https} scheme URIs.
     * <p>
     * Converting {@code ws} and {@code wss} URIs to their HTTP equivalent
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
        String httpScheme;
        //noinspection UnusedAssignment
        int port = -1;
        if ("http".equalsIgnoreCase(wsScheme))
        {
            // leave alone
            httpScheme = wsScheme;
        }
        else if ("https".equalsIgnoreCase(wsScheme))
        {
            // leave alone
            httpScheme = wsScheme;
        }
        
        if ("ws".equalsIgnoreCase(wsScheme))
        {
            // convert to http
            httpScheme = "http";
        }
        
        if ("wss".equalsIgnoreCase(wsScheme))
        {
            // convert to https
            httpScheme = "https";
        }
        else
        {
            throw new URISyntaxException(inputUri.toString(),"Unrecognized WebSocket scheme");
        }
    
        if (inputUri.getPort() > 0)
        {
            port = inputUri.getPort();
        }

        return new URI(httpScheme,inputUri.getUserInfo(),inputUri.getHost(),port,inputUri.getPath(),inputUri.getQuery(),inputUri.getFragment());
    }

    /**
     * Convert to WebSocket {@code ws} or {@code wss} scheme URIs
     * <p>
     * Converting {@code http} and {@code https} URIs to their WebSocket equivalent
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
     * Convert to WebSocket {@code ws} or {@code wss} scheme URIs
     * <p>
     * Converting {@code http} and {@code https} URIs to their WebSocket equivalent
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
     * Convert to WebSocket {@code ws} or {@code wss} scheme URIs
     * 
     * <p>
     * Converting {@code http} and {@code https} URIs to their WebSocket equivalent
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
        String wsScheme;
        //noinspection UnusedAssignment
        int port = -1;
        if ("ws".equalsIgnoreCase(httpScheme))
        {
            // keep as-is
            wsScheme = httpScheme;
        }
        else if ("wss".equalsIgnoreCase(httpScheme))
        {
            // keep as-is
            wsScheme = httpScheme;
        }
        
        if ("http".equalsIgnoreCase(httpScheme))
        {
            // convert to ws
            wsScheme = "ws";
        }
        
        if ("https".equalsIgnoreCase(httpScheme))
        {
            // convert to wss
            wsScheme = "wss";
        }
        else
        {
            throw new URISyntaxException(inputUri.toString(),"Unrecognized HTTP scheme");
        }
        
        if (inputUri.getPort() > 0)
        {
            port = inputUri.getPort();
        }
        return new URI(wsScheme,inputUri.getUserInfo(),inputUri.getHost(),port,inputUri.getPath(),inputUri.getQuery(),inputUri.getFragment());
    }
}
