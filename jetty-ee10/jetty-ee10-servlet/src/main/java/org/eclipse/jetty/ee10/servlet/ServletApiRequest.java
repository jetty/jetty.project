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

package org.eclipse.jetty.ee10.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.PushBuilder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.ServletRequestInfo;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.SetCookieParser;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.CookieCache;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.HttpCookieUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Jetty implementation of the ee10 {@link HttpServletRequest} object.
 * This provides the bridge from Servlet {@link HttpServletRequest} to the Jetty Core {@link Request}
 * via the {@link ServletContextRequest}.
 */
public class ServletApiRequest implements HttpServletRequest
{
    public static class CrossContextForwarded extends ServletApiRequest
    {
        protected CrossContextForwarded(ServletContextRequest servletContextRequest)
        {
            super(servletContextRequest);
        }

        @Override
        protected void extractQueryParameters() throws BadMessageException
        {
            // Extract query string parameters; these may be replaced by a forward()
            // and may have already been extracted by mergeQueryParameters().
            if (_queryParameters == null)
            {
                String forwardQueryString = (String)getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
                String originalQueryString = getQueryString();
                if (StringUtil.isBlank(forwardQueryString))
                {
                    if (StringUtil.isBlank(originalQueryString))
                    {
                        _queryParameters = ServletContextRequest.NO_PARAMS;
                    }
                    else
                    {
                        _queryParameters = new Fields(true);
                        UrlEncoded.decodeTo(forwardQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                    }
                }
                else
                {
                    _queryParameters = new Fields(true);
                    if (!StringUtil.isBlank(originalQueryString))
                        UrlEncoded.decodeTo(originalQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                    UrlEncoded.decodeTo(forwardQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                }
            }
        }
    }

    public static class CrossContextIncluded extends ServletApiRequest
    {
        private final ServletPathMapping _originalMapping;

        protected CrossContextIncluded(ServletContextRequest servletContextRequest)
        {
            super(servletContextRequest);
            Request dispatchedRequest = servletContextRequest.getWrapped();

            _originalMapping = ServletPathMapping.from(dispatchedRequest.getAttribute(CrossContextDispatcher.ORIGINAL_SERVLET_MAPPING));

            //ensure the request is set up with the correct INCLUDE attributes now we know the matchedResource
            MatchedResource<ServletHandler.MappedServlet> matchedResource = servletContextRequest.getMatchedResource();
            dispatchedRequest.setAttribute(RequestDispatcher.INCLUDE_MAPPING, matchedResource.getResource().getServletPathMapping(getServletRequestInfo().getDecodedPathInContext()));
            dispatchedRequest.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, matchedResource.getMatchedPath().getPathMatch());
            dispatchedRequest.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, matchedResource.getMatchedPath().getPathInfo());
            dispatchedRequest.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, servletContextRequest.getContext().getContextPath());
        }
        
        @Override
        public String getPathInfo()
        {
            return _originalMapping == null ? null : _originalMapping.getPathInfo();
        }
        
        @Override
        public String getContextPath()
        {
            return (String)getAttribute(CrossContextDispatcher.ORIGINAL_CONTEXT_PATH);
        }
        
        @Override
        public String getQueryString()
        {
            return (String)getAttribute(CrossContextDispatcher.ORIGINAL_QUERY_STRING);
        }

        @Override
        public String getServletPath()
        {
            return _originalMapping == null ? null : _originalMapping.getServletPath();
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            return _originalMapping;
        }

        @Override
        public String getRequestURI()
        {
            return (String)getAttribute(CrossContextDispatcher.ORIGINAL_URI);
        }

