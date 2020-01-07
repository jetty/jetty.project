//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import javax.servlet.ServletException;

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
