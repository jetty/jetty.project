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

package org.eclipse.jetty.ee10.servlet;

import java.util.Set;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHandler extends Handler.Wrapper
{    
    static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        return null;
    }

    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        return null;
    }

    public String getExtendedId(HttpSession session)
    {
        return null;
    }

    public int getMaxInactiveInterval()
    {
        return 0;
    }

    public SessionCookieConfig getSessionCookieConfig()
    {
        return null;
    }

    public String getSessionIdPathParameterNamePrefix()
    {
        return null;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        LOG.warn("Session are not implemented.");
        return super.handle(request);
    }

    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return false;
    }

    public boolean isUsingCookies()
    {
        return false;
    }

    public boolean isUsingURLs()
    {
        return false;
    }

    public boolean isValid(HttpSession session)
    {
        return false;
    }

    public void setMaxInactiveInterval(int tmp)
    {

    }

    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {

    }
}
