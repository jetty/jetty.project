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

/* ------------------------------------------------------------------------------- */
class BadMessage extends Error
{
    final int _code;
    final String _reason;

    BadMessage()
    {
        this(400,null);
    }
    
    BadMessage(int code)
    {
        this(code,null);
    }
    
    BadMessage(String reason)
    {
        this(400,reason);
    }
    
    BadMessage(int code,String reason)
    {
        _code=code;
        _reason=reason;
    }
    
    BadMessage(int code,String reason,Throwable cause)
    {
        super(cause);
        _code=code;
        _reason=reason;
    }
    
    public int getCode()
    {
        return _code;
    }
    
    public String getReason()
    {
        return _reason;
    }
    
    
}