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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Subclass of {@link java.lang.RuntimeException} used to signal that there
 * was an {@link java.io.IOException} thrown by underlying {@link java.io.Writer}
 * @deprecated use {@link UncheckedIOException}
 */
@Deprecated(forRemoval = true, since = "12.1.0")
public class RuntimeIOException extends UncheckedIOException
{
    public RuntimeIOException()
    {
        this(new IOException());
    }

    public RuntimeIOException(String message)
    {
        this(new IOException(message));
    }

    public RuntimeIOException(Throwable cause)
    {
        this(null, cause);
    }

    public RuntimeIOException(String message, Throwable cause)
    {
        super(message, cause instanceof IOException ioe ? ioe : new IOException(cause));
    }
}
