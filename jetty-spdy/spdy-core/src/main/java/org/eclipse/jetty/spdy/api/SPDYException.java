//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.api;

/**
 * <p>An unrecoverable exception that signals to the application that
 * something wrong happened.</p>
 */
public class SPDYException extends RuntimeException
{
    public SPDYException()
    {
    }

    public SPDYException(String message)
    {
        super(message);
    }

    public SPDYException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public SPDYException(Throwable cause)
    {
        super(cause);
    }

    public SPDYException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace)
    {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
