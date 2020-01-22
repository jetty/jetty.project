//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.jetty.websocket.core.exception.WebSocketException;

public class FunctionCallException extends WebSocketException
{
    public FunctionCallException(String message)
    {
        super(message);
    }

    public FunctionCallException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public FunctionCallException(Throwable cause)
    {
        super(cause);
    }

    public Throwable getInvokedCause()
    {
        Throwable cause = getCause();
        if (cause instanceof InvocationTargetException)
        {
            return cause.getCause();
        }
        return cause;
    }
}
