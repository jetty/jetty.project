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
import java.util.concurrent.atomic.AtomicBoolean;
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
        _handler = handler;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        SessionRequest sessionRequest = new SessionRequest(request);

        Request.Processor processor = getHandler().handle(sessionRequest);
        if (processor == null)
            return null;

        addSessionStreamWrapper(request);
        return sessionRequest.wrapProcessor(processor);
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

    public class SessionRequest extends Request.WrapperProcessor
    {
        private final AtomicBoolean _resolved = new AtomicBoolean();
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

        public SessionAPI getSession(boolean create)
        {
            if (_response == null)
                throw new IllegalStateException("!processing");

            Session session;
            if (_resolved.compareAndSet(false, true))
            {
                RequestedSession requestedSession = resolveRequestedSessionId(this);
                _requestedSessionId = requestedSession.sessionId();
                _session.set(session = requestedSession.session());

                if (session != null)
                {
                    HttpCookie cookie = access(session, getConnectionMetaData().isSecure());
                    if (cookie != null)
                        Response.replaceCookie(_response, cookie);
                }
            }
            else
            {
                session = _session.get();
            }

            if (session == null && create)
            {
                _session.set(session = newSession(this, _requestedSessionId));
                HttpCookie cookie = getSessionCookie(session, getContext().getContextPath(), getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.replaceCookie(_response, cookie);
            }

            return session == null || session.isInvalid() ? null : session.getAPISession();
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            _response = response;
            super.process(ignored, _response, callback);
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
    }
}
