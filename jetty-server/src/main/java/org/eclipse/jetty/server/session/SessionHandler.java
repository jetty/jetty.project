//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.EnumSet;
import java.util.EventListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * SessionHandler.
 */
public class SessionHandler extends ScopedHandler
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    public final static EnumSet<SessionTrackingMode> DEFAULT_TRACKING = EnumSet.of(SessionTrackingMode.COOKIE,SessionTrackingMode.URL);

    /* -------------------------------------------------------------- */
    private SessionManager _sessionManager;

    /* ------------------------------------------------------------ */
    /**
     * Constructor. Construct a SessionHandler witha a HashSessionManager with a standard java.util.Random generator is created.
     */
    public SessionHandler()
    {
        this(new HashSessionManager());
    }

    /* ------------------------------------------------------------ */
    /**
     * @param manager
     *            The session manager
     */
    public SessionHandler(SessionManager manager)
    {
        setSessionManager(manager);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the sessionManager.
     */
    public SessionManager getSessionManager()
    {
        return _sessionManager;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sessionManager
     *            The sessionManager to set.
     */
    public void setSessionManager(SessionManager sessionManager)
    {
        if (isStarted())
            throw new IllegalStateException();
        SessionManager old_session_manager = _sessionManager;

        if (getServer() != null)
            getServer().getContainer().update(this,old_session_manager,sessionManager,"sessionManager",true);

        if (sessionManager != null)
            sessionManager.setSessionHandler(this);

        _sessionManager = sessionManager;

        if (old_session_manager != null)
            old_session_manager.setSessionHandler(null);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        Server old_server = getServer();
        if (old_server != null && old_server != server)
            old_server.getContainer().update(this,_sessionManager,null,"sessionManager",true);
        super.setServer(server);
        if (server != null && server != old_server)
            server.getContainer().update(this,null,_sessionManager,"sessionManager",true);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _sessionManager.start();
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        // Destroy sessions before destroying servlets/filters see JETTY-1266
        _sessionManager.stop();
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        SessionManager old_session_manager = null;
        HttpSession old_session = null;
        HttpSession access = null;
        try
        {
            old_session_manager = baseRequest.getSessionManager();
            old_session = baseRequest.getSession(false);

            if (old_session_manager != _sessionManager)
            {
                // new session context
                baseRequest.setSessionManager(_sessionManager);
                baseRequest.setSession(null);
                checkRequestedSessionId(baseRequest,request);
            }

            // access any existing session
            HttpSession session = null;
            if (_sessionManager != null)
            {
                session = baseRequest.getSession(false);
                if (session != null)
                {
                    if (session != old_session)
                    {
                        access = session;
                        HttpCookie cookie = _sessionManager.access(session,request.isSecure());
                        if (cookie != null) // Handle changed ID or max-age refresh
                            baseRequest.getResponse().addCookie(cookie);
                    }
                }
                else
                {
                    session = baseRequest.recoverNewSession(_sessionManager);
                    if (session != null)
                        baseRequest.setSession(session);
                }
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("sessionManager=" + _sessionManager);
                LOG.debug("session=" + session);
            }

            // start manual inline of nextScope(target,baseRequest,request,response);
            if (_nextScope != null)
                _nextScope.doScope(target,baseRequest,request,response);
            else if (_outerScope != null)
                _outerScope.doHandle(target,baseRequest,request,response);
            else
                doHandle(target,baseRequest,request,response);
            // end manual inline (pathentic attempt to reduce stack depth)

        }
        finally
        {
            if (access != null)
                _sessionManager.complete(access);

            HttpSession session = baseRequest.getSession(false);
            if (session != null && old_session == null && session != access)
                _sessionManager.complete(session);

            if (old_session_manager != null && old_session_manager != _sessionManager)
            {
                baseRequest.setSessionManager(old_session_manager);
                baseRequest.setSession(old_session);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // start manual inline of nextHandle(target,baseRequest,request,response);
        if (never())
            nextHandle(target,baseRequest,request,response);
        else if (_nextScope != null && _nextScope == _handler)
            _nextScope.doHandle(target,baseRequest,request,response);
        else if (_handler != null)
            _handler.handle(target,baseRequest,request,response);
        // end manual inline
    }

    /* ------------------------------------------------------------ */
    /**
     * Look for a requested session ID in cookies and URI parameters
     *
     * @param baseRequest
     * @param request
     */
    protected void checkRequestedSessionId(Request baseRequest, HttpServletRequest request)
    {
        String requested_session_id = request.getRequestedSessionId();

        SessionManager sessionManager = getSessionManager();

        if (requested_session_id != null && sessionManager != null)
        {
            HttpSession session = sessionManager.getHttpSession(requested_session_id);
            if (session != null && sessionManager.isValid(session))
                baseRequest.setSession(session);
            return;
        }
        else if (!DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
            return;

        boolean requested_session_id_from_cookie = false;
        HttpSession session = null;

        // Look for session id cookie
        if (_sessionManager.isUsingCookies())
        {
            Cookie[] cookies = request.getCookies();
            if (cookies != null && cookies.length > 0)
            {
                final String sessionCookie=sessionManager.getSessionCookieConfig().getName();
                for (int i = 0; i < cookies.length; i++)
                {
                    if (sessionCookie.equalsIgnoreCase(cookies[i].getName()))
                    {
                        requested_session_id = cookies[i].getValue();
                        requested_session_id_from_cookie = true;

                        LOG.debug("Got Session ID {} from cookie",requested_session_id);

                        if (requested_session_id != null)
                        {
                            session = sessionManager.getHttpSession(requested_session_id);

                            if (session != null && sessionManager.isValid(session))
                            {
                                break;
                            }
                        }
                        else
                        {
                            LOG.warn("null session id from cookie");
                        }
                    }
                }
            }
        }

        if (requested_session_id == null || session == null)
        {
            String uri = request.getRequestURI();

            String prefix = sessionManager.getSessionIdPathParameterNamePrefix();
            if (prefix != null)
            {
                int s = uri.indexOf(prefix);
                if (s >= 0)
                {
                    s += prefix.length();
                    int i = s;
                    while (i < uri.length())
                    {
                        char c = uri.charAt(i);
                        if (c == ';' || c == '#' || c == '?' || c == '/')
                            break;
                        i++;
                    }

                    requested_session_id = uri.substring(s,i);
                    requested_session_id_from_cookie = false;
                    session = sessionManager.getHttpSession(requested_session_id);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Got Session ID {} from URL",requested_session_id);
                }
            }
        }

        baseRequest.setRequestedSessionId(requested_session_id);
        baseRequest.setRequestedSessionIdFromCookie(requested_session_id != null && requested_session_id_from_cookie);
        if (session != null && sessionManager.isValid(session))
            baseRequest.setSession(session);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    public void addEventListener(EventListener listener)
    {
        if (_sessionManager != null)
            _sessionManager.addEventListener(listener);
    }

    /* ------------------------------------------------------------ */
    public void clearEventListeners()
    {
        if (_sessionManager != null)
            _sessionManager.clearEventListeners();
    }
}
