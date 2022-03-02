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

package org.eclipse.jetty.websocket.api.exceptions;

import org.eclipse.jetty.websocket.api.StatusCode;

/**
 * Per spec, a protocol error should result in a Close frame of status code 1002 (PROTOCOL_ERROR)
 */
@SuppressWarnings("serial")
public class ProtocolException extends CloseException
{
    public ProtocolException(String message)
    {
        super(StatusCode.PROTOCOL, message);
    }

    public ProtocolException(String message, Throwable t)
    {
        super(StatusCode.PROTOCOL, message, t);
    }

    public ProtocolException(Throwable t)
    {
        super(StatusCode.PROTOCOL, t);
    }
}