        @Override
        protected void extractQueryParameters() throws BadMessageException
        {
            // Extract query string parameters; these may be replaced by a forward()
            // and may have already been extracted by mergeQueryParameters().
            if (_queryParameters == null)
            {
                String includedQueryString = (String)getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
                String originalQueryString = getQueryString();
                if (StringUtil.isBlank(includedQueryString))
                {
                    if (StringUtil.isBlank(originalQueryString))
                    {
                        _queryParameters = ServletContextRequest.NO_PARAMS;
                    }
                    else
                    {
                        _queryParameters = new Fields(true);
                        UrlEncoded.decodeTo(includedQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                    }
                }
                else
                {
                    _queryParameters = new Fields(true);
                    if (!StringUtil.isBlank(originalQueryString))
                        UrlEncoded.decodeTo(originalQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                    UrlEncoded.decodeTo(includedQueryString, _queryParameters::add, getServletRequestInfo().getQueryEncoding());
                }
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ServletApiRequest.class);
    private static final SetCookieParser SET_COOKIE_PARSER = SetCookieParser.newInstance();

    private final ServletContextRequest _servletContextRequest;
    private final ServletChannel _servletChannel;
    private AsyncContextState _async;
    private Charset _charset;
    private Charset _readerCharset;
    private int _inputState = ServletContextRequest.INPUT_NONE;
    private BufferedReader _reader;
    private String _contentType;
    protected Fields _contentParameters;
    private Fields _parameters;
    protected Fields _queryParameters;
    private ServletMultiPartFormData.Parts _parts;
    private boolean _asyncSupported = true;

    protected ServletApiRequest(ServletContextRequest servletContextRequest)
    {
        _servletContextRequest = servletContextRequest;
        _servletChannel = _servletContextRequest.getServletChannel();
    }

    public AuthenticationState getAuthentication()
    {
        return AuthenticationState.getAuthenticationState(getRequest());
    }

    private AuthenticationState getUndeferredAuthentication()
    {
        AuthenticationState authenticationState = getAuthentication();
        if (authenticationState instanceof AuthenticationState.Deferred deferred)
        {
            AuthenticationState undeferred = deferred.authenticate(getRequest());
            if (undeferred != null && undeferred != authenticationState)
            {
                authenticationState = undeferred;
                AuthenticationState.setAuthenticationState(getRequest(), authenticationState);
            }
        }
        return authenticationState;
    }

    @Override
    public String getMethod()
    {
        return getRequest().getMethod();
    }

    /**
     * @return The {@link ServletRequestInfo} view of the {@link ServletContextRequest} as wrapped
     * by the {@link ServletContextHandler}.
     * @see #getRequest()
     */
    public ServletRequestInfo getServletRequestInfo()
    {
        return _servletContextRequest;
    }

    /**
     * @return The core {@link Request} associated with the servlet API request. This may differ
     * from {@link ServletContextRequest} as wrapped by the {@link ServletContextHandler} as it
     * may have been further wrapped before being passed
     * to {@link ServletChannel#associate(Request, Response, Callback)}.
     * @see #getServletRequestInfo()
     * @see ServletChannel#associate(Request, Response, Callback)
     */
    public Request getRequest()
    {
        ServletChannel servletChannel = _servletChannel;
        return servletChannel == null ? _servletContextRequest : servletChannel.getRequest();
    }

    public HttpFields getFields()
    {
        return getRequest().getHeaders();
    }

    @Override
    public String getRequestId()
    {
        return getRequest().getConnectionMetaData().getId() + "#" + getRequest().getId();
    }

    @Override
    public String getProtocolRequestId()
    {
        return switch (getRequest().getConnectionMetaData().getHttpVersion())
        {
            case HTTP_2, HTTP_3 -> getRequest().getId();
            default -> "";
        };
    }

    @Override
    public ServletConnection getServletConnection()
    {
        // TODO cache the results
        final ConnectionMetaData connectionMetaData = getRequest().getConnectionMetaData();
        return new ServletConnection()
        {
            @Override
            public String getConnectionId()
            {
                return connectionMetaData.getId();
            }

            @Override
            public String getProtocol()
            {
                return connectionMetaData.getProtocol();
            }

            @Override
            public String getProtocolConnectionId()
            {
                // TODO review
                if (HttpVersion.HTTP_3.is(connectionMetaData.getProtocol()))
                    return connectionMetaData.getId();
                return "";
            }

            @Override
            public boolean isSecure()
            {
                return connectionMetaData.isSecure();
            }
        };
    }

    @Override
    public String getAuthType()
    {
        AuthenticationState authenticationState = getUndeferredAuthentication();
        if (authenticationState instanceof AuthenticationState.Succeeded succeededAuthentication)
            return succeededAuthentication.getAuthenticationType();
        return null;
    }

    @Override
    public Cookie[] getCookies()
    {
        return CookieCache.getApiCookies(getRequest(), Cookie.class, this::convertCookie);
    }

    private Cookie convertCookie(HttpCookie cookie)
    {
        CookieCompliance compliance = getRequest().getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance();
        Cookie result = new Cookie(cookie.getName(), cookie.getValue());
        //RFC2965 defines the cookie header as supporting path and domain but RFC6265 permits only name=value
        if (CookieCompliance.RFC2965.equals(compliance))
        {
            result.setPath(cookie.getPath());
            result.setDomain(cookie.getDomain());
        }
        return result;
    }

    @Override
    public long getDateHeader(String name)
    {
        HttpFields fields = getFields();
        if (fields == null)
            return -1;
        HttpField field = fields.getField(name);
        if (field == null)
            return -1;
        long date = fields.getDateField(name);
        if (date == -1)
            throw new IllegalArgumentException("Cannot parse date");
        return date;
    }

    @Override
    public String getHeader(String name)
    {
        return getFields().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        return getFields().getValues(name);
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        return Collections.enumeration(getFields().getFieldNamesCollection());
    }

    @Override
    public int getIntHeader(String name)
    {
        HttpFields fields = getFields();
        return fields == null ? -1 : (int)fields.getLongField(name);
    }

    @Override
    public String getPathInfo()
    {
        return getServletRequestInfo().getMatchedResource().getMatchedPath().getPathInfo();
    }

    @Override
    public String getPathTranslated()
    {
        String pathInfo = getPathInfo();
        if (pathInfo == null || getServletRequestInfo().getServletContext() == null)
            return null;
        return getServletRequestInfo().getServletContext().getServletContext().getRealPath(pathInfo);
    }

    @Override
    public String getContextPath()
    {
        return getServletRequestInfo().getServletContext().getServletContextHandler().getRequestContextPath();
    }

    @Override
    public String getQueryString()
    {
        return getRequest().getHttpURI().getQuery();
    }

    @Override
    public String getRemoteUser()
    {
        Principal p = getUserPrincipal();
        if (p == null)
            return null;
        return p.getName();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        //obtain any substituted role name from the destination servlet
        String linkedRole = getServletRequestInfo().getMatchedResource().getResource().getServletHolder().getUserRoleLink(role);
        AuthenticationState authenticationState = getUndeferredAuthentication();

        if (authenticationState instanceof AuthenticationState.Succeeded succeededAuthentication)
            return succeededAuthentication.isUserInRole(linkedRole);
        return false;
    }

    @Override
    public Principal getUserPrincipal()
    {
        AuthenticationState authenticationState = getUndeferredAuthentication();

        if (authenticationState instanceof AuthenticationState.Succeeded succeededAuthentication)
        {
            UserIdentity user = succeededAuthentication.getUserIdentity();
            return user.getUserPrincipal();
        }

        return null;
    }

    @Override
    public String getRequestedSessionId()
    {
        AbstractSessionManager.RequestedSession requestedSession = getServletRequestInfo().getRequestedSession();
        return requestedSession == null ? null : requestedSession.sessionId();
    }

    @Override
    public String getRequestURI()
    {
        HttpURI uri = getRequest().getHttpURI();
        return uri == null ? null : uri.getPath();
    }

    @Override
    public StringBuffer getRequestURL()
    {
        // Use the ServletContextRequest here as even if changed in the Request, it must match the servletPath and pathInfo
        return new StringBuffer(HttpURI.build(getRequest().getHttpURI()).query(null).asString());
    }

    @Override
    public String getServletPath()
    {
        return getServletRequestInfo().getMatchedResource().getMatchedPath().getPathMatch();
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        Session session = getRequest().getSession(create);
        if (session == null)
            return null;
        if (session.isNew() && getAuthentication() instanceof AuthenticationState.Succeeded)
            session.setAttribute(ManagedSession.SESSION_CREATED_SECURE, Boolean.TRUE);
        return session.getApi();
    }

    @Override
    public HttpSession getSession()
    {
        return getSession(true);
    }

    @Override
    public String changeSessionId()
    {
        Session session = getRequest().getSession(false);
        if (session == null)
            throw new IllegalStateException("No session");

        session.renewId(getRequest(), _servletChannel.getResponse());

        if (getRemoteUser() != null)
            session.setAttribute(ManagedSession.SESSION_CREATED_SECURE, Boolean.TRUE);

        return session.getId();
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        AbstractSessionManager.RequestedSession requestedSession = getServletRequestInfo().getRequestedSession();
        HttpSession session = getSession(false);
        SessionManager manager = getServletRequestInfo().getSessionManager();
        return requestedSession != null &&
            requestedSession.sessionId() != null &&
            requestedSession.session() != null &&
            requestedSession.session().isValid() &&
            manager != null &&
            manager.getSessionIdManager().getId(requestedSession.sessionId()).equals(session.getId());
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        AbstractSessionManager.RequestedSession requestedSession = getServletRequestInfo().getRequestedSession();
        return requestedSession != null && requestedSession.sessionId() != null && requestedSession.sessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        AbstractSessionManager.RequestedSession requestedSession = getServletRequestInfo().getRequestedSession();
        return requestedSession != null && requestedSession.sessionId() != null && !requestedSession.sessionIdFromCookie();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        //TODO: if authentication is deferred, we could authenticate first, otherwise we
        //are re-authenticating for each of getUserPrincipal, getRemoteUser and getAuthType

        //if already authenticated, return true
        if (getUserPrincipal() != null && getRemoteUser() != null && getAuthType() != null)
            return true;

        //do the authentication
        AuthenticationState authenticationState = getUndeferredAuthentication();

        //if the authentication did not succeed
        if (authenticationState instanceof AuthenticationState.Deferred)
            response.sendError(HttpStatus.UNAUTHORIZED_401);

        //if the authentication is incomplete, return false
        if (!(authenticationState instanceof AuthenticationState.ResponseSent))
            return false;

        //TODO: this should only be returned IFF the authenticator has NOT set the response,
        //and the BasicAuthenticator at least will have set the response to SC_UNAUTHENTICATED
        //something has gone wrong
        throw new ServletException("Authentication failed");
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        try
        {
            ServletRequestInfo servletRequestInfo = getServletRequestInfo();
            AuthenticationState.Succeeded succeededAuthentication = AuthenticationState.login(
                username, password, getRequest(), servletRequestInfo.getServletChannel().getServletContextResponse());

            if (succeededAuthentication == null)
                throw new QuietException.Exception("Authentication failed for username '" + username + "'");
        }
        catch (Throwable t)
        {
            throw new ServletException(t.getMessage(), t);
        }
    }

    @Override
    public void logout() throws ServletException
    {
        ServletRequestInfo servletRequestInfo = getServletRequestInfo();
        if (!AuthenticationState.logout(getRequest(), servletRequestInfo.getServletChannel().getServletContextResponse()))
            throw new ServletException("logout failed");
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        //TODO support parts read during a cross context dispatch to environment other than EE10
        if (_parts == null)
        {
            try
            {
                _parts = ServletMultiPartFormData.getParts(this);

                Collection<Part> parts = _parts.getParts();

                String formCharset = null;
                Part charsetPart = _parts.getPart("_charset_");
                if (charsetPart != null)
                {
                    try (InputStream is = charsetPart.getInputStream())
                    {
                        formCharset = IO.toString(is, StandardCharsets.UTF_8);
                    }
                }

                /*
                Select Charset to use for this part. (NOTE: charset behavior is for the part value only and not the part header/field names)
                1. Use the part specific charset as provided in that part's Content-Type header; else
                2. Use the overall default charset. Determined by:
                    a. if part name _charset_ exists, use that part's value.
                    b. if the request.getCharacterEncoding() returns a value, use that.
                        (note, this can be either from the charset field on the request Content-Type
                        header, or from a manual call to request.setCharacterEncoding())
                    c. use utf-8.
                */
                Charset defaultCharset;
                if (formCharset != null)
                    defaultCharset = Charset.forName(formCharset);
                else if (getCharacterEncoding() != null)
                    defaultCharset = Charset.forName(getCharacterEncoding());
                else
                    defaultCharset = StandardCharsets.UTF_8;

                // Recheck some constraints here, just in case the preloaded parts were not properly configured.
                ServletContextHandler servletContextHandler = getServletRequestInfo().getServletContext().getServletContextHandler();
                long maxFormContentSize = servletContextHandler.getMaxFormContentSize();
                int maxFormKeys = servletContextHandler.getMaxFormKeys();

                long formContentSize = 0;
                int count = 0;
                for (Part p : parts)
                {
                    if (maxFormKeys > 0 && ++count > maxFormKeys)
                        throw new IllegalStateException("Too many form keys > " + maxFormKeys);

                    if (p.getSubmittedFileName() == null)
                    {
                        formContentSize = Math.addExact(formContentSize, p.getSize());
                        if (maxFormContentSize >= 0 && formContentSize > maxFormContentSize)
                            throw new IllegalStateException("Form is larger than max length " + maxFormContentSize);

                        // Servlet Spec 3.0 pg 23, parts without filename must be put into params.
                        String charset = null;
                        if (p.getContentType() != null)
                            charset = MimeTypes.getCharsetFromContentType(p.getContentType());

                        try (InputStream is = p.getInputStream())
                        {
                            String content = IO.toString(is, charset == null ? defaultCharset : Charset.forName(charset));
                            if (_contentParameters == null || _contentParameters.isEmpty())
                                _contentParameters = new Fields(true);
                            _contentParameters.add(p.getName(), content);
                        }
                    }
                }
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("getParts", t);

                Throwable cause;
                if (t instanceof ExecutionException ee)
                    cause = ee.getCause();
                else if (t instanceof ServletException se)
                    cause = se.getCause();
                else
                    cause = t;

                if (cause instanceof IOException ioException)
                    throw ioException;

                throw new ServletException(new BadMessageException("bad multipart", cause));
            }
        }

        return _parts.getParts();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        getParts();
        return _parts.getPart(name);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        // Not implemented. Throw ServletException as per spec.
        throw new ServletException("Not implemented");
    }

    @Override
    public PushBuilder newPushBuilder()
    {
        if (!getRequest().getConnectionMetaData().isPushSupported())
            return null;

        HttpFields.Mutable pushHeaders = HttpFields.build(getRequest().getHeaders(), EnumSet.of(
            HttpHeader.IF_MATCH,
            HttpHeader.IF_RANGE,
            HttpHeader.IF_UNMODIFIED_SINCE,
            HttpHeader.RANGE,
            HttpHeader.EXPECT,
            HttpHeader.IF_NONE_MATCH,
            HttpHeader.IF_MODIFIED_SINCE)
        );

        String referrer = getRequestURL().toString();
        String query = getQueryString();
        if (query != null)
            referrer += "?" + query;
        pushHeaders.put(HttpHeader.REFERER, referrer);

        StringBuilder cookieBuilder = new StringBuilder();
        Cookie[] cookies = getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (!cookieBuilder.isEmpty())
                    cookieBuilder.append("; ");
                cookieBuilder.append(cookie.getName()).append("=").append(cookie.getValue());
            }
        }
        // Any Set-Cookie in the response should be present in the push.
        for (HttpField field : _servletContextRequest.getServletContextResponse().getHeaders())
        {
            HttpHeader header = field.getHeader();
            if (header == HttpHeader.SET_COOKIE || header == HttpHeader.SET_COOKIE2)
            {
                HttpCookie httpCookie;
                if (field instanceof HttpCookieUtils.SetCookieHttpField set)
                    httpCookie = set.getHttpCookie();
                else
                    httpCookie = SET_COOKIE_PARSER.parse(field.getValue());
                if (httpCookie == null || httpCookie.isExpired())
                    continue;
                if (!cookieBuilder.isEmpty())
                    cookieBuilder.append("; ");
                cookieBuilder.append(httpCookie.getName()).append("=").append(httpCookie.getValue());
            }
        }
        if (!cookieBuilder.isEmpty())
            pushHeaders.put(HttpHeader.COOKIE, cookieBuilder.toString());

        String sessionId;
        HttpSession httpSession = getSession(false);
        if (httpSession != null)
        {
            try
            {
                // Check that the session is valid;
                httpSession.getLastAccessedTime();
                sessionId = httpSession.getId();
            }
            catch (Throwable x)
            {
                if (LOG.isTraceEnabled())
                    LOG.trace("invalid HTTP session", x);
                sessionId = getRequestedSessionId();
            }
        }
        else
        {
            sessionId = getRequestedSessionId();
        }

        return new PushBuilderImpl(ServletContextRequest.getServletContextRequest(this), pushHeaders, sessionId);
    }

    @Override
    public Object getAttribute(String name)
    {
        if (_async != null)
        {
            // This switch works by allowing the attribute to get underneath any dispatch wrapper.
            // Note that there are further servlet specific attributes in ServletContextRequest
            return switch (name)
            {
                case AsyncContext.ASYNC_REQUEST_URI -> getRequestURI();
                case AsyncContext.ASYNC_CONTEXT_PATH -> getContextPath();
                case AsyncContext.ASYNC_SERVLET_PATH -> getServletPath();
                case AsyncContext.ASYNC_PATH_INFO -> getPathInfo();
                case AsyncContext.ASYNC_QUERY_STRING -> getQueryString();
                case AsyncContext.ASYNC_MAPPING -> getHttpServletMapping();
                default -> getRequest().getAttribute(name);
            };
        }

        return getRequest().getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        Set<String> set = getRequest().getAttributeNameSet();
        if (_async != null)
        {
            set = new HashSet<>(set);
            set.add(AsyncContext.ASYNC_REQUEST_URI);
            set.add(AsyncContext.ASYNC_CONTEXT_PATH);
            set.add(AsyncContext.ASYNC_SERVLET_PATH);
            set.add(AsyncContext.ASYNC_PATH_INFO);
            set.add(AsyncContext.ASYNC_QUERY_STRING);
            set.add(AsyncContext.ASYNC_MAPPING);
        }

        return Collections.enumeration(set);
    }

    @Override
    public String getCharacterEncoding()
    {
        try
        {
            if (_charset == null)
                _charset = Request.getCharset(getRequest());
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e)
        {
            return MimeTypes.getCharsetFromContentType(getRequest().getHeaders().get(HttpHeader.CONTENT_TYPE));
        }

        if (_charset == null)
            return getServletRequestInfo().getServletContext().getServletContext().getRequestCharacterEncoding();

        return _charset.name();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        if (_inputState != ServletContextRequest.INPUT_NONE)
            return;
        _charset = MimeTypes.getKnownCharset(encoding);
    }

    @Override
    public int getContentLength()
    {
        long contentLength = getContentLengthLong();
        if (contentLength > Integer.MAX_VALUE)
            // Per ServletRequest#getContentLength() javadoc this must return -1 for values exceeding Integer.MAX_VALUE
            return -1;
        return (int)contentLength;
    }

    @Override
    public long getContentLengthLong()
    {
        // Even thought the metadata might know the real content length,
        // we always look at the headers because the length may be changed by interceptors.
        if (getFields() == null)
            return -1;

        return getFields().getLongField(HttpHeader.CONTENT_LENGTH);
    }

    @Override
    public String getContentType()
    {
        if (_contentType == null)
            _contentType = getFields().get(HttpHeader.CONTENT_TYPE);
        return _contentType;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        if (_inputState != ServletContextRequest.INPUT_NONE && _inputState != ServletContextRequest.INPUT_STREAM)
            throw new IllegalStateException("READER");

        // Try to write a 100 continue if it is necessary
        if (_inputState == ServletContextRequest.INPUT_NONE && _servletContextRequest.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
            _servletChannel.getResponse().writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY);

        _inputState = ServletContextRequest.INPUT_STREAM;
        return getServletRequestInfo().getHttpInput();
    }

    @Override
    public String getParameter(String name)
    {
        return getParameters().getValue(name);
    }

    @Override
    public Enumeration<String> getParameterNames()
    {
        return Collections.enumeration(getParameters().getNames());
    }

    @Override
    public String[] getParameterValues(String name)
    {
        List<String> vals = getParameters().getValues(name);
        if (vals == null)
            return null;
        return vals.toArray(new String[0]);
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        return Collections.unmodifiableMap(getParameters().toStringArrayMap());
    }

    public Fields getParameters()
    {
        // protect against calls to recycled requests (which is illegal, but
        // this gives better failures
        Fields parameters = _parameters;
        if (parameters == null)
        {
            extractContentParameters();
            extractQueryParameters();

            // Do parameters need to be combined?
            if (ServletContextRequest.isNoParams(_queryParameters) || _queryParameters.getSize() == 0)
                _parameters = _contentParameters;
            else if (ServletContextRequest.isNoParams(_contentParameters) || _contentParameters.getSize() == 0)
                _parameters = _queryParameters;
            else if (_parameters == null)
            {
                _parameters = new Fields(true);
                _parameters.addAll(_queryParameters);
                _parameters.addAll(_contentParameters);
            }
            parameters = _parameters;
        }
        return parameters == null ? ServletContextRequest.NO_PARAMS : parameters;
    }

    private void extractContentParameters() throws BadMessageException
    {
        // Extract content parameters; these cannot be replaced by a forward()
        // once extracted and may have already been extracted by getParts() or
        // by a processing happening after a form-based authentication.
        if (_contentParameters == null)
        {
            try
            {
                int contentLength = getContentLength();
                if (contentLength != 0 && _inputState == ServletContextRequest.INPUT_NONE)
                {
                    String baseType = HttpField.getValueParameters(getContentType(), null);
                    if (MimeTypes.Type.FORM_ENCODED.is(baseType) &&
                        getRequest().getConnectionMetaData().getHttpConfiguration().isFormEncodedMethod(getMethod()))
                    {
                        try
                        {
                            ServletContextHandler contextHandler = getServletRequestInfo().getServletContextHandler();
                            int maxKeys = contextHandler.getMaxFormKeys();
                            int maxContentSize = contextHandler.getMaxFormContentSize();
                            _contentParameters = FormFields.getFields(getRequest(), maxKeys, maxContentSize);
                        }
                        catch (IllegalStateException | IllegalArgumentException | CompletionException e)
                        {
                            LOG.warn(e.toString());
                            throw new BadMessageException("Unable to parse form content", e);
                        }
                    }
                    else if (MimeTypes.Type.MULTIPART_FORM_DATA.is(baseType) &&
                        getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT) != null)
                    {
                        try
                        {
                            getParts();
                        }
                        catch (IOException e)
                        {
                            String msg = "Unable to extract content parameters";
                            if (LOG.isDebugEnabled())
                                LOG.debug(msg, e);
                            throw new RuntimeIOException(msg, e);
                        }
                        catch (ServletException e)
                        {
                            Throwable cause = e.getCause();
                            if (cause instanceof BadMessageException badMessageException)
                                throw badMessageException;

                            String msg = "Unable to extract content parameters";
                            if (LOG.isDebugEnabled())
                                LOG.debug(msg, e);
                            throw new RuntimeIOException(msg, e);
                        }
                    }
                    else
                    {
                        try
                        {
                            _contentParameters = FormFields.getFields(getRequest());
                        }
                        catch (IllegalStateException | IllegalArgumentException | CompletionException e)
                        {
                            LOG.warn(e.toString());
                            throw new BadMessageException("Unable to parse form content", e);
                        }
                    }
                }

                if (_contentParameters == null || _contentParameters.isEmpty())
                    _contentParameters = ServletContextRequest.NO_PARAMS;
            }
            catch (IllegalStateException | IllegalArgumentException e)
            {
                LOG.warn(e.toString());
                throw new BadMessageException("Unable to parse form content", e);
            }
        }
    }

