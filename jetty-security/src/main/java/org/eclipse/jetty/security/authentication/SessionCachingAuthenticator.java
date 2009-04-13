// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.DefaultAuthentication;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.Authentication.Status;
import org.eclipse.jetty.server.UserIdentity;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class SessionCachingAuthenticator extends DelegateAuthenticator
{
    public final static String __J_AUTHENTICATED = "org.eclipse.jetty.server.Auth";

    public SessionCachingAuthenticator(Authenticator delegate)
    {
        super(delegate);
    }

    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        HttpSession session = ((HttpServletRequest)request).getSession(mandatory);
        // not mandatory and not authenticated
        if (session == null) 
            return DefaultAuthentication.SUCCESS_UNAUTH_RESULTS;

        Authentication authentication = (Authentication) session.getAttribute(__J_AUTHENTICATED);
        if (authentication != null) 
            return authentication;

        authentication = _delegate.validateRequest(request, response, mandatory);
        if (authentication != null && authentication.getUserIdentity().getSubject() != null)
        {
            Authentication next=new FormAuthentication(Authentication.Status.SUCCESS,_delegate,authentication.getUserIdentity());
            session.setAttribute(__J_AUTHENTICATED, next);
        }
        return authentication;
    }
    
    protected class FormAuthentication extends DefaultAuthentication implements HttpSessionAttributeListener
    {
        public FormAuthentication(Status authStatus, Authenticator authenticator, UserIdentity userIdentity)
        {
            super(authStatus,authenticator,userIdentity);
        }

        public void attributeAdded(HttpSessionBindingEvent event)
        {
        }

        public void attributeRemoved(HttpSessionBindingEvent event)
        {
            logout();
        }
        
        public void attributeReplaced(HttpSessionBindingEvent arg0)
        {
            logout();
        }
        
    }
}
