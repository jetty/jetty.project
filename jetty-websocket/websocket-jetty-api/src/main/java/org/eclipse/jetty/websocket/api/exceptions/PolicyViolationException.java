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
 * Exception when a violation of policy occurs and should trigger a connection close.
 *
 * @see StatusCode#POLICY_VIOLATION
 */
@SuppressWarnings("serial")
public class PolicyViolationException extends CloseException
{
    public PolicyViolationException(String message)
    {
        super(StatusCode.POLICY_VIOLATION, message);
    }

    public PolicyViolationException(String message, Throwable t)
    {
        super(StatusCode.POLICY_VIOLATION, message, t);
    }

    public PolicyViolationException(Throwable t)
    {
        super(StatusCode.POLICY_VIOLATION, t);
    }
}
