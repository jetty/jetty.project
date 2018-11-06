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


package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.security.AbstractUserAuthentication;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SessionAuthentication extends AbstractUserAuthentication implements Serializable, HttpSessionActivationListener, HttpSessionBindingListener
{
    private static final Logger LOG = Log.getLogger(SessionAuthentication.class);

    private static final long serialVersionUID = -4643200685888258706L;



    public final static String __J_AUTHENTICATED="org.eclipse.jetty.security.UserIdentity";

    private final String _name;
    private final Object _credentials;
    private transient HttpSession _session;

    public SessionAuthentication(String method, UserIdentity userIdentity, Object credentials)
    {
        super(method, userIdentity);
        _name=userIdentity.getUserPrincipal().getName();
        _credentials=credentials;
    }


    @Override
    public UserIdentity getUserIdentity()
    {
        if (_userIdentity == null)
            throw new IllegalStateException("!UserIdentity");
        return super.getUserIdentity();
    }


    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        stream.defaultReadObject();

        SecurityHandler security=SecurityHandler.getCurrentSecurityHandler();
        if (security==null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("!SecurityHandler");
            return;
        }

        LoginService login_service=security.getLoginService();
        if (login_service==null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("!LoginService");
            return;
        }

        _userIdentity=login_service.login(_name,_credentials, null);
        LOG.debug("Deserialized and relogged in {}",this);
    }

    @Override
    public void logout()
    {
        if (_session!=null && _session.getAttribute(__J_AUTHENTICATED)!=null)
            _session.removeAttribute(__J_AUTHENTICATED);

        doLogout();
    }

    private void doLogout()
    {
        SecurityHandler security=SecurityHandler.getCurrentSecurityHandler();
        if (security!=null)
            security.logout(this);
        if (_session!=null)
            _session.removeAttribute(Session.SESSION_CREATED_SECURE);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s}",this.getClass().getSimpleName(),hashCode(),_session==null?"-":_session.getId(),_userIdentity);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
       
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        if (_session==null)
        {
            _session=se.getSession();
        }
    }

    @Override
    public void valueBound(HttpSessionBindingEvent event)
    {
        if (_session==null)
        {
            _session=event.getSession();
        }
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event)
    {
        doLogout();
    }

}
