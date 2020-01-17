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

package org.eclipse.jetty.server.session;

/**
 * UnreadableSessionDataException
 */
public class UnreadableSessionDataException extends Exception
{
    /**
     *
     */
    private static final long serialVersionUID = 1806303483488966566L;
    private String _id;
    private SessionContext _sessionContext;

    /**
     * @return the session id
     */
    public String getId()
    {
        return _id;
    }

    /**
     * @return the SessionContext to which the unreadable session belongs
     */
    public SessionContext getSessionContext()
    {
        return _sessionContext;
    }

    /**
     * @param id the session id
     * @param sessionContext the sessionContext
     * @param t the cause of the exception
     */
    public UnreadableSessionDataException(String id, SessionContext sessionContext, Throwable t)
    {
        super("Unreadable session " + id + " for " + sessionContext, t);
        _sessionContext = sessionContext;
        _id = id;
    }
}
