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

package org.eclipse.jetty.websocket.api;

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
        super(message,cause);
    }

    public WebSocketTimeoutException(Throwable cause)
    {
        super(cause);
    }
}
