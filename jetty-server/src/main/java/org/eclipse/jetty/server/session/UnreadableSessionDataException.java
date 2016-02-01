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


package org.eclipse.jetty.server.session;

/**
 * UnreadableSessionData
 *
 *
 */
public class UnreadableSessionDataException extends Exception
{
    private String _id;
    private SessionContext _sessionContext;
    
    
    public String getId()
    {
        return _id;
    }
    
    public SessionContext getSessionContext()
    {
        return _sessionContext;
    }


    public UnreadableSessionDataException (String id, SessionContext contextId, Throwable t)
    {
        super ("Unreadable session "+id+" for "+contextId, t);
        _sessionContext = contextId;
        _id = id;
    }
    
    public UnreadableSessionDataException (String id, SessionContext contextId, boolean loadAttemptsExhausted)
    {
        super("Unreadable session "+id+" for "+contextId+(loadAttemptsExhausted?" max load attempts":""));
        _sessionContext = contextId;
        _id = id;
    }
    

}
