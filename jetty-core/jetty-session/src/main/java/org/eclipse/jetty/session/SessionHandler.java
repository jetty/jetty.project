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

package org.eclipse.jetty.session;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;

/**
 * A simple core SessionHandler
 */
public class SessionHandler extends AbstractSessionManager implements Handler.Singleton
{
    private Server _server;
    private Handler _handler;

    @Override
    public void setServer(Server server)
    {
        _server = server;
        Handler handler = getHandler();
        if (handler != null)
            handler.setServer(server);
    }

    @Override
    public Handler getHandler()
    {
        return _handler;
    }

    @Override
    public void setHandler(Handler handler)
    {
        _handler = Handler.Singleton.updateHandler(this, handler);
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        SessionRequest sessionRequest = new SessionRequest(request);
        addSessionStreamWrapper(sessionRequest);
        return sessionRequest.process(next, response, callback);
    }

    @Override
    public ManagedSession getManagedSession(Request request)
    {
        return Request.get(request, SessionRequest.class, SessionRequest::getManagedSession);
    }

    @Override
    public Session.API newSessionAPIWrapper(ManagedSession session)
    {
        return null;
    }

    private class SessionRequest extends Request.Wrapper
    {
        private final AtomicReference<ManagedSession> _session = new AtomicReference<>();
        private String _requestedSessionId;
        private Response _response;

        public SessionRequest(Request request)
        {
            super(request);
        }

        void setManagedSession(ManagedSession session)
        {
            _session.set(session);
        }

        ManagedSession getManagedSession()
        {
            return _session.get();
        }

        @Override
        public Session getSession(boolean create)
        {
            if (_response == null)
                throw new IllegalStateException("!processing");

            ManagedSession session = _session.get();

            if (session == null && create)
            {
                newSession(this, _requestedSessionId, this::setManagedSession);
                session = _session.get();
                HttpCookie cookie = getSessionCookie(session, getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.putCookie(_response, cookie);
            }

            return session == null || !session.isValid() ? null : session;
        }

        public boolean process(Handler handler, Response response, Callback callback) throws Exception
        {
            _response = response;

            RequestedSession requestedSession = resolveRequestedSessionId(this);
            _requestedSessionId = requestedSession.sessionId();
            ManagedSession session = requestedSession.session();

            if (session != null)
            {
                _session.set(session);
                HttpCookie cookie = access(session, getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.putCookie(_response, cookie);
            }

            return handler.handle(this, _response, callback);
        }
    }
}
