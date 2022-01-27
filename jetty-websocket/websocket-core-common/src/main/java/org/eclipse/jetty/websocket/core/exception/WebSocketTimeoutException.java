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

package org.eclipse.jetty.websocket.core.exception;

/**
 * Exception thrown to indicate a connection I/O timeout.
 */
public class WebSocketTimeoutException extends WebSocketException
{
    private static final long serialVersionUID = -6145098200250676673L;

    public WebSocketTimeoutException(String message)
    {
        super(message);
    }

    public WebSocketTimeoutException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public WebSocketTimeoutException(Throwable cause)
    {
        super(cause);
    }
}
