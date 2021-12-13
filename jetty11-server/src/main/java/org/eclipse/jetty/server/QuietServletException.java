//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import jakarta.servlet.ServletException;
import org.eclipse.jetty.io.QuietException;

/**
 * A ServletException that is logged less verbosely than
 * a normal ServletException.
 * <p>
 * Used for container generated exceptions that need only a message rather
 * than a stack trace.
 * </p>
 */
public class QuietServletException extends ServletException implements QuietException
{
    public QuietServletException()
    {
        super();
    }

    public QuietServletException(String message, Throwable rootCause)
    {
        super(message, rootCause);
    }

    public QuietServletException(String message)
    {
        super(message);
    }

    public QuietServletException(Throwable rootCause)
    {
        super(rootCause);
    }
}