    protected void extractQueryParameters() throws BadMessageException
    {
        // Extract query string parameters; these may be replaced by a forward()
        // and may have already been extracted by mergeQueryParameters().
        if (_queryParameters == null)
        {
            HttpURI httpURI = getRequest().getHttpURI();
            if (httpURI == null || StringUtil.isEmpty(httpURI.getQuery()))
                _queryParameters = ServletContextRequest.NO_PARAMS;
            else
            {
                try
                {
                    _queryParameters = Request.extractQueryParameters(getRequest(), getServletRequestInfo().getQueryEncoding());
                }
                catch (IllegalStateException | IllegalArgumentException e)
                {
                    _queryParameters = ServletContextRequest.BAD_PARAMS;
                    throw new BadMessageException("Unable to parse URI query", e);
                }
            }
        }
    }

    @Override
    public String getProtocol()
    {
        return getRequest().getConnectionMetaData().getProtocol();
    }

    @Override
    public String getScheme()
    {
        return getRequest().getHttpURI().getScheme();
    }

    @Override
    public String getServerName()
    {
        HttpURI uri = getRequest().getHttpURI();
        if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
            return formatAddrOrHost(uri.getHost());
        else
            return findServerName();
    }

    private String formatAddrOrHost(String name)
    {
        ServletChannel servletChannel = _servletChannel;
        return servletChannel == null ? HostPort.normalizeHost(name) : servletChannel.formatAddrOrHost(name);
    }

