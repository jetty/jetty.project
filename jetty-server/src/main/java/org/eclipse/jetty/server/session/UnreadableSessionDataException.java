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
