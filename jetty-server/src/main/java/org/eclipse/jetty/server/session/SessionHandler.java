// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.EventListener;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/** SessionHandler.
 *
 * 
 *
 */
public class SessionHandler extends HandlerWrapper
{
    /* -------------------------------------------------------------- */
    private SessionManager _sessionManager;

    /* ------------------------------------------------------------ */
    /** Constructor.
     * Construct a SessionHandler witha a HashSessionManager with a standard
     * java.util.Random generator is created.
     */
    public SessionHandler()
    {
        this(new HashSessionManager());
    }

    /* ------------------------------------------------------------ */
    /**
     * @param manager The session manager
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
     * @param sessionManager The sessionManager to set.
     */
    public void setSessionManager(SessionManager sessionManager)
    {
        if (isStarted())
            throw new IllegalStateException();
        SessionManager old_session_manager = _sessionManager;

        if (getServer()!=null)
            getServer().getContainer().update(this, old_session_manager, sessionManager, "sessionManager",true);

        if (sessionManager!=null)
            sessionManager.setSessionHandler(this);

        _sessionManager = sessionManager;

        if (old_session_manager!=null)
            old_session_manager.setSessionHandler(null);
    }


    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        Server old_server=getServer();
        if (old_server!=null && old_server!=server)
            old_server.getContainer().update(this, _sessionManager, null, "sessionManager",true);
        super.setServer(server);
        if (server!=null && server!=old_server)
            server.getContainer().update(this, null,_sessionManager, "sessionManager",true);
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
        super.doStop();
        _sessionManager.stop();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        setRequestedId(baseRequest,request);

        SessionManager old_session_manager=null;
        HttpSession old_session=null;

        try
        {
            old_session_manager = baseRequest.getSessionManager();
            old_session = baseRequest.getSession(false);

            if (old_session_manager != _sessionManager)
            {
                // new session context
                baseRequest.setSessionManager(_sessionManager);
                baseRequest.setSession(null);
            }

            // access any existing session
            HttpSession session=null;
            if (_sessionManager!=null)
            {
                session=baseRequest.getSession(false);
                if (session!=null)
                {
                    if(session!=old_session)
                    {
                        HttpCookie cookie = _sessionManager.access(session,request.isSecure());
                        if (cookie!=null ) // Handle changed ID or max-age refresh
                            baseRequest.getResponse().addCookie(cookie);
                    }
                }
                else
                {
                    session=baseRequest.recoverNewSession(_sessionManager);
                    if (session!=null)
                        baseRequest.setSession(session);
                }
            }

            if(Log.isDebugEnabled())
            {
                Log.debug("sessionManager="+_sessionManager);
                Log.debug("session="+session);
            }

            getHandler().handle(target, baseRequest, request, response);
        }
        finally
        {
            HttpSession session=request.getSession(false);

            if (old_session_manager != _sessionManager)
            {
                //leaving context, free up the session
                if (session!=null)
                    _sessionManager.complete(session);
                baseRequest.setSessionManager(old_session_manager);
                baseRequest.setSession(old_session);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Look for a requested session ID in cookies and URI parameters
     * @param request
     * @param dispatch
     */
    protected void setRequestedId(Request baseRequest, HttpServletRequest request)
    {
        String requested_session_id=request.getRequestedSessionId();
        if (!DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()) || requested_session_id!=null)
            return;

        SessionManager sessionManager = getSessionManager();
        boolean requested_session_id_from_cookie=false;
        HttpSession session=null;

        // Look for session id cookie
        if (_sessionManager.isUsingCookies())
        {
            Cookie[] cookies=request.getCookies();
            if (cookies!=null && cookies.length>0)
            {
                for (int i=0;i<cookies.length;i++)
                {
                    if (sessionManager.getSessionCookie().equalsIgnoreCase(cookies[i].getName()))
                    {
                        if (requested_session_id!=null)
                        {
                            // Multiple jsessionid cookies. Probably due to
                            // multiple paths and/or domains. Pick the first
                            // known session or the last defined cookie.
                            if (sessionManager.getHttpSession(requested_session_id)!=null)
                                break;
                        }

                        requested_session_id=cookies[i].getValue();
                        requested_session_id_from_cookie = true;
                        if(Log.isDebugEnabled())Log.debug("Got Session ID "+requested_session_id+" from cookie");
                        
                        session=sessionManager.getHttpSession(requested_session_id);
                        if (session!=null)
                            baseRequest.setSession(session);
                    }
                }
            }
        }

        if (requested_session_id==null || session==null)
        {
            String uri = request.getRequestURI();

            int semi = uri.lastIndexOf(';');
            if (semi>=0)
            {
                // check if there is a url encoded session param.
                String param=sessionManager.getSessionIdPathParameterName();
                if (param!=null)
                {
                    int p=uri.indexOf(param,semi+1);
                    if (p>0)
                    {
                        requested_session_id = uri.substring(p+param.length()+1);
                        requested_session_id_from_cookie = false;
                        if(Log.isDebugEnabled())Log.debug("Got Session ID "+requested_session_id+" from URL");
                    }
                }
            }
        }

        baseRequest.setRequestedSessionId(requested_session_id);
        baseRequest.setRequestedSessionIdFromCookie(requested_session_id!=null && requested_session_id_from_cookie);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    public void addEventListener(EventListener listener)
    {
        if(_sessionManager!=null)
            _sessionManager.addEventListener(listener);
    }

    /* ------------------------------------------------------------ */
    public void clearEventListeners()
    {
        if(_sessionManager!=null)
            _sessionManager.clearEventListeners();
    }
}
