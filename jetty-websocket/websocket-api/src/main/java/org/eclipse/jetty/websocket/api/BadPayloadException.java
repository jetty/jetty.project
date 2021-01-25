//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * Exception to terminate the connection because it has received data within a frame payload that was not consistent with the requirements of that frame
 * payload. (eg: not UTF-8 in a text frame, or a unexpected data seen by an extension)
 *
 * @see StatusCode#BAD_PAYLOAD
 */
@SuppressWarnings("serial")
public class BadPayloadException extends CloseException
{
    public BadPayloadException(String message)
    {
        super(StatusCode.BAD_PAYLOAD, message);
    }

    public BadPayloadException(String message, Throwable t)
    {
        super(StatusCode.BAD_PAYLOAD, message, t);
    }

    public BadPayloadException(Throwable t)
    {
        super(StatusCode.BAD_PAYLOAD, t);
    }
}