    private String findServerName()
    {
        ServletChannel servletChannel = _servletChannel;
        if (servletChannel != null)
        {
            HostPort serverAuthority = servletChannel.getServerAuthority();
            if (serverAuthority != null)
                return formatAddrOrHost(serverAuthority.getHost());
        }

        // Return host from connection
        String name = getLocalName();
        if (name != null)
            return formatAddrOrHost(name);

        return ""; // not allowed to be null
    }

    @Override
    public int getServerPort()
    {
        int port;

        HttpURI uri = getRequest().getHttpURI();
        if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
            port = uri.getPort();
        else
            port = findServerPort();

        // If no port specified, return the default port for the scheme
        if (port <= 0)
            return URIUtil.getDefaultPortForScheme(getScheme());

        // return a specific port
        return port;
    }

    private int findServerPort()
    {
        ServletChannel servletChannel = getServletRequestInfo().getServletChannel();
        if (servletChannel != null)
        {
            HostPort serverAuthority = servletChannel.getServerAuthority();
            if (serverAuthority != null)
                return serverAuthority.getPort();
        }

        // Return host from connection
        return getLocalPort();
    }

    @Override
    public BufferedReader getReader() throws IOException
    {
        if (_inputState != ServletContextRequest.INPUT_NONE && _inputState != ServletContextRequest.INPUT_READER)
            throw new IllegalStateException("STREAMED");

        if (_inputState == ServletContextRequest.INPUT_READER)
            return _reader;

        Charset charset = _charset;
        try
        {
            if (charset == null)
            {
                charset = _charset = Request.getCharset(getRequest());
                if (charset == null)
                    charset = StandardCharsets.ISO_8859_1;
            }
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e)
        {
            throw new UnsupportedEncodingException(e.getMessage())
            {
                {
                    initCause(e);
                }

                @Override
                public String toString()
                {
                    return "%s@%x:%s".formatted(UnsupportedEncodingException.class.getName(), hashCode(), getMessage());
                }
            };
        }

        if (_reader != null && charset.equals(_readerCharset))
        {
            // Try to write a 100 continue, ignoring failure result if it was not necessary.
            _servletChannel.getResponse().writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY);
        }
        else
        {
            ServletInputStream in = getInputStream();
            _readerCharset = charset;
            _reader = new BufferedReader(new InputStreamReader(in, charset))
            {
                @Override
                public void close() throws IOException
                {
                    // Do not call super to avoid marking this reader as closed,
                    // but do close the ServletInputStream that can be reopened.
                    in.close();
                }
            };
        }
        _inputState = ServletContextRequest.INPUT_READER;
        return _reader;
    }

