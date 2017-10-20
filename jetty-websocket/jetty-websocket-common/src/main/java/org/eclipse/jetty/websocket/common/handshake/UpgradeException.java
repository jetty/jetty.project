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

package org.eclipse.jetty.websocket.common.handshake;

import java.net.URI;

import org.eclipse.jetty.websocket.core.WebSocketException;

/**
 * Exception during WebSocket Upgrade Handshake.
 */
@SuppressWarnings("serial")
public class UpgradeException extends WebSocketException
{
    private final URI requestURI;
    private final int responseStatusCode;

    public UpgradeException(URI requestURI, int responseStatusCode, String message)
    {
        super(message);
        this.requestURI = requestURI;
        this.responseStatusCode = responseStatusCode;
    }

    public UpgradeException(URI requestURI, int responseStatusCode, String message, Throwable cause)
    {
        super(message,cause);
        this.requestURI = requestURI;
        this.responseStatusCode = responseStatusCode;
    }

    public UpgradeException(URI requestURI, Throwable cause)
    {
        super(cause);
        this.requestURI = requestURI;
        this.responseStatusCode = -1;
    }

    public URI getRequestURI()
    {
        return requestURI;
    }

    public int getResponseStatusCode()
    {
        return responseStatusCode;
    }
}
