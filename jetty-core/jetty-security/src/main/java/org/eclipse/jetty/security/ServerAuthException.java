//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security;

import java.security.GeneralSecurityException;

/**
 * A server specific Authentication or Authorization exception.
 */
public class ServerAuthException extends GeneralSecurityException
{

    /**
     * Creates a server authentication exception with no message.
     */
    public ServerAuthException()
    {
    }

    /**
     * Creates a server authentication exception with the given message.
     *
     * @param s the exception message
     */
    public ServerAuthException(String s)
    {
        super(s);
    }

    /**
     * Creates a server authentication exception with the given message and cause.
     *
     * @param s the exception message
     * @param throwable the underlying cause
     */
    public ServerAuthException(String s, Throwable throwable)
    {
        super(s, throwable);
    }

    /**
     * Creates a server authentication exception with the given cause.
     *
     * @param throwable the underlying cause
     */
    public ServerAuthException(Throwable throwable)
    {
        super(throwable);
    }
}