    @Override
    public String getRemoteAddr()
    {
        return Request.getRemoteAddr(getRequest());
    }

    @Override
    public String getRemoteHost()
    {
        // TODO: review.
        return Request.getRemoteAddr(getRequest());
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        Object oldValue = getRequest().setAttribute(name, attribute);

        if ("org.eclipse.jetty.server.Request.queryEncoding".equals(name))
            getServletRequestInfo().setQueryEncoding(attribute == null ? null : attribute.toString());

        if (!getServletRequestInfo().getRequestAttributeListeners().isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(getServletRequestInfo().getServletContext().getServletContext(), this, name, oldValue == null ? attribute : oldValue);
            for (ServletRequestAttributeListener l : getServletRequestInfo().getRequestAttributeListeners())
            {
                if (oldValue == null)
                    l.attributeAdded(event);
                else if (attribute == null)
                    l.attributeRemoved(event);
                else
                    l.attributeReplaced(event);
            }
        }
    }

    @Override
    public void removeAttribute(String name)
    {
        Object oldValue = getRequest().removeAttribute(name);

        if (oldValue != null && !getServletRequestInfo().getRequestAttributeListeners().isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(getServletRequestInfo().getServletContext().getServletContext(), this, name, oldValue);
            for (ServletRequestAttributeListener listener : getServletRequestInfo().getRequestAttributeListeners())
            {
                listener.attributeRemoved(event);
            }
        }
    }

