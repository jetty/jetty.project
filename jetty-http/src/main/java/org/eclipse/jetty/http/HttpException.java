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

package org.eclipse.jetty.http;

import java.io.IOException;

public class HttpException extends IOException
{
    int _status;
    String _reason;

    /* ------------------------------------------------------------ */
    public HttpException(int status)
    {
        _status=status;
        _reason=null;
    }

    /* ------------------------------------------------------------ */
    public HttpException(int status,String reason)
    {
        _status=status;
        _reason=reason;
    }

    /* ------------------------------------------------------------ */
    public HttpException(int status,String reason, Throwable rootCause)
    {
        _status=status;
        _reason=reason;
        initCause(rootCause);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the reason.
     */
    public String getReason()
    {
        return _reason;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param reason The reason to set.
     */
    public void setReason(String reason)
    {
        _reason = reason;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the status.
     */
    public int getStatus()
    {
        return _status;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param status The status to set.
     */
    public void setStatus(int status)
    {
        _status = status;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return ("HttpException("+_status+","+_reason+","+super.getCause()+")");
    }
    
    
}
