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

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.SessionManager;

public abstract class LoginAuthenticator implements Authenticator
{
    public final static String SESSION_SECURED="org.eclipse.jetty.security.secured";
    protected final DeferredAuthentication _deferred=new DeferredAuthentication(this);
    protected LoginService _loginService;
    protected IdentityService _identityService;
    private boolean _renewSession;

    protected LoginAuthenticator()
    {
    }

    public void setConfiguration(AuthConfiguration configuration)
    {
        _loginService=configuration.getLoginService();
        if (_loginService==null)
            throw new IllegalStateException("No LoginService for "+this+" in "+configuration);
        _identityService=configuration.getIdentityService();
        if (_identityService==null)
            throw new IllegalStateException("No IdentityService for "+this+" in "+configuration);
        _renewSession=configuration.isSessionRenewedOnAuthentication();
    }
    
    public LoginService getLoginService()
    {
        return _loginService;
    }
    
    /* ------------------------------------------------------------ */
    /** Change the session when the request is authenticated for the first time
     * @param request
     * @param response
     * @return The new session.
     */
    protected HttpSession renewSessionOnAuthentication(HttpServletRequest request, HttpServletResponse response)
    {
        HttpSession httpSession = request.getSession(false);
        if (_renewSession && httpSession!=null && httpSession.getAttribute(SESSION_SECURED)==null)
        {
            synchronized (this)
            {
                Map<String,Object> attributes = new HashMap<String, Object>();
                for (Enumeration<String> e=httpSession.getAttributeNames();e.hasMoreElements();)
                {
                    String name=e.nextElement();
                    attributes.put(name,httpSession.getAttribute(name));
                    httpSession.removeAttribute(name);
                }
                httpSession.invalidate();
                httpSession = request.getSession(true);
                httpSession.setAttribute(SESSION_SECURED,Boolean.TRUE);
                for (Map.Entry<String, Object> entry: attributes.entrySet())
                    httpSession.setAttribute(entry.getKey(),entry.getValue());
            }
        }
        
        return httpSession;
    }
}
