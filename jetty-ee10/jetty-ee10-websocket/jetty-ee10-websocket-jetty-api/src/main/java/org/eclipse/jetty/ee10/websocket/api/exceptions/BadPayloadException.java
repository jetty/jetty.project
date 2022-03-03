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

package org.eclipse.jetty.ee10.websocket.api.exceptions;

import org.eclipse.jetty.ee10.websocket.api.StatusCode;

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
