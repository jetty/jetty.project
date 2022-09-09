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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;
import javax.servlet.http.PushBuilder;
import javax.servlet.http.WebConnection;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookie.SetCookieHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty Request.
 * <p>
 * Implements {@link javax.servlet.http.HttpServletRequest} from the <code>javax.servlet.http</code> package.
 * </p>
 * <p>
 * The standard interface of mostly getters, is extended with setters so that the request is mutable by the handlers that it is passed to. This allows the
 * request object to be as lightweight as possible and not actually implement any significant behavior. For example
 * <ul>
 *
 * <li>the {@link Request#getContextPath()} method will return null, until the request has been passed to a {@link ContextHandler} which matches the
 * {@link Request#getPathInfo()} with a context path and calls {@link Request#setContext(Context,String)} as a result.  For
 * some dispatch types (ie include and named dispatch) the context path may not reflect the {@link ServletContext} set
 * by {@link Request#setContext(Context, String)}.</li>
 *
 * <li>the HTTP session methods will all return null sessions until such time as a request has been passed to a
 * {@link org.eclipse.jetty.server.session.SessionHandler} which checks for session cookies and enables the ability to create new sessions.</li>
 *
 * <li>The {@link Request#getServletPath()} method will return "" until the request has been passed to a <code>org.eclipse.jetty.servlet.ServletHandler</code>
 * and the pathInfo matched against the servlet URL patterns and {@link Request#setServletPathMapping(ServletPathMapping)} called as a result.</li>
 * </ul>
 *
 * <p>
 * A request instance is created for each connection accepted by the server and recycled for each HTTP request received via that connection.
 * An effort is made to avoid reparsing headers and cookies that are likely to be the same for requests from the same connection.
 * </p>
 * <p>
 * Request instances are recycled, which combined with badly written asynchronous applications can result in calls on requests that have been reset.
 * The code is written in a style to avoid NPE and ISE when such calls are made, as this has often proved generate exceptions that distraction
 * from debugging such bad asynchronous applications.  Instead, request methods attempt to not fail when called in an illegal state, so that hopefully
 * the bad application will proceed to a major state event (eg calling AsyncContext.onComplete) which has better asynchronous guards, true atomic state
 * and better failure behaviour that will assist in debugging.
 * </p>
 * <p>
 * The form content that a request can process is limited to protect from Denial of Service attacks. The size in bytes is limited by
 * {@link ContextHandler#getMaxFormContentSize()} or if there is no context then the "org.eclipse.jetty.server.Request.maxFormContentSize" {@link Server}
 * attribute. The number of parameters keys is limited by {@link ContextHandler#getMaxFormKeys()} or if there is no context then the
 * "org.eclipse.jetty.server.Request.maxFormKeys" {@link Server} attribute.
 * </p>
 * <p>If IOExceptions or timeouts occur while reading form parameters, these are thrown as unchecked Exceptions: ether {@link RuntimeIOException},
 * {@link BadMessageException} or {@link RuntimeException} as appropriate.</p>
 */
public class Request implements HttpServletRequest
{
    public static final String __MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";

    private static final Logger LOG = LoggerFactory.getLogger(Request.class);
    private static final Collection<Locale> __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int INPUT_NONE = 0;
    private static final int INPUT_STREAM = 1;
    private static final int INPUT_READER = 2;

    private static final MultiMap<String> NO_PARAMS = new MultiMap<>();
    private static final MultiMap<String> BAD_PARAMS = new MultiMap<>();

    /**
     * Compare inputParameters to NO_PARAMS by Reference
     *
     * @param inputParameters The parameters to compare to NO_PARAMS
     * @return True if the inputParameters reference is equal to NO_PARAMS otherwise False
     */
    private static boolean isNoParams(MultiMap<String> inputParameters)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isNoParams = (inputParameters == NO_PARAMS);
        return isNoParams;
    }

    /**
     * Obtain the base {@link Request} instance of a {@link ServletRequest}, by
     * coercion, unwrapping or special attribute.
     *
     * @param request The request
     * @return the base {@link Request} instance of a {@link ServletRequest}.
     */
    public static Request getBaseRequest(ServletRequest request)
    {
        if (request instanceof Request)
            return (Request)request;

        Object channel = request.getAttribute(HttpChannel.class.getName());
        if (channel instanceof HttpChannel)
            return ((HttpChannel)channel).getRequest();

        while (request instanceof ServletRequestWrapper)
        {
            request = ((ServletRequestWrapper)request).getRequest();
        }

        if (request instanceof Request)
            return (Request)request;

        return null;
    }

    private final HttpChannel _channel;
    private final List<ServletRequestAttributeListener> _requestAttributeListeners = new ArrayList<>();
    private final HttpInput _input;
    private MetaData.Request _metaData;
    private HttpFields _httpFields;
    private HttpFields _trailers;
    private HttpURI _uri;
    private String _method;
    private String _pathInContext;
    private ServletPathMapping _servletPathMapping;
    private Object _asyncNotSupportedSource = null;
    private boolean _secure;
    private boolean _newContext;
    private boolean _cookiesExtracted = false;
    private boolean _handled = false;
    private boolean _contentParamsExtracted;
    private boolean _requestedSessionIdFromCookie = false;
    private Attributes _attributes;
    private Authentication _authentication;
    private String _contentType;
    private String _characterEncoding;
    private ContextHandler.Context _context;
    private ContextHandler.Context _errorContext;
    private Cookies _cookies;
    private DispatcherType _dispatcherType;
    private int _inputState = INPUT_NONE;
    private BufferedReader _reader;
    private String _readerEncoding;
    private MultiMap<String> _queryParameters;
    private MultiMap<String> _contentParameters;
    private MultiMap<String> _parameters;
    private Charset _queryEncoding;
    private InetSocketAddress _remote;
    private String _requestedSessionId;
    private UserIdentity.Scope _scope;
    private HttpSession _session;
    private SessionHandler _sessionHandler;
    private long _timeStamp;
    private MultiParts _multiParts; //if the request is a multi-part mime
    private AsyncContextState _async;
    private List<Session> _sessions; //list of sessions used during lifetime of request

    public Request(HttpChannel channel, HttpInput input)
    {
        _channel = channel;
        _input = input;
    }

    public HttpFields getHttpFields()
    {
        return _httpFields;
    }

    public void setHttpFields(HttpFields fields)
    {
        _httpFields = fields.asImmutable();
    }

    @Override
    public Map<String, String> getTrailerFields()
    {
        HttpFields trailersFields = getTrailerHttpFields();
        if (trailersFields == null)
            return Collections.emptyMap();
        Map<String, String> trailers = new HashMap<>();
        for (HttpField field : trailersFields)
        {
            String key = field.getName().toLowerCase();
            String value = trailers.get(key);
            trailers.put(key, value == null ? field.getValue() : value + "," + field.getValue());
        }
        return trailers;
    }

    public void setTrailerHttpFields(HttpFields trailers)
    {
        _trailers = trailers == null ? null : trailers.asImmutable();
    }

    public HttpFields getTrailerHttpFields()
    {
        return _trailers;
    }

    public HttpInput getHttpInput()
    {
        return _input;
    }

    public boolean isPush()
    {
        return Boolean.TRUE.equals(getAttribute("org.eclipse.jetty.pushed"));
    }

    public boolean isPushSupported()
    {
        return !isPush() && getHttpChannel().getHttpTransport().isPushSupported();
    }

    private static final EnumSet<HttpHeader> NOT_PUSHED_HEADERS = EnumSet.of(
        HttpHeader.IF_MATCH,
        HttpHeader.IF_RANGE,
        HttpHeader.IF_UNMODIFIED_SINCE,
        HttpHeader.RANGE,
        HttpHeader.EXPECT,
        HttpHeader.REFERER,
        HttpHeader.COOKIE,
        HttpHeader.AUTHORIZATION,
        HttpHeader.IF_NONE_MATCH,
        HttpHeader.IF_MODIFIED_SINCE
    );

    @Override
    public PushBuilder newPushBuilder()
    {
        if (!isPushSupported())
            return null;

        HttpFields.Mutable fields = HttpFields.build(getHttpFields(), NOT_PUSHED_HEADERS);

        HttpField authField = getHttpFields().getField(HttpHeader.AUTHORIZATION);
        //TODO check what to do for digest etc etc
        if (authField != null && getUserPrincipal() != null && authField.getValue().startsWith("Basic"))
            fields.add(authField);

        String id;
        try
        {
            HttpSession session = getSession();
            if (session != null)
            {
                session.getLastAccessedTime(); // checks if session is valid
                id = session.getId();
            }
            else
                id = getRequestedSessionId();
        }
        catch (IllegalStateException e)
        {
            id = getRequestedSessionId();
        }

        Map<String, String> cookies = new HashMap<>();
        Cookie[] existingCookies = getCookies();
        if (existingCookies != null)
        {
            for (Cookie c: getCookies())
            {
                cookies.put(c.getName(), c.getValue());
            }
        }

        //Any Set-Cookies that were set on the response must be set as Cookies on the
        //PushBuilder, unless the max-age of the cookie is <= 0
        HttpFields responseFields = getResponse().getHttpFields();
        for (HttpField field : responseFields)
        {
            HttpHeader header = field.getHeader();
            if (header == HttpHeader.SET_COOKIE)
            {
                HttpCookie cookie = (field instanceof SetCookieHttpField)
                    ? ((SetCookieHttpField)field).getHttpCookie()
                    : new HttpCookie(field.getValue());

                if (cookie.getMaxAge() > 0)
                    cookies.put(cookie.getName(), cookie.getValue());
                else
                    cookies.remove(cookie.getName());
            }
        }

        if (!cookies.isEmpty())
        {
            StringBuilder buff = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet())
            {
                if (buff.length() > 0)
                    buff.append("; ");
                buff.append(entry.getKey()).append('=').append(entry.getValue());
            }
            fields.add(new HttpField(HttpHeader.COOKIE, buff.toString()));
        }

        PushBuilder builder = new PushBuilderImpl(this, fields, getMethod(), getQueryString(), id);
        builder.addHeader("referer", getRequestURL().toString());

        return builder;
    }

    public void addEventListener(final EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener)
            _requestAttributeListeners.add((ServletRequestAttributeListener)listener);
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    /**
     * Remember a session that this request has just entered.
     *
     * @param s the session
     */
    public void enterSession(HttpSession s)
    {
        if (!(s instanceof Session))
            return;

        if (_sessions == null)
            _sessions = new ArrayList<>();
        if (LOG.isDebugEnabled())
            LOG.debug("Request {} entering session={}", this, s);
        _sessions.add((Session)s);
    }

    /**
     * Complete this request's access to a session.
     *
     * @param session the session
     */
    private void leaveSession(Session session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Request {} leaving session {}", this, session);
        //try and scope to a request and context before leaving the session
        ServletContext ctx = session.getServletContext();
        ContextHandler handler = ContextHandler.getContextHandler(ctx);
        if (handler == null)
            session.getSessionHandler().complete(session);
        else
            handler.handle(this, () ->  session.getSessionHandler().complete(session));
    }

    /**
     * A response is being committed for a session,
     * potentially write the session out before the
     * client receives the response.
     *
     * @param session the session
     */
    private void commitSession(Session session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Response {} committing for session {}", this, session);
        
        //try and scope to a request and context before committing the session
        ServletContext ctx = session.getServletContext();
        ContextHandler handler = ContextHandler.getContextHandler(ctx);
        if (handler == null)
            session.getSessionHandler().commit(session);
        else
            handler.handle(this, () -> session.getSessionHandler().commit(session));
    }

    private MultiMap<String> getParameters()
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
                    extractContentParameters();
                }
                catch (IllegalStateException | IllegalArgumentException e)
                {
                    LOG.warn(e.toString());
                    throw new BadMessageException("Unable to parse form content", e);
                }
            }
        }

        // Extract query string parameters; these may be replaced by a forward()
        // and may have already been extracted by mergeQueryParameters().
        if (_queryParameters == null)
            extractQueryParameters();

        // Do parameters need to be combined?
        if (isNoParams(_queryParameters) || _queryParameters.size() == 0)
            _parameters = _contentParameters;
        else if (isNoParams(_contentParameters) || _contentParameters.size() == 0)
            _parameters = _queryParameters;
        else if (_parameters == null)
        {
            _parameters = new MultiMap<>();
            _parameters.addAllValues(_queryParameters);
            _parameters.addAllValues(_contentParameters);
        }

        // protect against calls to recycled requests (which is illegal, but
        // this gives better failures
        MultiMap<String> parameters = _parameters;
        return parameters == null ? NO_PARAMS : parameters;
    }

    private void extractQueryParameters()
    {
        if (_uri == null || StringUtil.isEmpty(_uri.getQuery()))
            _queryParameters = NO_PARAMS;
        else
        {
            try
            {
                _queryParameters = new MultiMap<>();
                UrlEncoded.decodeTo(_uri.getQuery(), _queryParameters, _queryEncoding);
            }
            catch (IllegalStateException | IllegalArgumentException e)
            {
                _queryParameters = BAD_PARAMS;
                throw new BadMessageException("Unable to parse URI query", e);
            }
        }
    }

    private boolean isContentEncodingSupported()
    {
        String contentEncoding = getHttpFields().get(HttpHeader.CONTENT_ENCODING);
        if (contentEncoding == null)
            return true;
        return HttpHeaderValue.IDENTITY.is(contentEncoding);
    }

    private void extractContentParameters()
    {
        String contentType = getContentType();
        if (contentType == null || contentType.isEmpty())
            _contentParameters = NO_PARAMS;
        else
        {
            _contentParameters = new MultiMap<>();
            int contentLength = getContentLength();
            if (contentLength != 0 && _inputState == INPUT_NONE)
            {
                String baseType = HttpField.valueParameters(contentType, null);
                if (MimeTypes.Type.FORM_ENCODED.is(baseType) &&
                    _channel.getHttpConfiguration().isFormEncodedMethod(getMethod()))
                {
                    if (_metaData != null && !isContentEncodingSupported())
                    {
                        throw new BadMessageException(HttpStatus.UNSUPPORTED_MEDIA_TYPE_415, "Unsupported Content-Encoding");
                    }

                    extractFormParameters(_contentParameters);
                }
                else if (MimeTypes.Type.MULTIPART_FORM_DATA.is(baseType) &&
                    getAttribute(__MULTIPART_CONFIG_ELEMENT) != null &&
                    _multiParts == null)
                {
                    try
                    {
                        if (_metaData != null && !isContentEncodingSupported())
                        {
                            throw new BadMessageException(HttpStatus.UNSUPPORTED_MEDIA_TYPE_415, "Unsupported Content-Encoding");
                        }
                        getParts(_contentParameters);
                    }
                    catch (IOException e)
                    {
                        String msg = "Unable to extract content parameters";
                        if (LOG.isDebugEnabled())
                            LOG.debug(msg, e);
                        throw new RuntimeIOException(msg, e);
                    }
                }
            }
        }
    }

    public void extractFormParameters(MultiMap<String> params)
    {
        try
        {
            int maxFormContentSize = ContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE;
            int maxFormKeys = ContextHandler.DEFAULT_MAX_FORM_KEYS;

            if (_context != null)
            {
                ContextHandler contextHandler = _context.getContextHandler();
                maxFormContentSize = contextHandler.getMaxFormContentSize();
                maxFormKeys = contextHandler.getMaxFormKeys();
            }
            else
            {
                maxFormContentSize = lookupServerAttribute(ContextHandler.MAX_FORM_CONTENT_SIZE_KEY, maxFormContentSize);
                maxFormKeys = lookupServerAttribute(ContextHandler.MAX_FORM_KEYS_KEY, maxFormKeys);
            }

            int contentLength = getContentLength();
            if (maxFormContentSize >= 0 && contentLength > maxFormContentSize)
                throw new IllegalStateException("Form is larger than max length " + maxFormContentSize);

            InputStream in = getInputStream();
            if (_input.isAsync())
                throw new IllegalStateException("Cannot extract parameters with async IO");

            UrlEncoded.decodeTo(in, params, getCharacterEncoding(), maxFormContentSize, maxFormKeys);
        }
        catch (IOException e)
        {
            String msg = "Unable to extract form parameters";
            if (LOG.isDebugEnabled())
                LOG.debug(msg, e);
            throw new RuntimeIOException(msg, e);
        }
    }

    private int lookupServerAttribute(String key, int dftValue)
    {
        Object attribute = _channel.getServer().getAttribute(key);
        if (attribute instanceof Number)
            return ((Number)attribute).intValue();
        else if (attribute instanceof String)
            return Integer.parseInt((String)attribute);
        return dftValue;
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        HttpChannelState state = getHttpChannelState();
        if (_async == null || !state.isAsyncStarted())
            throw new IllegalStateException(state.getStatusString());

        return _async;
    }

    public HttpChannelState getHttpChannelState()
    {
        return _channel.getState();
    }

    public ComplianceViolation.Listener getComplianceViolationListener()
    {
        if (_channel instanceof ComplianceViolation.Listener)
        {
            return (ComplianceViolation.Listener)_channel;
        }

        ComplianceViolation.Listener listener = _channel.getConnector().getBean(ComplianceViolation.Listener.class);
        if (listener == null)
        {
            listener = _channel.getServer().getBean(ComplianceViolation.Listener.class);
        }
        return listener;
    }

    /**
     * Get Request Attribute.
     * <p>
     * Also supports jetty specific attributes to gain access to Jetty APIs:
     * <dl>
     * <dt>org.eclipse.jetty.server.Server</dt><dd>The Jetty Server instance</dd>
     * <dt>org.eclipse.jetty.server.HttpChannel</dt><dd>The HttpChannel for this request</dd>
     * <dt>org.eclipse.jetty.server.HttpConnection</dt><dd>The HttpConnection or null if another transport is used</dd>
     * </dl>
     * While these attributes may look like security problems, they are exposing nothing that is not already
     * available via reflection from a Request instance.
     *
     * @see javax.servlet.ServletRequest#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        if (name.startsWith("org.eclipse.jetty"))
        {
            if (Server.class.getName().equals(name))
                return _channel.getServer();
            if (HttpChannel.class.getName().equals(name))
                return _channel;
            if (HttpConnection.class.getName().equals(name) &&
                _channel.getHttpTransport() instanceof HttpConnection)
                return _channel.getHttpTransport();
        }
        return (_attributes == null) ? null : _attributes.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        if (_attributes == null)
            return Collections.enumeration(Collections.emptyList());

        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    public Attributes getAttributes()
    {
        if (_attributes == null)
            _attributes = new ServletAttributes();
        return _attributes;
    }

    /**
     * Get the authentication.
     *
     * @return the authentication
     */
    public Authentication getAuthentication()
    {
        return _authentication;
    }

    @Override
    public String getAuthType()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getAuthMethod();
        return null;
    }

    @Override
    public String getCharacterEncoding()
    {
        if (_characterEncoding == null)
        {
            if (_context != null)
                _characterEncoding = _context.getRequestCharacterEncoding();

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

    /**
     * @return Returns the connection.
     */
    public HttpChannel getHttpChannel()
    {
        return _channel;
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
        if (_httpFields == null)
            return -1;

        return _httpFields.getLongField(HttpHeader.CONTENT_LENGTH);
    }

    public long getContentRead()
    {
        return _input.getContentReceived();
    }

    @Override
    public String getContentType()
    {
        if (_contentType == null)
        {
            MetaData.Request metadata = _metaData;
            _contentType = metadata == null ? null : metadata.getFields().get(HttpHeader.CONTENT_TYPE);
        }
        return _contentType;
    }

    /**
     * @return The current {@link Context context} used for this request, or <code>null</code> if {@link #setContext} has not yet been called.
     */
    public Context getContext()
    {
        return _context;
    }

    /**
     * @return The current {@link Context context} used for this error handling for this request.  If the request is asynchronous,
     * then it is the context that called async. Otherwise it is the last non-null context passed to #setContext
     */
    public Context getErrorContext()
    {
        if (isAsyncStarted())
        {
            ContextHandler handler = _channel.getState().getContextHandler();
            if (handler != null)
                return handler.getServletContext();
        }

        return _errorContext;
    }

    @Override
    public String getContextPath()
    {
        // The context path returned is normally for the current context.  Except during a cross context
        // INCLUDE dispatch, in which case this method returns the context path of the source context,
        // which we recover from the IncludeAttributes wrapper.
        Context context;
        if (_dispatcherType == DispatcherType.INCLUDE)
        {
            Dispatcher.IncludeAttributes include = Attributes.unwrap(_attributes, Dispatcher.IncludeAttributes.class);
            context = (include == null) ? _context : include.getSourceContext();
        }
        else
        {
            context = _context;
        }

        if (context == null)
            return null;

        return context.getContextHandler().getRequestContextPath();
    }

    /** Get the path in the context.
     *
     * The path relative to the context path, analogous to {@link #getServletPath()} + {@link #getPathInfo()}.
     * If no context is set, then the path in context is the full path.
     * @return The decoded part of the {@link #getRequestURI()} path after any {@link #getContextPath()}
     *         up to any {@link #getQueryString()}, excluding path parameters.
     * @see #setContext(Context, String)
     */
    public String getPathInContext()
    {
        return _pathInContext;
    }

    @Override
    public Cookie[] getCookies()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null || _cookiesExtracted)
        {
            if (_cookies == null || _cookies.getCookies().length == 0)
                return null;

            return _cookies.getCookies();
        }

        _cookiesExtracted = true;

        for (HttpField field : metadata.getFields())
        {
            if (field.getHeader() == HttpHeader.COOKIE)
            {
                if (_cookies == null)
                    _cookies = new Cookies(getHttpChannel().getHttpConfiguration().getRequestCookieCompliance(), getComplianceViolationListener());
                _cookies.addCookieField(field.getValue());
            }
        }

        //Javadoc for Request.getCookies() stipulates null for no cookies
        if (_cookies == null || _cookies.getCookies().length == 0)
            return null;

        return _cookies.getCookies();
    }

    @Override
    public long getDateHeader(String name)
    {
        HttpFields fields = _httpFields;
        return fields == null ? -1 : fields.getDateField(name);
    }

    @Override
    public DispatcherType getDispatcherType()
    {
        return _dispatcherType;
    }

    @Override
    public String getHeader(String name)
    {
        HttpFields fields = _httpFields;
        return fields == null ? null : fields.get(name);
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        HttpFields fields = _httpFields;
        return fields == null ? Collections.emptyEnumeration() : fields.getFieldNames();
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        HttpFields fields = _httpFields;
        if (fields == null)
            return Collections.emptyEnumeration();
        Enumeration<String> e = fields.getValues(name);
        if (e == null)
            return Collections.enumeration(Collections.emptyList());
        return e;
    }

    /**
     * @return Returns the inputState.
     */
    public int getInputState()
    {
        return _inputState;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        if (_inputState != INPUT_NONE && _inputState != INPUT_STREAM)
            throw new IllegalStateException("READER");
        _inputState = INPUT_STREAM;

        if (_channel.isExpecting100Continue())
            _channel.continue100(_input.available());

        return _input;
    }

    @Override
    public int getIntHeader(String name)
    {
        HttpFields fields = _httpFields;
        return fields == null ? -1 : (int)fields.getLongField(name);
    }

    @Override
    public Locale getLocale()
    {
        HttpFields fields = _httpFields;
        if (fields == null)
            return Locale.getDefault();

        List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // handle no locale
        if (acceptable.isEmpty())
            return Locale.getDefault();

        String language = acceptable.get(0);
        language = HttpField.stripParameters(language);
        String country = "";
        int dash = language.indexOf('-');
        if (dash > -1)
        {
            country = language.substring(dash + 1).trim();
            language = language.substring(0, dash).trim();
        }
        return new Locale(language, country);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        HttpFields fields = _httpFields;
        if (fields == null)
            return Collections.enumeration(__defaultLocale);

        List<String> acceptable = fields.getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // handle no locale
        if (acceptable.isEmpty())
            return Collections.enumeration(__defaultLocale);

        List<Locale> locales = acceptable.stream().map(language ->
        {
            language = HttpField.stripParameters(language);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0, dash).trim();
            }
            return new Locale(language, country);
        }).collect(Collectors.toList());

        return Collections.enumeration(locales);
    }

    @Override
    public String getLocalAddr()
    {
        if (_channel != null)
        {
            InetSocketAddress local = _channel.getLocalAddress();
            if (local == null)
                return "";
            InetAddress address = local.getAddress();
            String result = address == null
                ? local.getHostString()
                : address.getHostAddress();

            return formatAddrOrHost(result);
        }

        return "";
    }

    /*
     * @see javax.servlet.ServletRequest#getLocalName()
     */
    @Override
    public String getLocalName()
    {
        if (_channel != null)
        {
            String localName = _channel.getLocalName();
            return formatAddrOrHost(localName);
        }

        return ""; // not allowed to be null
    }

    @Override
    public int getLocalPort()
    {
        if (_channel != null)
        {
            int localPort = _channel.getLocalPort();
            if (localPort > 0)
                return localPort;
        }
        return 0;
    }

    @Override
    public String getMethod()
    {
        return _method;
    }

    @Override
    public String getParameter(String name)
    {
        return getParameters().getValue(name, 0);
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        return Collections.unmodifiableMap(getParameters().toStringArrayMap());
    }

    @Override
    public Enumeration<String> getParameterNames()
    {
        return Collections.enumeration(getParameters().keySet());
    }

    @Override
    public String[] getParameterValues(String name)
    {
        List<String> vals = getParameters().getValues(name);
        if (vals == null)
            return null;
        return vals.toArray(new String[0]);
    }

    public MultiMap<String> getQueryParameters()
    {
        return _queryParameters;
    }

    public void setQueryParameters(MultiMap<String> queryParameters)
    {
        _queryParameters = queryParameters;
    }

    public void setContentParameters(MultiMap<String> contentParameters)
    {
        _contentParameters = contentParameters;
    }

    public void resetParameters()
    {
        _parameters = null;
    }

    @Override
    public String getPathInfo()
    {
        // The pathInfo returned is normally for the current servlet.  Except during an
        // INCLUDE dispatch, in which case this method returns the pathInfo of the source servlet,
        // which we recover from the IncludeAttributes wrapper.
        ServletPathMapping mapping = findServletPathMapping();
        return mapping == null ? _pathInContext : mapping.getPathInfo();
    }

    @Override
    public String getPathTranslated()
    {
        String pathInfo = getPathInfo();
        if (pathInfo == null || _context == null)
            return null;
        return _context.getRealPath(pathInfo);
    }

    @Override
    public String getProtocol()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return null;
        HttpVersion version = metadata.getHttpVersion();
        if (version == null)
            return null;
        return version.toString();
    }

    /*
     * @see javax.servlet.ServletRequest#getProtocol()
     */
    public HttpVersion getHttpVersion()
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getHttpVersion();
    }

    public String getQueryEncoding()
    {
        return _queryEncoding == null ? null : _queryEncoding.name();
    }

    Charset getQueryCharset()
    {
        return _queryEncoding;
    }

    @Override
    public String getQueryString()
    {
        return _uri == null ? null : _uri.getQuery();
    }

    @Override
    public BufferedReader getReader() throws IOException
    {
        if (_inputState != INPUT_NONE && _inputState != INPUT_READER)
            throw new IllegalStateException("STREAMED");

        if (_inputState == INPUT_READER)
            return _reader;

        String encoding = getCharacterEncoding();
        if (encoding == null)
            encoding = StringUtil.__ISO_8859_1;

        if (_reader == null || !encoding.equalsIgnoreCase(_readerEncoding))
        {
            final ServletInputStream in = getInputStream();
            _readerEncoding = encoding;
            _reader = new BufferedReader(new InputStreamReader(in, encoding))
            {
                @Override
                public void close() throws IOException
                {
                    in.close();
                }
            };
        }
        _inputState = INPUT_READER;
        return _reader;
    }

    @Override
    @Deprecated(since = "Servlet API 2.1")
    public String getRealPath(String path)
    {
        if (_context == null)
            return null;
        return _context.getRealPath(path);
    }

    /**
     * Access the underlying Remote {@link InetSocketAddress} for this request.
     *
     * @return the remote {@link InetSocketAddress} for this request, or null if the request has no remote (see {@link ServletRequest#getRemoteAddr()} for
     * conditions that result in no remote address)
     */
    public InetSocketAddress getRemoteInetSocketAddress()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();

        return remote;
    }

    @Override
    public String getRemoteAddr()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();
        if (remote == null)
            return "";

        InetAddress address = remote.getAddress();
        String result = address == null
                ? remote.getHostString()
                : address.getHostAddress();

        return formatAddrOrHost(result);
    }

    @Override
    public String getRemoteHost()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();
        if (remote == null)
            return "";

        // We want the URI host, so add IPv6 brackets if necessary.
        return formatAddrOrHost(remote.getHostString());
    }

    @Override
    public int getRemotePort()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();
        return remote == null ? 0 : remote.getPort();
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
    public RequestDispatcher getRequestDispatcher(String path)
    {
        if (path == null || _context == null)
            return null;

        // handle relative path
        if (!path.startsWith("/"))
        {
            String relTo = _pathInContext;
            int slash = relTo.lastIndexOf("/");
            if (slash > 1)
                relTo = relTo.substring(0, slash + 1);
            else
                relTo = "/";
            path = URIUtil.addPaths(relTo, path);
        }

        return _context.getRequestDispatcher(path);
    }

    @Override
    public String getRequestedSessionId()
    {
        return _requestedSessionId;
    }

    @Override
    public String getRequestURI()
    {
        if (_uri == null)
            return null;
        if (HttpMethod.CONNECT.is(getMethod()))
            return _uri.getAuthority();
        else
            return _uri.getPath();
    }

    @Override
    public StringBuffer getRequestURL()
    {
        final StringBuffer url = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(url, getScheme(), getServerName(), getServerPort());
        // only add RequestURI if not a CONNECT method
        if (!HttpMethod.CONNECT.is(getMethod()))
        {
            String requestURI = getRequestURI();
            if (requestURI != null)
                url.append(requestURI);
        }
        return url;
    }

    public Response getResponse()
    {
        return _channel.getResponse();
    }

    /**
     * Reconstructs the URL the client used to make the request. The returned URL contains a protocol, server name, port number, and, but it does not include a
     * path.
     * <p>
     * Because this method returns a <code>StringBuffer</code>, not a string, you can modify the URL easily, for example, to append path and query parameters.
     *
     * This method is useful for creating redirect messages and for reporting errors.
     *
     * @return "scheme://host:port"
     */
    public StringBuilder getRootURL()
    {
        StringBuilder url = new StringBuilder(128);
        URIUtil.appendSchemeHostPort(url, getScheme(), getServerName(), getServerPort());
        return url;
    }

    @Override
    public String getScheme()
    {
        return _uri == null ? "http" : _uri.getScheme();
    }

    @Override
    public String getServerName()
    {
        if ((_uri != null) && StringUtil.isNotBlank(_uri.getAuthority()))
            return formatAddrOrHost(_uri.getHost());
        else
            return findServerName();
    }

    private String findServerName()
    {
        if (_channel != null)
        {
            HostPort serverAuthority = _channel.getServerAuthority();
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

        if ((_uri != null) && StringUtil.isNotBlank(_uri.getAuthority()))
            port = _uri.getPort();
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
        if (_channel != null)
        {
            HostPort serverAuthority = _channel.getServerAuthority();
            if (serverAuthority != null)
                return serverAuthority.getPort();
        }

        // Return host from connection
        return getLocalPort();
    }

    @Override
    public ServletContext getServletContext()
    {
        return _context;
    }

    public String getServletName()
    {
        if (_scope != null)
            return _scope.getName();
        return null;
    }

    @Override
    public String getServletPath()
    {
        // The servletPath returned is normally for the current servlet.  Except during an
        // INCLUDE dispatch, in which case this method returns the servletPath of the source servlet,
        // which we recover from the IncludeAttributes wrapper.
        ServletPathMapping mapping = findServletPathMapping();
        return mapping == null ? "" : mapping.getServletPath();
    }

    public ServletResponse getServletResponse()
    {
        return _channel.getResponse();
    }

    @Override
    public String changeSessionId()
    {
        HttpSession session = getSession(false);
        if (session == null)
            throw new IllegalStateException("No session");

        if (session instanceof Session)
        {
            Session s = ((Session)session);
            s.renewId(this);
            if (getRemoteUser() != null)
                s.setAttribute(Session.SESSION_CREATED_SECURE, Boolean.TRUE);
            if (s.isIdChanged() && _sessionHandler.isUsingCookies())
                _channel.getResponse().replaceCookie(_sessionHandler.getSessionCookie(s, getContextPath(), isSecure()));
        }

        return session.getId();
    }

    /**
     * Called when the request is fully finished being handled.
     * For every session in any context that the session has
     * accessed, ensure that the session is completed.
     */
    public void onCompleted()
    {
        HttpChannel httpChannel = getHttpChannel();
        // httpChannel can be null in some scenarios
        // it's not possible to use requestlog in those scenarios anyway.
        if (httpChannel != null)
        {
            RequestLog requestLog = httpChannel.getRequestLog();
            if (requestLog != null)
            {
                // Don't allow pulling more parameters from request body content
                _contentParamsExtracted = true;
                if (_contentParameters == null)
                    _contentParameters = NO_PARAMS;

                // Reset the status code to what was committed
                MetaData.Response committedResponse = getResponse().getCommittedMetaData();
                if (committedResponse != null)
                {
                    getResponse().setStatus(committedResponse.getStatus());
                    // TODO: Reset the response headers to what they were when committed
                }

                requestLog.log(this, getResponse());
            }
        }

        if (_sessions != null)
        {
            for (Session s:_sessions)
                leaveSession(s);
        }

        //Clean up any tmp files created by MultiPartInputStream
        if (_multiParts != null)
        {
            try
            {
                _multiParts.close();
            }
            catch (Throwable e)
            {
                LOG.warn("Errors deleting multipart tmp files", e);
            }
        }
    }

    /**
     * Called when a response is about to be committed, ie sent
     * back to the client
     */
    public void onResponseCommit()
    {
        if (_sessions != null)
        {
            for (Session s:_sessions)
            {
                commitSession(s);
            }
        }
    }

    /**
     * Find a session that this request has already entered for the
     * given SessionHandler
     *
     * @param sessionHandler the SessionHandler (ie context) to check
     * @return the session for the passed session handler or null
     */
    public HttpSession getSession(SessionHandler sessionHandler)
    {
        if (_sessions == null || _sessions.size() == 0 || sessionHandler == null)
            return null;

        HttpSession session = null;

        for (HttpSession s:_sessions)
        {
            Session ss = (Session)s;
            if (sessionHandler == ss.getSessionHandler())
            {
                session = s;
                if (ss.isValid())
                    return session;
            }
        }
        return session;
    }

    @Override
    public HttpSession getSession()
    {
        return getSession(true);
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        if (_session != null)
        {
            if (_sessionHandler != null && !_sessionHandler.isValid(_session))
                _session = null;
            else
                return _session;
        }

        if (!create)
            return null;

        if (getResponse().isCommitted())
            throw new IllegalStateException("Response is committed");

        if (_sessionHandler == null)
            throw new IllegalStateException("No SessionManager");

        _session = _sessionHandler.newHttpSession(this);
        if (_session == null)
            throw new IllegalStateException("Create session failed");

        HttpCookie cookie = _sessionHandler.getSessionCookie(_session, getContextPath(), isSecure());
        if (cookie != null)
            _channel.getResponse().replaceCookie(cookie);

        return _session;
    }

    /**
     * @return Returns the sessionManager.
     */
    public SessionHandler getSessionHandler()
    {
        return _sessionHandler;
    }

    /**
     * Get Request TimeStamp
     *
     * @return The time that the request was received.
     */
    public long getTimeStamp()
    {
        return _timeStamp;
    }

    public HttpURI getHttpURI()
    {
        return _uri;
    }

    public void setHttpURI(HttpURI uri)
    {
        if (_uri != null && !Objects.equals(_uri.getQuery(), uri.getQuery()) && _queryParameters != BAD_PARAMS)
            _parameters = _queryParameters = null;
        _uri = uri.asImmutable();
    }

    /**
     * @return Returns the original uri passed in metadata before customization/rewrite
     */
    public String getOriginalURI()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return null;
        HttpURI uri = metadata.getURI();
        if (uri == null)
            return null;
        return uri.isAbsolute() && metadata.getHttpVersion() == HttpVersion.HTTP_2
            ? uri.getPathQuery()
            : uri.toString();
    }

    public UserIdentity getUserIdentity()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getUserIdentity();
        return null;
    }

    /**
     * @return The resolved user Identity, which may be null if the {@link Authentication} is not {@link Authentication.User} (eg.
     * {@link Authentication.Deferred}).
     */
    public UserIdentity getResolvedUserIdentity()
    {
        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getUserIdentity();
        return null;
    }

    public UserIdentity.Scope getUserIdentityScope()
    {
        return _scope;
    }

    @Override
    public Principal getUserPrincipal()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
        {
            UserIdentity user = ((Authentication.User)_authentication).getUserIdentity();
            return user.getUserPrincipal();
        }

        return null;
    }

    public boolean isHandled()
    {
        return _handled;
    }

    @Override
    public boolean isAsyncStarted()
    {
        return getHttpChannelState().isAsyncStarted();
    }

    @Override
    public boolean isAsyncSupported()
    {
        return _asyncNotSupportedSource == null;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        return _requestedSessionId != null && _requestedSessionIdFromCookie;
    }

    @Override
    @Deprecated(since = "Servlet API 2.1")
    public boolean isRequestedSessionIdFromUrl()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        if (_requestedSessionId == null)
            return false;

        HttpSession session = getSession(false);
        return (session != null && _sessionHandler.getSessionIdManager().getId(_requestedSessionId).equals(_sessionHandler.getId(session)));
    }

    @Override
    public boolean isSecure()
    {
        return _secure;
    }

    public void setSecure(boolean secure)
    {
        _secure = secure;
    }

    @Override
    public boolean isUserInRole(String role)
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).isUserInRole(_scope, role);
        return false;
    }

    /**
     * @param request the Request metadata
     */
    public void setMetaData(MetaData.Request request)
    {
        if (_metaData == null && _input != null && _channel != null)
        {
            _input.reopen();
            _channel.getResponse().getHttpOutput().reopen();
        }
        _metaData = request;
        _method = request.getMethod();
        _httpFields = request.getFields();
        final HttpURI uri = request.getURI();
        UriCompliance compliance = null;
        if (uri.hasViolations())
        {
            compliance = _channel == null || _channel.getHttpConfiguration() == null ? null : _channel.getHttpConfiguration().getUriCompliance();
            String badMessage = UriCompliance.checkUriCompliance(compliance, uri);
            if (badMessage != null)
                throw new BadMessageException(badMessage);
        }

        if (uri.isAbsolute() && uri.hasAuthority() && uri.getPath() != null)
        {
            _uri = uri;
        }
        else
        {
            HttpURI.Mutable builder = HttpURI.build(uri);

            if (!uri.isAbsolute())
                builder.scheme(HttpScheme.HTTP.asString());

            if (uri.getPath() == null)
                builder.path("/");

            if (!uri.hasAuthority())
            {
                HttpField field = getHttpFields().getField(HttpHeader.HOST);
                if (field instanceof HostPortHttpField)
                {
                    HostPortHttpField authority = (HostPortHttpField)field;

                    builder.host(authority.getHost()).port(authority.getPort());
                }
                else
                {
                    builder.host(findServerName()).port(findServerPort());
                }
            }
            _uri = builder.asImmutable();
        }
        setSecure(HttpScheme.HTTPS.is(_uri.getScheme()));

        String encoded = _uri.getPath();
        String path;
        if (encoded == null)
            // TODO this is not really right for CONNECT
            path = _uri.isAbsolute() ? "/" : null;
        else if (encoded.startsWith("/"))
        {
            path = (encoded.length() == 1) ? "/" : _uri.getDecodedPath();
        }
        else if ("*".equals(encoded) || HttpMethod.CONNECT.is(getMethod()))
        {
            path = encoded;
        }
        else
        {
            path = null;
        }

        if (path == null || path.isEmpty())
        {
            _pathInContext = encoded == null ? "" : encoded;
            throw new BadMessageException(400, "Bad URI");
        }
        _pathInContext = path;
    }

    public org.eclipse.jetty.http.MetaData.Request getMetaData()
    {
        return _metaData;
    }

    public boolean hasMetaData()
    {
        return _metaData != null;
    }

    protected void recycle()
    {
        if (_context != null)
            throw new IllegalStateException("Request in context!");
        if (_reader != null && _inputState == INPUT_READER)
        {
            try
            {
                int r = _reader.read();
                while (r != -1)
                {
                    r = _reader.read();
                }
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
                _reader = null;
                _readerEncoding = null;
            }
        }

        getHttpChannelState().recycle();
        _requestAttributeListeners.clear();
        _input.recycle();
        _metaData = null;
        _httpFields = null;
        _trailers = null;
        _uri = null;
        _method = null;
        _pathInContext = null;
        _servletPathMapping = null;
        _asyncNotSupportedSource = null;
        _secure = false;
        _newContext = false;
        _cookiesExtracted = false;
        _handled = false;
        _contentParamsExtracted = false;
        _requestedSessionIdFromCookie = false;
        _attributes = Attributes.unwrap(_attributes);
        if (_attributes != null)
        {
            if (ServletAttributes.class.equals(_attributes.getClass()))
                _attributes.clearAttributes();
            else
                _attributes = null;
        }
        setAuthentication(Authentication.NOT_CHECKED);
        _contentType = null;
        _characterEncoding = null;
        _context = null;
        _errorContext = null;
        if (_cookies != null)
            _cookies.reset();
        _dispatcherType = null;
        _inputState = INPUT_NONE;
        // _reader can be reused
        // _readerEncoding can be reused
        _queryParameters = null;
        _contentParameters = null;
        _parameters = null;
        _queryEncoding = null;
        _remote = null;
        _requestedSessionId = null;
        _scope = null;
        _session = null;
        _sessionHandler = null;
        _timeStamp = 0;
        _multiParts = null;
        if (_async != null)
            _async.reset();
        _async = null;
        _sessions = null;
    }

    @Override
    public void removeAttribute(String name)
    {
        Object oldValue = _attributes == null ? null : _attributes.getAttribute(name);

        if (_attributes != null)
            _attributes.removeAttribute(name);

        if (oldValue != null && !_requestAttributeListeners.isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context, this, name, oldValue);
            for (ServletRequestAttributeListener listener : _requestAttributeListeners)
            {
                listener.attributeRemoved(event);
            }
        }
    }

    public void removeEventListener(final EventListener listener)
    {
        _requestAttributeListeners.remove(listener);
    }

    public void setAsyncSupported(boolean supported, Object source)
    {
        _asyncNotSupportedSource = supported ? null : (source == null ? "unknown" : source);
    }

    /**
     * Set a request attribute. if the attribute name is "org.eclipse.jetty.server.server.Request.queryEncoding" then the value is also passed in a call to
     * {@link #setQueryEncoding}.
     *
     * @see javax.servlet.ServletRequest#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object oldValue = _attributes == null ? null : _attributes.getAttribute(name);

        if ("org.eclipse.jetty.server.Request.queryEncoding".equals(name))
            setQueryEncoding(value == null ? null : value.toString());
        else if ("org.eclipse.jetty.server.sendContent".equals(name))
            LOG.warn("Deprecated: org.eclipse.jetty.server.sendContent");

        if (_attributes == null)
            _attributes = new ServletAttributes();
        _attributes.setAttribute(name, value);

        if (!_requestAttributeListeners.isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context, this, name, oldValue == null ? value : oldValue);
            for (ServletRequestAttributeListener l : _requestAttributeListeners)
            {
                if (oldValue == null)
                    l.attributeAdded(event);
                else if (value == null)
                    l.attributeRemoved(event);
                else
                    l.attributeReplaced(event);
            }
        }
    }

    /**
     * Set the attributes for the request.
     *
     * @param attributes The attributes, which must be a {@link org.eclipse.jetty.util.Attributes.Wrapper}
     *                   for which {@link Attributes#unwrap(Attributes)} will return the
     *                   original {@link ServletAttributes}.
     */
    public void setAttributes(Attributes attributes)
    {
        _attributes = attributes;
    }

    public void setAsyncAttributes()
    {
        // Return if we have been async dispatched before.
        if (getAttribute(AsyncContext.ASYNC_REQUEST_URI) != null)
            return;

        // Unwrap the _attributes to get the base attributes instance.
        Attributes baseAttributes;
        if (_attributes == null)
            baseAttributes = _attributes = new ServletAttributes();
        else
            baseAttributes = Attributes.unwrap(_attributes);

        // We cannot use a apply AsyncAttribute via #setAttributes as that
        // will wrap over any dispatch specific attribute wrappers (eg.
        // Dispatcher#ForwardAttributes).   Async attributes must persist
        // after the current dispatch, so they must be set under any other
        // wrappers.

        String fwdRequestURI = (String)getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (fwdRequestURI == null)
        {
            if (baseAttributes instanceof ServletAttributes)
            {
                // The baseAttributes map is our ServletAttributes, so we can set the async
                // attributes there, under any other wrappers.
                ((ServletAttributes)baseAttributes).setAsyncAttributes(getRequestURI(),
                    getContextPath(),
                    getPathInContext(),
                    getServletPathMapping(),
                    getQueryString());
            }
            else
            {
                // We cannot find our ServletAttributes instance, so just set directly and hope
                // whatever non jetty wrappers that have been applied will do the right thing.
                _attributes.setAttribute(AsyncContext.ASYNC_REQUEST_URI, getRequestURI());
                _attributes.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, getContextPath());
                _attributes.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, getServletPath());
                _attributes.setAttribute(AsyncContext.ASYNC_PATH_INFO, getPathInfo());
                _attributes.setAttribute(AsyncContext.ASYNC_QUERY_STRING, getQueryString());
                _attributes.setAttribute(AsyncContext.ASYNC_MAPPING, getHttpServletMapping());
            }
        }
        else
        {
            if (baseAttributes instanceof ServletAttributes)
            {
                // The baseAttributes map is our ServletAttributes, so we can set the async
                // attributes there, under any other wrappers.
                ((ServletAttributes)baseAttributes).setAsyncAttributes(fwdRequestURI,
                    (String)getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH),
                    (String)getAttribute(RequestDispatcher.FORWARD_PATH_INFO),
                    (ServletPathMapping)getAttribute(RequestDispatcher.FORWARD_MAPPING),
                    (String)getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
            }
            else
            {
                // We cannot find our ServletAttributes instance, so just set directly and hope
                // whatever non jetty wrappers that have been applied will do the right thing.
                _attributes.setAttribute(AsyncContext.ASYNC_REQUEST_URI, fwdRequestURI);
                _attributes.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH, getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
                _attributes.setAttribute(AsyncContext.ASYNC_SERVLET_PATH, getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
                _attributes.setAttribute(AsyncContext.ASYNC_PATH_INFO, getAttribute(RequestDispatcher.FORWARD_PATH_INFO));
                _attributes.setAttribute(AsyncContext.ASYNC_QUERY_STRING, getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
                _attributes.setAttribute(AsyncContext.ASYNC_MAPPING, getAttribute(RequestDispatcher.FORWARD_MAPPING));
            }
        }
    }

    /**
     * Set the authentication.
     *
     * @param authentication the authentication to set
     */
    public void setAuthentication(Authentication authentication)
    {
        _authentication = authentication;
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        if (_inputState != INPUT_NONE)
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

    /*
     * @see javax.servlet.ServletRequest#setCharacterEncoding(java.lang.String)
     */
    public void setCharacterEncodingUnchecked(String encoding)
    {
        _characterEncoding = encoding;
    }

    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
    public void setContentType(String contentType)
    {
        _contentType = contentType;
    }

    /**
     * Set request context and path in the context.
     *
     * @param context context object
     * @param pathInContext the part of the URI path that is withing the context.
     *                      For servlets, this is equal to servletPath + pathInfo
     */
    public void setContext(Context context, String pathInContext)
    {
        _newContext = _context != context;
        _context = context;
        _pathInContext = pathInContext;
        if (context != null)
            _errorContext = context;
    }

    /**
     * @return True if this is the first call of <code>takeNewContext()</code> since the last
     * {@link #setContext(org.eclipse.jetty.server.handler.ContextHandler.Context, String)} call.
     */
    public boolean takeNewContext()
    {
        boolean nc = _newContext;
        _newContext = false;
        return nc;
    }

    /**
     * @param cookies The cookies to set.
     */
    public void setCookies(Cookie[] cookies)
    {
        if (_cookies == null)
            _cookies = new Cookies(getHttpChannel().getHttpConfiguration().getRequestCookieCompliance(), getComplianceViolationListener());
        _cookies.setCookies(cookies);
    }

    public void setDispatcherType(DispatcherType type)
    {
        _dispatcherType = type;
    }

    public void setHandled(boolean h)
    {
        _handled = h;
    }

    /**
     * @param method The method to set.
     */
    public void setMethod(String method)
    {
        _method = method;
    }

    public boolean isHead()
    {
        return HttpMethod.HEAD.is(getMethod());
    }

    /**
     * Set the character encoding used for the query string. This call will effect the return of getQueryString and getParamaters. It must be called before any
     * getParameter methods.
     *
     * The request attribute "org.eclipse.jetty.server.Request.queryEncoding" may be set as an alternate method of calling setQueryEncoding.
     *
     * @param queryEncoding the URI query character encoding
     */
    public void setQueryEncoding(String queryEncoding)
    {
        _queryEncoding = Charset.forName(queryEncoding);
    }

    /**
     * @param addr The address to set.
     */
    public void setRemoteAddr(InetSocketAddress addr)
    {
        _remote = addr;
    }

    /**
     * @param requestedSessionId The requestedSessionId to set.
     */
    public void setRequestedSessionId(String requestedSessionId)
    {
        _requestedSessionId = requestedSessionId;
    }

    /**
     * @param requestedSessionIdCookie The requestedSessionIdCookie to set.
     */
    public void setRequestedSessionIdFromCookie(boolean requestedSessionIdCookie)
    {
        _requestedSessionIdFromCookie = requestedSessionIdCookie;
    }

    /**
     * @param session The session to set.
     */
    public void setSession(HttpSession session)
    {
        _session = session;
    }

    /**
     * @param sessionHandler The SessionHandler to set.
     */
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        _sessionHandler = sessionHandler;
    }

    public void setTimeStamp(long ts)
    {
        _timeStamp = ts;
    }

    public void setUserIdentityScope(UserIdentity.Scope scope)
    {
        _scope = scope;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (_asyncNotSupportedSource != null)
            throw new IllegalStateException("!asyncSupported: " + _asyncNotSupportedSource);
        return forceStartAsync();
    }

    private AsyncContextState forceStartAsync()
    {
        HttpChannelState state = getHttpChannelState();
        if (_async == null)
            _async = new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_context, _async, state, this, this, getResponse());
        state.startAsync(event);
        return _async;
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (_asyncNotSupportedSource != null)
            throw new IllegalStateException("!asyncSupported: " + _asyncNotSupportedSource);
        HttpChannelState state = getHttpChannelState();
        if (_async == null)
            _async = new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_context, _async, state, this, servletRequest, servletResponse, getHttpURI());
        event.setDispatchContext(getServletContext());
        state.startAsync(event);
        return _async;
    }

    public static HttpServletRequest unwrap(ServletRequest servletRequest)
    {
        if (servletRequest instanceof HttpServletRequestWrapper)
        {
            return (HttpServletRequestWrapper)servletRequest;
        }
        if (servletRequest instanceof ServletRequestWrapper)
        {
            return unwrap(((ServletRequestWrapper)servletRequest).getRequest());
        }
        return ((HttpServletRequest)servletRequest);
    }

    @Override
    public String toString()
    {
        return String.format("%s%s%s %s%s@%x",
            getClass().getSimpleName(),
            _handled ? "[" : "(",
            getMethod(),
            getHttpURI(),
            _handled ? "]" : ")",
            hashCode());
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        //if already authenticated, return true
        if (getUserPrincipal() != null && getRemoteUser() != null && getAuthType() != null)
            return true;

        //do the authentication
        if (_authentication instanceof Authentication.Deferred)
        {
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this, response));
        }

        //if the authentication did not succeed
        if (_authentication instanceof Authentication.Deferred)
            response.sendError(HttpStatus.UNAUTHORIZED_401);

        //if the authentication is incomplete, return false
        if (!(_authentication instanceof Authentication.ResponseSent))
            return false;

        //something has gone wrong
        throw new ServletException("Authentication failed");
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        getParts();
        return _multiParts.getPart(name);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        String contentType = getContentType();
        if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpField.valueParameters(contentType, null)))
            throw new ServletException("Unsupported Content-Type [" + contentType + "], expected [multipart/form-data]");
        return getParts(null);
    }

    private Collection<Part> getParts(MultiMap<String> params) throws IOException
    {
        if (_multiParts == null)
        {
            MultipartConfigElement config = (MultipartConfigElement)getAttribute(__MULTIPART_CONFIG_ELEMENT);
            if (config == null)
                throw new IllegalStateException("No multipart config for servlet");

            _multiParts = newMultiParts(config);
            Collection<Part> parts = _multiParts.getParts();

            String formCharset = null;
            Part charsetPart = _multiParts.getPart("_charset_");
            if (charsetPart != null)
            {
                try (InputStream is = charsetPart.getInputStream())
                {
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    IO.copy(is, os);
                    formCharset = new String(os.toByteArray(), StandardCharsets.UTF_8);
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

            ByteArrayOutputStream os = null;
            for (Part p : parts)
            {
                if (p.getSubmittedFileName() == null)
                {
                    // Servlet Spec 3.0 pg 23, parts without filename must be put into params.
                    String charset = null;
                    if (p.getContentType() != null)
                        charset = MimeTypes.getCharsetFromContentType(p.getContentType());

                    try (InputStream is = p.getInputStream())
                    {
                        if (os == null)
                            os = new ByteArrayOutputStream();
                        IO.copy(is, os);

                        String content = new String(os.toByteArray(), charset == null ? defaultCharset : Charset.forName(charset));
                        if (_contentParameters == null)
                            _contentParameters = params == null ? new MultiMap<>() : params;
                        _contentParameters.add(p.getName(), content);
                    }
                    os.reset();
                }
            }
        }

        return _multiParts.getParts();
    }

    private MultiParts newMultiParts(MultipartConfigElement config) throws IOException
    {
        MultiPartFormDataCompliance compliance = getHttpChannel().getHttpConfiguration().getMultipartFormDataCompliance();
        if (LOG.isDebugEnabled())
            LOG.debug("newMultiParts {} {}", compliance, this);

        switch (compliance)
        {
            case RFC7578:
                return new MultiParts.MultiPartsHttpParser(getInputStream(), getContentType(), config,
                    (_context != null ? (File)_context.getAttribute("javax.servlet.context.tempdir") : null), this);

            case LEGACY:
            default:
                return new MultiParts.MultiPartsUtilParser(getInputStream(), getContentType(), config,
                    (_context != null ? (File)_context.getAttribute("javax.servlet.context.tempdir") : null), this);
        }
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        if (_authentication instanceof Authentication.LoginAuthentication)
        {
            Authentication auth = ((Authentication.LoginAuthentication)_authentication).login(username, password, this);
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
            _authentication = ((Authentication.LogoutAuthentication)_authentication).logout(this);
    }

    public void mergeQueryParameters(String oldQuery, String newQuery)
    {
        MultiMap<String> newQueryParams = null;
        // Have to assume ENCODING because we can't know otherwise.
        if (newQuery != null)
        {
            newQueryParams = new MultiMap<>();
            UrlEncoded.decodeTo(newQuery, newQueryParams, UrlEncoded.ENCODING);
        }

        MultiMap<String> oldQueryParams = _queryParameters;
        if (oldQueryParams == null && oldQuery != null)
        {
            oldQueryParams = new MultiMap<>();
            try
            {
                UrlEncoded.decodeTo(oldQuery, oldQueryParams, getQueryCharset());
            }
            catch (Throwable th)
            {
                _queryParameters = BAD_PARAMS;
                throw new BadMessageException(400, "Bad query encoding", th);
            }
        }

        MultiMap<String> mergedQueryParams;
        if (newQueryParams == null || newQueryParams.size() == 0)
            mergedQueryParams = oldQueryParams == null ? NO_PARAMS : oldQueryParams;
        else if (oldQueryParams == null || oldQueryParams.size() == 0)
            mergedQueryParams = newQueryParams;
        else
        {
            // Parameters values are accumulated.
            mergedQueryParams = new MultiMap<>(newQueryParams);
            mergedQueryParams.addAllValues(oldQueryParams);
        }

        setQueryParameters(mergedQueryParams);
        resetParameters();
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        Response response = _channel.getResponse();
        if (response.getStatus() != HttpStatus.SWITCHING_PROTOCOLS_101)
            throw new IllegalStateException("Response status should be 101");
        if (response.getHeader("Upgrade") == null)
            throw new IllegalStateException("Missing Upgrade header");
        if (!"Upgrade".equalsIgnoreCase(response.getHeader("Connection")))
            throw new IllegalStateException("Invalid Connection header");
        if (response.isCommitted())
            throw new IllegalStateException("Cannot upgrade committed response");
        if (_metaData == null || _metaData.getHttpVersion() != HttpVersion.HTTP_1_1)
            throw new IllegalStateException("Only requests over HTTP/1.1 can be upgraded");

        ServletOutputStream outputStream = response.getOutputStream();
        ServletInputStream inputStream = getInputStream();
        HttpChannelOverHttp httpChannel11 = (HttpChannelOverHttp)_channel;
        HttpConnection httpConnection = (HttpConnection)_channel.getConnection();

        T upgradeHandler;
        try
        {
            upgradeHandler = handlerClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new ServletException("Unable to instantiate handler class", e);
        }

        httpChannel11.servletUpgrade(); // tell the HTTP 1.1 channel that it is now handling an upgraded servlet
        AsyncContext asyncContext = forceStartAsync(); // force the servlet in async mode

        outputStream.flush(); // commit the 101 response
        httpConnection.getGenerator().servletUpgrade(); // tell the generator it can send data as-is
        httpConnection.addEventListener(new Connection.Listener()
        {
            @Override
            public void onClosed(Connection connection)
            {
                try
                {
                    asyncContext.complete();
                }
                catch (Exception e)
                {
                    LOG.warn("error during upgrade AsyncContext complete", e);
                }
                try
                {
                    upgradeHandler.destroy();
                }
                catch (Exception e)
                {
                    LOG.warn("error during upgrade HttpUpgradeHandler destroy", e);
                }
            }

            @Override
            public void onOpened(Connection connection)
            {
            }
        });

        upgradeHandler.init(new WebConnection()
        {
            @Override
            public void close() throws Exception
            {
                try
                {
                    inputStream.close();
                }
                finally
                {
                    outputStream.close();
                }
            }

            @Override
            public ServletInputStream getInputStream()
            {
                return inputStream;
            }

            @Override
            public ServletOutputStream getOutputStream()
            {
                return outputStream;
            }
        });
        return upgradeHandler;
    }

    /**
     * Set the servletPathMapping, the servletPath and the pathInfo.
     * @param servletPathMapping The mapping used to return from {@link #getHttpServletMapping()}
     */
    public void setServletPathMapping(ServletPathMapping servletPathMapping)
    {
        _servletPathMapping = servletPathMapping;
    }

    /**
     * @return The mapping for the current target servlet, regardless of dispatch type.
     */
    public ServletPathMapping getServletPathMapping()
    {
        return _servletPathMapping;
    }

    /**
     * @return The mapping for the target servlet reported by the {@link #getServletPath()} and
     * {@link #getPathInfo()} methods.  For {@link DispatcherType#INCLUDE} dispatches, this
     * method returns the mapping of the source servlet, otherwise it returns the mapping of
     * the target servlet.
     */
    ServletPathMapping findServletPathMapping()
    {
        ServletPathMapping mapping;
        if (_dispatcherType == DispatcherType.INCLUDE)
        {
            Dispatcher.IncludeAttributes include = Attributes.unwrap(_attributes, Dispatcher.IncludeAttributes.class);
            mapping = (include == null) ? _servletPathMapping : include.getSourceMapping();
        }
        else
        {
            mapping = _servletPathMapping;
        }
        return mapping;
    }

    @Override
    public HttpServletMapping getHttpServletMapping()
    {
        // TODO This is to pass the current TCK.  This has been challenged in https://github.com/eclipse-ee4j/jakartaee-tck/issues/585
        if (_dispatcherType == DispatcherType.ASYNC)
        {
            ServletPathMapping async = (ServletPathMapping)getAttribute(AsyncContext.ASYNC_MAPPING);
            if (async != null && "/DispatchServlet".equals(async.getServletPath()))
                return async;
        }

        // The mapping returned is normally for the current servlet.  Except during an
        // INCLUDE dispatch, in which case this method returns the mapping of the source servlet,
        // which we recover from the IncludeAttributes wrapper.
        return findServletPathMapping();
    }

    private String formatAddrOrHost(String name)
    {
        return _channel == null ? HostPort.normalizeHost(name) : _channel.formatAddrOrHost(name);
    }
}
