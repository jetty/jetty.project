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

package org.eclipse.jetty.io;

import java.io.EOFException;

/**
 * A Jetty specialization of EOFException.
 * <p> This is thrown by Jetty to distinguish between EOF received from
 * the connection, vs and EOF thrown by some application talking to some other file/socket etc.
 * The only difference in handling is that Jetty EOFs are logged less verbosely.
 */
public class EofException extends EOFException implements QuietException
{
    public EofException()
    {
    }

    public EofException(String reason)
    {
        super(reason);
    }

    public EofException(Throwable th)
    {
        if (th != null)
            initCause(th);
    }
}