    @Override
    public Locale getLocale()
    {
        return Request.getLocales(getRequest()).get(0);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return Collections.enumeration(Request.getLocales(getRequest()));
    }

    @Override
    public boolean isSecure()
    {
        return getRequest().isSecure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        ServletContextHandler.ServletScopedContext context = getServletRequestInfo().getServletContext();
        if (path == null || context == null)
            return null;

        // handle relative path
        if (!path.startsWith("/"))
        {
            String relTo = getServletRequestInfo().getDecodedPathInContext();
            int slash = relTo.lastIndexOf("/");
            if (slash > 1)
                relTo = relTo.substring(0, slash + 1);
            else
                relTo = "/";
            path = URIUtil.addPaths(relTo, path);
        }

        return context.getServletContext().getRequestDispatcher(path);
    }

    @Override
    public int getRemotePort()
    {
        return Request.getRemotePort(getRequest());
    }

    @Override
    public String getLocalName()
    {
        ServletChannel servletChannel = getServletRequestInfo().getServletChannel();
        if (servletChannel != null)
        {
            String localName = servletChannel.getLocalName();
            return formatAddrOrHost(localName);
        }

        return ""; // not allowed to be null
    }

    @Override
    public String getLocalAddr()
    {
        return Request.getLocalAddr(getRequest());
    }

