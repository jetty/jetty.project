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

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;

/**
 * SimpleSessionHandler example
 */
public class SimpleSessionHandler extends AbstractSessionManager implements Handler.Nested
{
    private Server _server;
    private Handler _handler;

    @Override
    public void setServer(Server server)
    {
        _server = server;
    }

    @Override
    public Handler getHandler()
    {
        return _handler;
    }

    @Override
    public void setHandler(Handler handler)
    {
        _handler = Nested.updateHandler(this, handler);
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        SessionRequest sessionRequest = new SessionRequest(request);
        addSessionStreamWrapper(request);
        return sessionRequest.process(next, response, callback);
    }

    @Override
    public Session getSession(Request request)
    {
        return Request.get(request, SessionRequest.class, SessionRequest::getCoreSession);
    }

    @Override
    public Session.APISession newSessionAPIWrapper(Session session)
    {
        return new SessionAPI(session);
    }

    public class SessionRequest extends Request.Wrapper
    {
        private final AtomicReference<Session> _session = new AtomicReference<>();
        private String _requestedSessionId;
        private Response _response;

        public SessionRequest(Request request)
        {
            super(request);
        }

        private Session getCoreSession()
        {
            return _session.get();
        }
        
        public void setCoreSession(Session session)
        {
            _session.set(session);
        }

        public SessionAPI getSession(boolean create)
        {
            if (_response == null)
                throw new IllegalStateException("!processing");

            Session session = _session.get();

            if (session == null && create)
            {
                newSession(this, _requestedSessionId, this::setCoreSession);
                session = _session.get();
                HttpCookie cookie = getSessionCookie(session, getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.replaceCookie(_response, cookie);
            }

            return session == null || session.isInvalid() ? null : session.getAPISession();
        }

        public boolean process(Handler handler, Response response, Callback callback) throws Exception
        {
            _response = response;

            RequestedSession requestedSession = resolveRequestedSessionId(this);
            _requestedSessionId = requestedSession.sessionId();
            Session session = requestedSession.session();
            _session.set(session);

            if (session != null)
            {
                HttpCookie cookie = access(session, getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.replaceCookie(_response, cookie);
            }

            return handler.process(this, _response, callback);
        }
    }

    public static class SessionAPI implements Session.APISession
    {
        private final Session _coreSession;

        public SessionAPI(Session coreSession)
        {
            _coreSession = coreSession;
        }

        @Override
        public Session getCoreSession()
        {
            return _coreSession;
        }

        public String getId()
        {
            return _coreSession.getId();
        }

        public Set<String> getAttributeNames()
        {
            return _coreSession.getNames();
        }

        public Object getAttribute(String name)
        {
            return _coreSession.getAttribute(name);
        }

        public void setAttribute(String name, Object value)
        {
            _coreSession.setAttribute(name, value);
        }

        public void invalidate()
        {
            _coreSession.invalidate();
        }

        public void renewId(Request request, Response response)
        {
            _coreSession.renewId(request);
            SessionManager sessionManager = _coreSession.getSessionManager();

            if (sessionManager.isUsingCookies())
                Response.replaceCookie(response, sessionManager.getSessionCookie(getCoreSession(), request.isSecure()));
        }
    }
}
