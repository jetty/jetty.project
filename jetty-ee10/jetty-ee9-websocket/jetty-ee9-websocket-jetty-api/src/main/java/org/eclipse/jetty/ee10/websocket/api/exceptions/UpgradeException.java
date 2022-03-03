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

package org.eclipse.jetty.ee9.websocket.api.exceptions;

import java.net.URI;

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
        super(message, cause);
        this.requestURI = requestURI;
        this.responseStatusCode = responseStatusCode;
    }

    public UpgradeException(URI requestURI, Throwable cause)
    {
        this(requestURI, -1, cause);
    }

    public UpgradeException(URI requestURI, int responseStatusCode, Throwable cause)
    {
        super(cause);
        this.requestURI = requestURI;
        this.responseStatusCode = responseStatusCode;
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
