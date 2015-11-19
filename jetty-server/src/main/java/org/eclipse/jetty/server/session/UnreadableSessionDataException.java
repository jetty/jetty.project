//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
    private ContextId _contextId;
    
    
    public String getId()
    {
        return _id;
    }
    
    public ContextId getContextId()
    {
        return _contextId;
    }


    public UnreadableSessionDataException (String id, ContextId contextId, Throwable t)
    {
        super ("Unreadable session "+id+" for "+contextId, t);
        _contextId = contextId;
        _id = id;
    }
    
    public UnreadableSessionDataException (String id, ContextId contextId, boolean loadAttemptsExhausted)
    {
        super("Unreadable session "+id+" for "+contextId+(loadAttemptsExhausted?" max load attempts":""));
        _contextId = contextId;
        _id = id;
    }
    

}
