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

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.SessionStatus;

public class SessionException extends RuntimeException
{
    private final SessionStatus sessionStatus;

    public SessionException(SessionStatus sessionStatus)
    {
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, String message)
    {
        super(message);
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, Throwable cause)
    {
        super(cause);
        this.sessionStatus = sessionStatus;
    }

    public SessionStatus getSessionStatus()
    {
        return sessionStatus;
    }
}
