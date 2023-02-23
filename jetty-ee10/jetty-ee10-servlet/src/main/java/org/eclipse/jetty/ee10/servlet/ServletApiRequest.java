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
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.ArrayList;
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
import java.util.concurrent.ExecutionException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
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
import org.eclipse.jetty.ee10.servlet.security.Authentication;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.HttpCookieUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Jetty low level implementation of the ee10 {@link HttpServletRequest} object.
 *
 * <p>
 *     This provides the bridges from Servlet {@link HttpServletRequest} to the Jetty Core {@link Request} concepts (provided by the {@link ServletContextRequest})
 * </p>
 */
public class ServletApiRequest implements HttpServletRequest
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletApiRequest.class);
    private final ServletContextRequest _request;
    //TODO review which fields should be in ServletContextRequest
    private AsyncContextState _async;
    private String _characterEncoding;
    private int _inputState = ServletContextRequest.INPUT_NONE;
    private BufferedReader _reader;
    private String _readerEncoding;
    private String _contentType;
    private boolean _contentParamsExtracted;
    private Fields _contentParameters;
    private Fields _parameters;
    private Fields _queryParameters;
    private Authentication _authentication;
    private String _method;
    private ServletMultiPartFormData.Parts _parts;
    private boolean _asyncSupported = true;

    protected ServletApiRequest(ServletContextRequest servletContextRequest)
    {
        _request = servletContextRequest;
    }

    public Fields getQueryParams()
    {
        extractQueryParameters();
        return _queryParameters;
    }

    public Fields getContentParams()
    {
        extractContentParameters();
        return _contentParameters;
    }

    public void setAuthentication(Authentication authentication)
    {
        _authentication = authentication;
    }

    public Authentication getAuthentication()
    {
        return _authentication;
    }

    @Override
    public String getMethod()
    {
        if (_method == null)
            return getServletContextRequest().getMethod();
        else
            return _method;
    }

    //TODO shouldn't really be public?
    public void setMethod(String method)
    {
        _method = method;
    }

    public ServletContextRequest getServletContextRequest()
    {
        return _request;
    }

    public HttpFields getFields()
    {
        return _request.getHeaders();
    }

    @Override
    public String getRequestId()
    {
        return _request.getConnectionMetaData().getId() + "#" + _request.getId();
    }

    @Override
    public String getProtocolRequestId()
    {
        return _request.getId();
    }

    @Override
    public ServletConnection getServletConnection()
    {
        // TODO cache the results
        final ConnectionMetaData connectionMetaData = _request.getConnectionMetaData();
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
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(_request));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getAuthMethod();
        return null;
    }

    @Override
    public Cookie[] getCookies()
    {
        List<HttpCookie> httpCookies = Request.getCookies(getServletContextRequest());
        if (httpCookies.isEmpty())
            return null;
        return httpCookies.stream()
            .map(this::convertCookie)
            .toArray(Cookie[]::new);
    }

    public Cookie convertCookie(HttpCookie cookie)
    {
        Cookie result = new Cookie(cookie.getName(), cookie.getValue());
        //RFC2965 defines the cookie header as supporting path and domain but RFC6265 permits only name=value
        if (CookieCompliance.RFC2965.equals(getServletContextRequest().getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance()))
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
        return fields == null ? -1 : fields.getDateField(name);
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
        return getFields().getFieldNames();
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
        return _request._matchedPath.getPathInfo();
    }

    @Override
    public String getPathTranslated()
    {
        String pathInfo = getPathInfo();
        if (pathInfo == null || _request.getContext() == null)
            return null;
        return _request.getContext().getServletContext().getRealPath(pathInfo);
    }

    @Override
    public String getContextPath()
    {
        return _request.getContext().getServletContextHandler().getRequestContextPath();
    }

    @Override
    public String getQueryString()
    {
        return _request.getHttpURI().getQuery();
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
        String linkedRole = _request._mappedServlet.getServletHolder().getUserRoleLink(role);
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(_request));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).isUserInRole(linkedRole);
        return false;
    }

    @Override
    public Principal getUserPrincipal()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(_request));

        if (_authentication instanceof Authentication.User)
        {
            UserIdentity user = ((Authentication.User)_authentication).getUserIdentity();
            return user.getUserPrincipal();
        }

        return null;
    }

    @Override
    public String getRequestedSessionId()
    {
        AbstractSessionManager.RequestedSession requestedSession = _request.getRequestedSession();
        return requestedSession == null ? null : requestedSession.sessionId();
    }

    @Override
    public String getRequestURI()
    {
        HttpURI uri = _request.getHttpURI();
        return uri == null ? null : uri.getPath();
    }

    @Override
    public StringBuffer getRequestURL()
    {
        return new StringBuffer(HttpURI.build(_request.getHttpURI()).query(null).asString());
    }

    @Override
    public String getServletPath()
    {
        return _request._matchedPath.getPathMatch();
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        Session session = _request.getSession(create);
        if (session == null)
            return null;
        if (session.isNew() && getAuthentication() instanceof Authentication.User)
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
        Session session = _request.getSession(false);
        if (session == null)
            throw new IllegalStateException("No session");

        session.renewId(_request, _request.getResponse());

        if (getRemoteUser() != null)
            session.setAttribute(ManagedSession.SESSION_CREATED_SECURE, Boolean.TRUE);

        return session.getId();
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        AbstractSessionManager.RequestedSession requestedSession = _request.getRequestedSession();
        return requestedSession != null && requestedSession.sessionId() != null && !requestedSession.sessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        AbstractSessionManager.RequestedSession requestedSession = _request.getRequestedSession();
        return requestedSession != null && requestedSession.sessionId() != null && requestedSession.sessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        AbstractSessionManager.RequestedSession requestedSession = _request.getRequestedSession();
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
        if (_authentication instanceof Authentication.Deferred)
        {
            setAuthentication(((Authentication.Deferred)_authentication)
                .authenticate(_request, _request.getResponse(), getServletContextRequest().getServletChannel().getCallback()));
        }

        //if the authentication did not succeed
        if (_authentication instanceof Authentication.Deferred)
            response.sendError(HttpStatus.UNAUTHORIZED_401);

        //if the authentication is incomplete, return false
        if (!(_authentication instanceof Authentication.ResponseSent))
            return false;

        //TODO: this should only be returned IFF the authenticator has NOT set the response,
        //and the BasicAuthenticator at least will have set the response to SC_UNAUTHENTICATED
        //something has gone wrong
        throw new ServletException("Authentication failed");
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        if (_authentication instanceof Authentication.LoginAuthentication)
        {
            Authentication auth = ((Authentication.LoginAuthentication)_authentication).login(username, password, _request);
            if (auth == null)
                throw new Authentication.Failed("Authentication failed for username '" + username + "'");
            else
                _authentication = auth;
        }
        else
        {
            throw new Authentication.Failed("Authenticated failed for username '" + username + "'. Already authenticated as " + _authentication);
        }
    }

    @Override
    public void logout() throws ServletException
    {
        if (_authentication instanceof Authentication.LogoutAuthentication)
            _authentication = ((Authentication.LogoutAuthentication)_authentication).logout(_request);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        if (_parts == null)
        {
            String contentType = getContentType();
            if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpField.valueParameters(contentType, null)))
                throw new ServletException("Unsupported Content-Type [%s], expected [%s]".formatted(contentType, MimeTypes.Type.MULTIPART_FORM_DATA.asString()));

            MultipartConfigElement config = (MultipartConfigElement)getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT);
            if (config == null)
                throw new IllegalStateException("No multipart config for servlet");

            ServletContextHandler contextHandler = _request.getContext().getServletContextHandler();
            int maxFormContentSize = contextHandler.getMaxFormContentSize();
            int maxFormKeys = contextHandler.getMaxFormKeys();

            _parts = ServletMultiPartFormData.from(this, maxFormKeys);
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

            long formContentSize = 0;
            for (Part p : parts)
            {
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
                        if (_contentParameters == null)
                            _contentParameters = new Fields();
                        _contentParameters.add(p.getName(), content);
                    }
                }
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
        // TODO NYI
        return null;
    }

    @Override
    public PushBuilder newPushBuilder()
    {
        if (!_request.getConnectionMetaData().isPushSupported())
            return null;

        HttpFields.Mutable pushHeaders = HttpFields.build(_request.getHeaders(), EnumSet.of(
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

        // Any Set-Cookie in the response should be present in the push.
        HttpFields.Mutable responseHeaders = _request.getResponse().getHeaders();
        List<String> setCookies = new ArrayList<>(responseHeaders.getValuesList(HttpHeader.SET_COOKIE));
        setCookies.addAll(responseHeaders.getValuesList(HttpHeader.SET_COOKIE2));
        String cookies = pushHeaders.get(HttpHeader.COOKIE);
        if (!setCookies.isEmpty())
        {
            StringBuilder pushCookies = new StringBuilder();
            if (cookies != null)
                pushCookies.append(cookies);
            for (String setCookie : setCookies)
            {
                Map<String, String> cookieFields = HttpCookieUtils.extractBasics(setCookie);
                String cookieName = cookieFields.get("name");
                String cookieValue = cookieFields.get("value");
                String cookieMaxAge = cookieFields.get("max-age");
                long maxAge = cookieMaxAge != null ? Long.parseLong(cookieMaxAge) : -1;
                if (maxAge > 0)
                {
                    if (pushCookies.length() > 0)
                        pushCookies.append("; ");
                    pushCookies.append(cookieName).append("=").append(cookieValue);
                }
            }
            pushHeaders.put(HttpHeader.COOKIE, pushCookies.toString());
        }

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

        return new PushBuilderImpl(_request, pushHeaders, sessionId);
    }

    @Override
    public Object getAttribute(String name)
    {
        if (_async != null)
        {
            // This switch works by allowing the attribute to get underneath any dispatch wrapper.
            switch (name)
            {
                case AsyncContext.ASYNC_REQUEST_URI:
                    return getRequestURI();
                case AsyncContext.ASYNC_CONTEXT_PATH:
                    return getContextPath();
                case AsyncContext.ASYNC_SERVLET_PATH:
                    return getServletPath();
                case AsyncContext.ASYNC_PATH_INFO:
                    return getPathInfo();
                case AsyncContext.ASYNC_QUERY_STRING:
                    return getQueryString();
                case AsyncContext.ASYNC_MAPPING:
                    return getHttpServletMapping();
                default:
                    break;
            }
        }

        return _request.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        Set<String> set = _request.getAttributeNameSet();
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
        if (_characterEncoding == null)
        {
            if (_request.getContext() != null)
                _characterEncoding = _request.getContext().getServletContext().getRequestCharacterEncoding();

            if (_characterEncoding == null)
            {
                String contentType = getContentType();
                if (contentType != null)
                {
                    MimeTypes.Type mime = MimeTypes.CACHE.get(contentType);
                    String charset = (mime == null || mime.getCharset() == null) ? MimeTypes.getCharsetFromContentType(contentType) : mime.getCharset().toString();
                    if (charset != null)
                        _characterEncoding = charset;
                }
            }
        }
        return _characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        if (_inputState != ServletContextRequest.INPUT_NONE)
            return;

        _characterEncoding = encoding;

        // check encoding is supported
        if (!StringUtil.isUTF8(encoding))
        {
            try
            {
                Charset.forName(encoding);
            }
            catch (UnsupportedCharsetException e)
            {
                throw new UnsupportedEncodingException(e.getMessage());
            }
        }
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
        _inputState = ServletContextRequest.INPUT_STREAM;

        if (_request.getServletChannel().isExpecting100Continue())
            _request.getServletChannel().continue100(_request.getHttpInput().available());

        return _request.getHttpInput();
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

    public Fields getContentParameters()
    {
        getParameters(); // ensure extracted
        return _contentParameters;
    }

    public void setContentParameters(Fields params)
    {
        if (params == null || params.getSize() == 0)
            _contentParameters = ServletContextRequest.NO_PARAMS;
        else
            _contentParameters = params;
    }

    private Fields getParameters()
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

        // protect against calls to recycled requests (which is illegal, but
        // this gives better failures
        Fields parameters = _parameters;
        return parameters == null ? ServletContextRequest.NO_PARAMS : parameters;
    }

    private void extractContentParameters() throws BadMessageException
    {
        if (!_contentParamsExtracted)
        {
            // content parameters need boolean protection as they can only be read
            // once, but may be reset to null by a reset
            _contentParamsExtracted = true;

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
                        String baseType = HttpField.valueParameters(getContentType(), null);
                        if (MimeTypes.Type.FORM_ENCODED.is(baseType) &&
                            _request.getConnectionMetaData().getHttpConfiguration().isFormEncodedMethod(getMethod()))
                        {
                            try
                            {
                                int maxKeys = _request.getServletRequestState().getContextHandler().getMaxFormKeys();
                                int maxContentSize = _request.getServletRequestState().getContextHandler().getMaxFormContentSize();
                                _contentParameters = FormFields.from(getServletContextRequest(), maxKeys, maxContentSize).get();
                            }
                            catch (IllegalStateException | IllegalArgumentException | ExecutionException |
                                   InterruptedException e)
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
                            catch (IOException | ServletException e)
                            {
                                String msg = "Unable to extract content parameters";
                                if (LOG.isDebugEnabled())
                                    LOG.debug(msg, e);
                                throw new RuntimeIOException(msg, e);
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
    }

    private void extractQueryParameters() throws BadMessageException
    {
        // Extract query string parameters; these may be replaced by a forward()
        // and may have already been extracted by mergeQueryParameters().
        if (_queryParameters == null)
        {
            HttpURI httpURI = _request.getHttpURI();
            if (httpURI == null || StringUtil.isEmpty(httpURI.getQuery()))
                _queryParameters = ServletContextRequest.NO_PARAMS;
            else
            {
                try
                {
                    _queryParameters = Request.extractQueryParameters(_request, _request.getQueryEncoding());
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
        return _request.getConnectionMetaData().getProtocol();
    }

    @Override
    public String getScheme()
    {
        return _request.getHttpURI().getScheme();
    }

    @Override
    public String getServerName()
    {
        HttpURI uri = _request.getHttpURI();
        if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
            return formatAddrOrHost(uri.getHost());
        else
            return findServerName();
    }

    private String formatAddrOrHost(String name)
    {
        ServletChannel servletChannel = _request.getServletChannel();
        return servletChannel == null ? HostPort.normalizeHost(name) : servletChannel.formatAddrOrHost(name);
    }

    private String findServerName()
    {
        ServletChannel servletChannel = _request.getServletChannel();
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
        int port = -1;

        HttpURI uri = _request.getHttpURI();
        if ((uri != null) && StringUtil.isNotBlank(uri.getAuthority()))
            port = uri.getPort();
        else
            port = findServerPort();

        // If no port specified, return the default port for the scheme
        if (port <= 0)
            return HttpScheme.getDefaultPort(getScheme());

        // return a specific port
        return port;
    }

    private int findServerPort()
    {
        ServletChannel servletChannel = _request.getServletChannel();
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

        String encoding = getCharacterEncoding();
        if (encoding == null)
            encoding = StringUtil.__ISO_8859_1;

        if (_reader == null || !encoding.equalsIgnoreCase(_readerEncoding))
        {
            ServletInputStream in = getInputStream();
            _readerEncoding = encoding;
            _reader = new BufferedReader(new InputStreamReader(in, encoding))
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
        else if (_request.getServletChannel().isExpecting100Continue())
        {
            _request.getServletChannel().continue100(_request.getHttpInput().available());
        }
        _inputState = ServletContextRequest.INPUT_READER;
        return _reader;
    }

    @Override
    public String getRemoteAddr()
    {
        return Request.getRemoteAddr(_request);
    }

    @Override
    public String getRemoteHost()
    {
        // TODO: review.
        return Request.getRemoteAddr(_request);
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        Object oldValue = _request.setAttribute(name, attribute);

        if ("org.eclipse.jetty.server.Request.queryEncoding".equals(name))
            _request.setQueryEncoding(attribute == null ? null : attribute.toString());

        if (!_request.getRequestAttributeListeners().isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_request.getContext().getServletContext(), this, name, oldValue == null ? attribute : oldValue);
            for (ServletRequestAttributeListener l : _request.getRequestAttributeListeners())
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
        Object oldValue = _request.removeAttribute(name);

        if (oldValue != null && !_request.getRequestAttributeListeners().isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_request.getContext().getServletContext(), this, name, oldValue);
            for (ServletRequestAttributeListener listener : _request.getRequestAttributeListeners())
            {
                listener.attributeRemoved(event);
            }
        }
    }

    @Override
    public Locale getLocale()
    {
        return Request.getLocales(_request).get(0);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return Collections.enumeration(Request.getLocales(_request));
    }

    @Override
    public boolean isSecure()
    {
        return _request.getConnectionMetaData().isSecure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        ServletContextHandler.ServletScopedContext context = _request.getContext();
        if (path == null || context == null)
            return null;

        // handle relative path
        if (!path.startsWith("/"))
        {
            String relTo = _request.getPathInContext();
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
        return Request.getRemotePort(_request);
    }

    @Override
    public String getLocalName()
    {
        ServletChannel servletChannel = _request.getServletChannel();
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
        return Request.getLocalAddr(_request);
    }

    @Override
    public int getLocalPort()
    {
        return Request.getLocalPort(_request);
    }

    @Override
    public ServletContext getServletContext()
    {
        return _request.getServletChannel().getServletContext();
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (!isAsyncSupported())
            throw new IllegalStateException("Async Not Supported");
        ServletRequestState state = _request.getState();
        if (_async == null)
            _async = new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_request.getContext(), _async, state, this, _request.getResponse().getHttpServletResponse());
        state.startAsync(event);
        return _async;
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (!isAsyncSupported())
            throw new IllegalStateException("Async Not Supported");
        ServletRequestState state = _request.getState();
        if (_async == null)
            _async = new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_request.getContext(), _async, state, servletRequest, servletResponse);
        state.startAsync(event);
        return _async;
    }

    @Override
    public HttpServletMapping getHttpServletMapping()
    {
        return _request._mappedServlet.getServletPathMapping(_request.getPathInContext());
    }

    @Override
    public boolean isAsyncStarted()
    {
        return _request.getState().isAsyncStarted();
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
        ServletRequestState state = _request.getServletChannel().getState();
        if (_async == null || !state.isAsyncStarted())
            throw new IllegalStateException(state.getStatusString());

        return _async;
    }

    @Override
    public DispatcherType getDispatcherType()
    {
        return DispatcherType.REQUEST;
    }

    @Override
    public Map<String, String> getTrailerFields()
    {
        HttpFields trailers = _request.getTrailers();
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
}
