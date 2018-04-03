//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

/**
 * TestSessionListener
 *
 *
 */
public class TestHttpSessionListener implements HttpSessionListener
{
    public List<String> createdSessions = new ArrayList<>();
    public List<String> destroyedSessions = new ArrayList<>();
    public boolean accessAttribute = false;
    public Exception ex = null;

    public TestHttpSessionListener(boolean access)
    {
        accessAttribute = access;
    }

    public TestHttpSessionListener()
    {
        accessAttribute = false;
    }

    public void sessionDestroyed(HttpSessionEvent se)
    {
        destroyedSessions.add(se.getSession().getId());
        if (accessAttribute)
        {
            try
            {
                se.getSession().getAttribute("anything");
            }
            catch (Exception e)
            {
                ex = e;
            }
        }
    }

    public void sessionCreated(HttpSessionEvent se)
    {
        createdSessions.add(se.getSession().getId());
    }

}
