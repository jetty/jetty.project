//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
