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
 * Suppressed exceptions are disabled for this implementation,
 * meaning calling {@link #addSuppressed(Throwable)} has no effect.
 * This means instances of {@link SentinelWebSocketCloseException} are suitable to be kept as static fields.
 */
public class SentinelWebSocketCloseException extends Exception
{
    public SentinelWebSocketCloseException()
    {
        this(null);
    }

    public SentinelWebSocketCloseException(String message)
    {
        super(message, null, false, true);
    }
}
