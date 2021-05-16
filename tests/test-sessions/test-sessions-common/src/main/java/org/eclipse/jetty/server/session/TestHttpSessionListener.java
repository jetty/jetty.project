//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 */
public class TestHttpSessionListener implements HttpSessionListener
{
    public List<String> createdSessions = new ArrayList<>();
    public List<String> destroyedSessions = new ArrayList<>();
    public boolean accessAttribute = false;
    public boolean lastAccessTime = false;
    public Exception attributeException = null;
    public Exception accessTimeException = null;

    public TestHttpSessionListener(boolean accessAttribute, boolean lastAccessTime)
    {
        this.accessAttribute = accessAttribute;
        this.lastAccessTime = lastAccessTime;
    }

    public TestHttpSessionListener()
    {
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
                attributeException = e;
            }
        }
        
        if (lastAccessTime)
        {
            try
            {
                se.getSession().getLastAccessedTime();
            }
            catch (Exception e)
            {
                accessTimeException = e;
            }
        }
    }

    public void sessionCreated(HttpSessionEvent se)
    {
        createdSessions.add(se.getSession().getId());
    }
}
