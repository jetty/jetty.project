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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;

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
    public Request.Processor handle(Request request) throws Exception
    {
        SessionRequest sessionRequest = new SessionRequest(request);

        Request.Processor processor = getHandler().handle(sessionRequest);
        if (processor == null)
            return null;

        return new Request.ReWrappingProcessor<>(processor, sessionRequest)
        {
            @Override
            protected SessionRequest wrap(Request request)
            {
                return new SessionRequest(request);
            }

            @Override
            protected void process(SessionRequest sessionRequest, Response response, Callback callback, Request.Processor next) throws Exception
            {
                addSessionStreamWrapper(sessionRequest.getWrapped());
                sessionRequest._response = response;

                RequestedSession requestedSession = resolveRequestedSessionId(sessionRequest);
                sessionRequest._requestedSessionId = requestedSession.sessionId();
                Session session = requestedSession.session();
                sessionRequest._session.set(session);

                if (session != null)
                {
                    HttpCookie cookie = access(session, sessionRequest.getConnectionMetaData().isSecure());
                    if (cookie != null)
                        Response.replaceCookie(response, cookie);
                }

                next.process(sessionRequest, response, callback);
            }
        };
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
                HttpCookie cookie = getSessionCookie(session, getContext().getContextPath(), getConnectionMetaData().isSecure());
                if (cookie != null)
                    Response.replaceCookie(_response, cookie);
            }

            return session == null || session.isInvalid() ? null : session.getAPISession();
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
                Response.replaceCookie(response, sessionManager.getSessionCookie(getCoreSession(), request.getContext().getContextPath(), request.isSecure()));
        }
    }

    @Override
    public HttpCookie getSessionCookie(Session session, String contextPath, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            String sessionPath = getSessionPath();
            sessionPath = (sessionPath == null) ? contextPath : sessionPath;
            sessionPath = (StringUtil.isEmpty(sessionPath)) ? "/" : sessionPath;
            SameSite sameSite = HttpCookie.getSameSiteFromComment(getSessionComment());
            Map<String, String> attributes = Collections.emptyMap();
            if (sameSite != null)
                attributes = Collections.singletonMap("SameSite", sameSite.getAttributeValue());
            return session.generateSetCookie((getSessionCookie() == null ? __DefaultSessionCookie : getSessionCookie()),
                getSessionDomain(),
                sessionPath,
                getMaxCookieAge(),
                isHttpOnly(),
                isSecureCookies() || (isSecureRequestOnly() && requestIsSecure),
                HttpCookie.getCommentWithoutAttributes(getSessionComment()),
                0,
                attributes);
        }
        return null;
    }

    @Override
    public SameSite getSameSite()
    {
        return HttpCookie.getSameSiteFromComment(getSessionComment());
    }

    @Override
    public void setSameSite(SameSite sameSite)
    {
        setSessionComment(HttpCookie.getCommentWithAttributes(getSessionComment(), false, sameSite));
    }
}