    @Override
    public int getLocalPort()
    {
        return Request.getLocalPort(getRequest());
    }

    @Override
    public ServletContext getServletContext()
    {
        return getServletRequestInfo().getServletChannel().getServletContextApi();
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (!isAsyncSupported())
            throw new IllegalStateException("Async Not Supported");
        ServletChannelState state = getServletRequestInfo().getState();
        if (_async == null)
            _async = new AsyncContextState(state);
        ServletRequestInfo servletRequestInfo = getServletRequestInfo();
        AsyncContextEvent event = new AsyncContextEvent(getServletRequestInfo().getServletContext(), _async, state, this, servletRequestInfo.getServletChannel().getServletContextResponse().getServletApiResponse());
        state.startAsync(event);
        return _async;
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (!isAsyncSupported())
            throw new IllegalStateException("Async Not Supported");
        ServletChannelState state = getServletRequestInfo().getState();
        if (_async == null)
            _async = new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(getServletRequestInfo().getServletContext(), _async, state, servletRequest, servletResponse);
        state.startAsync(event);
        return _async;
    }

    @Override
    public HttpServletMapping getHttpServletMapping()
    {
        return getServletRequestInfo().getMatchedResource().getResource().getServletPathMapping(getServletRequestInfo().getDecodedPathInContext());
    }

