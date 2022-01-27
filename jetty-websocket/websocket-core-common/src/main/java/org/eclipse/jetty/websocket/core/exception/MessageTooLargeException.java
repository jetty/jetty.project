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

import org.eclipse.jetty.websocket.core.CloseStatus;

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
        super(CloseStatus.MESSAGE_TOO_LARGE, message);
    }

    public MessageTooLargeException(String message, Throwable t)
    {
        super(CloseStatus.MESSAGE_TOO_LARGE, message, t);
    }

    public MessageTooLargeException(Throwable t)
    {
        super(CloseStatus.MESSAGE_TOO_LARGE, t);
    }
}
