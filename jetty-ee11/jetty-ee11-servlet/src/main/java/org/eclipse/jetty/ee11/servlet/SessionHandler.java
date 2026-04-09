//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlet;

import java.util.Enumeration;
import java.util.Iterator;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.ManagedSession;

public class SessionHandler extends org.eclipse.jetty.ee.servlet.SessionHandler
{
    public Session.API newSessionAPIWrapper(ManagedSession session)
    {
        return ServletSessionApi.wrapSession(session);
    }

    public static class ServletSessionApi implements HttpSession, Session.API
    {
        public static ServletSessionApi wrapSession(ManagedSession session)
        {
            return new ServletSessionApi(session);
        }

        public static ManagedSession getSession(HttpSession httpSession)
        {
            if (httpSession instanceof org.eclipse.jetty.ee.servlet.SessionHandler.ServletSessionApi apiSession)
                return apiSession.getSession();
            return null;
        }

        private final ManagedSession _session;

        private ServletSessionApi(ManagedSession session)
        {
            _session = session;
        }

        @Override
        public ManagedSession getSession()
        {
            return _session;
        }

        @Override
        public long getCreationTime()
        {
            return _session.getCreationTime();
        }

        @Override
        public String getId()
        {
            return _session.getId();
        }

        @Override
        public long getLastAccessedTime()
        {
            return _session.getLastAccessedTime();
        }

        @Override
        public ServletContext getServletContext()
        {
            return ServletContextHandler.getServletContext(_session.getSessionManager().getContext());
        }

        @Override
        public void setMaxInactiveInterval(int interval)
        {
            _session.setMaxInactiveInterval(interval);
        }

        @Override
        public int getMaxInactiveInterval()
        {
            return _session.getMaxInactiveInterval();
        }

        @Override
        public Object getAttribute(String name)
        {
            return _session.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            final Iterator<String> itor = _session.getAttributeNameSet().iterator();
            return new Enumeration<>()
            {

                @Override
                public boolean hasMoreElements()
                {
                    return itor.hasNext();
                }

                @Override
                public String nextElement()
                {
                    return itor.next();
                }
            };
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            _session.setAttribute(name, value);
        }

        @Override
        public void removeAttribute(String name)
        {
            _session.removeAttribute(name);
        }

        @Override
        public void invalidate()
        {
            _session.invalidate();
        }

        @Override
        public boolean isNew()
        {
            return _session.isNew();
        }
    }
}