    @Override
    public boolean isAsyncStarted()
    {
        return getServletRequestInfo().getState().isAsyncStarted();
    }

    @Override
    public boolean isAsyncSupported()
    {
        return _asyncSupported;
    }

    public void setAsyncSupported(boolean asyncSupported)
    {
        _asyncSupported = asyncSupported;
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        ServletChannelState state = getServletRequestInfo().getServletChannel().getServletRequestState();
        if (_async == null || !state.isAsyncStarted())
            throw new IllegalStateException(state.getStatusString());

        return _async;
    }

    @Override
    public DispatcherType getDispatcherType()
    {
        Request request = getRequest();
        String dispatchType = request.getContext().getCrossContextDispatchType(request);
        return dispatchType == null ? DispatcherType.REQUEST : DispatcherType.valueOf(dispatchType);
    }

    @Override
    public Map<String, String> getTrailerFields()
    {
        HttpFields trailers = getRequest().getTrailers();
        if (trailers == null)
            return Map.of();
        Map<String, String> trailersMap = new HashMap<>();
        for (HttpField field : trailers)
        {
            String key = field.getLowerCaseName();
            // Servlet spec requires field names to be lower case.
            trailersMap.merge(key, field.getValue(), (existing, value) -> existing + "," + value);
        }
        return trailersMap;
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), _servletContextRequest);
    }

    static class AmbiguousURI extends ServletApiRequest
    {
        private final String msg;

        protected AmbiguousURI(ServletContextRequest servletContextRequest, String msg)
        {
            super(servletContextRequest);
            this.msg = msg;
        }

        @Override
        public String getPathInfo()
        {
            throw new HttpException.IllegalArgumentException(HttpStatus.BAD_REQUEST_400, msg);
        }

        @Override
        public String getServletPath()
        {
            throw new HttpException.IllegalArgumentException(HttpStatus.BAD_REQUEST_400, msg);
        }
    }
}
