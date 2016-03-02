//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

/**
 * Exception during initialization of the Endpoint
 */
public class InitException extends IllegalStateException
{
    private static final long serialVersionUID = -4691138423037387558L;

    public InitException(String s)
    {
        super(s);
    }

    public InitException(String message, Throwable cause)
    {
        super(message,cause);
    }

    public InitException(Throwable cause)
    {
        super(cause);
    }
}
