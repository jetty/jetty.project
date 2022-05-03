//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

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
