//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

/**
 * Exception when a message is too large for the internal buffers occurs and should trigger a connection close.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6455#section-7.4.1">RFC6455 : Section 7.4.1</a>
 */
@SuppressWarnings("serial")
public class MessageTooLargeException extends CloseException
{
    public MessageTooLargeException(String message)
    {
        super(WebSocketConstants.MESSAGE_TOO_LARGE,message);
    }

    public MessageTooLargeException(String message, Throwable t)
    {
        super(WebSocketConstants.MESSAGE_TOO_LARGE,message,t);
    }

    public MessageTooLargeException(Throwable t)
    {
        super(WebSocketConstants.MESSAGE_TOO_LARGE,t);
    }
}
