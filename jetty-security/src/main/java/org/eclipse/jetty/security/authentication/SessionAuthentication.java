// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.eclipse.jetty.security.authentication;

import java.io.Serializable;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.UserIdentity;

class SessionAuthentication extends UserAuthentication implements HttpSessionAttributeListener, Serializable
{
    private static final long serialVersionUID = -4643200685888258706L;

    public final static String __J_AUTHENTICATED="org.eclipse.jetty.security.UserIdentity";
    
    HttpSession _session;
    
    public SessionAuthentication(HttpSession session,Authenticator authenticator, UserIdentity userIdentity)
    {
        super(authenticator,userIdentity);
        _session=session;
    }

    public void attributeAdded(HttpSessionBindingEvent event)
    {
    }

    public void attributeRemoved(HttpSessionBindingEvent event)
    {
        super.logout();
    }
    
    public void attributeReplaced(HttpSessionBindingEvent event)
    {
        if (event.getValue()==null)
            super.logout();
    }

    public void logout() 
    {    
        _session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
    }
    
    public String toString()
    {
        return "Session"+super.toString();
    }
    
}