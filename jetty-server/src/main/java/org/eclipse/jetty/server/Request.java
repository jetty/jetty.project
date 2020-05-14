//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCookie;
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
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
 * <li>The {@link Request#getContextPath()} method will return null, until the request has been passed to a {@link ContextHandler} which matches the
 * {@link Request#getPathInfo()} with a context path and calls {@link Request#setContextPath(String)} as a result.</li>
 *
 * <li>the HTTP session methods will all return null sessions until such time as a request has been passed to a
 * {@link org.eclipse.jetty.server.session.SessionHandler} which checks for session cookies and enables the ability to create new sessions.</li>
 *
 * <li>The {@link Request#getServletPath()} method will return null until the request has been passed to a <code>org.eclipse.jetty.servlet.ServletHandler</code>
 * and the pathInfo matched against the servlet URL patterns and {@link Request#setServletPath(String)} called as a result.</li>
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
    public static final String MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";
    public static final String MULTIPARTS = "org.eclipse.jetty.multiParts";

    private static final Logger LOG = Log.getLogger(Request.class);
    private static final Collection<Locale> __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int INPUT_NONE = 0;
    private static final int INPUT_STREAM = 1;
    private static final int INPUT_READER = 2;

    private static final MultiMap<String> NO_PARAMS = new MultiMap<>();

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
    private String _originalURI;
    private String _contextPath;
    private String _servletPath;
    private String _pathInfo;
    private boolean _secure;
    private String _asyncNotSupportedSource = null;
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
    private CookieCutter _cookies;
    private DispatcherType _dispatcherType;
    private int _inputState = INPUT_NONE;
    private MultiMap<String> _queryParameters;
    private MultiMap<String> _contentParameters;
    private MultiMap<String> _parameters;
    private String _queryEncoding;
    private BufferedReader _reader;
    private String _readerEncoding;
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
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getFields();
    }

    public HttpFields getTrailers()
    {
        MetaData.Request metadata = _metaData;
        Supplier<HttpFields> trailers = metadata == null ? null : metadata.getTrailerSupplier();
        return trailers == null ? null : trailers.get();
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

    /**
     * Get a PushBuilder associated with this request initialized as follows:<ul>
     * <li>The method is initialized to "GET"</li>
     * <li>The headers from this request are copied to the Builder, except for:<ul>
     * <li>Conditional headers (eg. If-Modified-Since)
     * <li>Range headers
     * <li>Expect headers
     * <li>Authorization headers
     * <li>Referrer headers
     * </ul></li>
     * <li>If the request was Authenticated, an Authorization header will
     * be set with a container generated token that will result in equivalent
     * Authorization</li>
     * <li>The query string from {@link #getQueryString()}
     * <li>The {@link #getRequestedSessionId()} value, unless at the time
     * of the call {@link #getSession(boolean)}
     * has previously been called to create a new {@link HttpSession}, in
     * which case the new session ID will be used as the PushBuilders
     * requested session ID.</li>
     * <li>The source of the requested session id will be the same as for
     * this request</li>
     * <li>The builders Referer header will be set to {@link #getRequestURL()}
     * plus any {@link #getQueryString()} </li>
     * <li>If {@link HttpServletResponse#addCookie(Cookie)} has been called
     * on the associated response, then a corresponding Cookie header will be added
     * to the PushBuilder, unless the {@link Cookie#getMaxAge()} is &lt;=0, in which
     * case the Cookie will be removed from the builder.</li>
     * <li>If this request has has the conditional headers If-Modified-Since or
     * If-None-Match then the {@link PushBuilderImpl#isConditional()} header is set
     * to true.
     * </ul>
     *
     * <p>Each call to getPushBuilder() will return a new instance
     * of a PushBuilder based off this Request.  Any mutations to the
     * returned PushBuilder are not reflected on future returns.
     *
     * @return A new PushBuilder or null if push is not supported
     */
    public PushBuilder getPushBuilder()
    {
        if (!isPushSupported())
            return null;

        HttpFields fields = new HttpFields(getHttpFields().size() + 5);
        boolean conditional = false;

        for (HttpField field : getHttpFields())
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                fields.add(field);
            else
            {
                switch (header)
                {
                    case IF_MATCH:
                    case IF_RANGE:
                    case IF_UNMODIFIED_SINCE:
                    case RANGE:
                    case EXPECT:
                    case REFERER:
                    case COOKIE:
                        continue;

                    case AUTHORIZATION:
                        continue;

                    case IF_NONE_MATCH:
                    case IF_MODIFIED_SINCE:
                        conditional = true;
                        continue;

                    default:
                        fields.add(field);
                }
            }
        }

        String id = null;
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

        PushBuilder builder = new PushBuilderImpl(this, fields, getMethod(), getQueryString(), id, conditional);
        builder.addHeader("referer", getRequestURL().toString());

        // TODO process any set cookies
        // TODO process any user_identity

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
        session.getSessionHandler().complete(session);
    }

    /**
     * A response is being committed for a session, 
     * potentially write the session out before the
     * client receives the response.
     * @param session the session
     */
    private void commitSession(Session session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Response {} committing for session {}", this, session);
        session.getSessionHandler().commit(session);
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
                    throw new BadMessageException("Unable to parse form content", e);
                }
            }
        }

        // Extract query string parameters; these may be replaced by a forward()
        // and may have already been extracted by mergeQueryParameters().
        if (_queryParameters == null)
        {
            try
            {
                extractQueryParameters();
            }
            catch (IllegalStateException | IllegalArgumentException e)
            {
                throw new BadMessageException("Unable to parse URI query", e);
            }
        }

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
        MetaData.Request metadata = _metaData;
        if (metadata == null || metadata.getURI() == null || !metadata.getURI().hasQuery())
            _queryParameters = NO_PARAMS;
        else
        {
            _queryParameters = new MultiMap<>();
            if (_queryEncoding == null)
                metadata.getURI().decodeQueryTo(_queryParameters);
            else
            {
                try
                {
                    metadata.getURI().decodeQueryTo(_queryParameters, _queryEncoding);
                }
                catch (UnsupportedEncodingException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn(e);
                    else
                        LOG.warn(e.toString());
                }
            }
        }
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
                String baseType = HttpFields.valueParameters(contentType, null);
                if (MimeTypes.Type.FORM_ENCODED.is(baseType) &&
                    _channel.getHttpConfiguration().isFormEncodedMethod(getMethod()))
                {
                    if (_metaData != null)
                    {
                        String contentEncoding = getHttpFields().get(HttpHeader.CONTENT_ENCODING);
                        if (contentEncoding != null && !HttpHeaderValue.IDENTITY.is(contentEncoding))
                            throw new BadMessageException(HttpStatus.NOT_IMPLEMENTED_501, "Unsupported Content-Encoding");
                    }
                    extractFormParameters(_contentParameters);
                }
                else if (MimeTypes.Type.MULTIPART_FORM_DATA.is(baseType) &&
                    getAttribute(MULTIPART_CONFIG_ELEMENT) != null &&
                    _multiParts == null)
                {
                    try
                    {
                        if (_metaData != null && getHttpFields().contains(HttpHeader.CONTENT_ENCODING))
                            throw new BadMessageException(HttpStatus.NOT_IMPLEMENTED_501, "Unsupported Content-Encoding");
                        getParts(_contentParameters);
                    }
                    catch (IOException e)
                    {
                        LOG.debug(e);
                        throw new RuntimeIOException(e);
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
            LOG.debug(e);
            throw new RuntimeIOException(e);
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

    /*
     * @see javax.servlet.ServletRequest#getAttributeNames()
     */
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getAuthType()
     */
    @Override
    public String getAuthType()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getAuthMethod();
        return null;
    }

    /*
     * @see javax.servlet.ServletRequest#getCharacterEncoding()
     */
    @Override
    public String getCharacterEncoding()
    {
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
        return _characterEncoding;
    }

    /**
     * @return Returns the connection.
     */
    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    /*
     * @see javax.servlet.ServletRequest#getContentLength()
     */
    @Override
    public int getContentLength()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return -1;

        long contentLength = metadata.getContentLength();
        if (contentLength != Long.MIN_VALUE)
        {
            if (contentLength > Integer.MAX_VALUE)
            {
                // Per ServletRequest#getContentLength() javadoc this must return -1 for values exceeding Integer.MAX_VALUE
                return -1;
            }
            else
            {
                return (int)contentLength;
            }
        }
        return (int)metadata.getFields().getLongField(HttpHeader.CONTENT_LENGTH.asString());
    }

    /*
     * @see javax.servlet.ServletRequest.getContentLengthLong()
     */
    @Override
    public long getContentLengthLong()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return -1L;
        if (metadata.getContentLength() != Long.MIN_VALUE)
            return metadata.getContentLength();
        return metadata.getFields().getLongField(HttpHeader.CONTENT_LENGTH.asString());
    }

    public long getContentRead()
    {
        return _input.getContentConsumed();
    }

    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getContextPath()
     */
    @Override
    public String getContextPath()
    {
        return _contextPath;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getCookies()
     */
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
                    _cookies = new CookieCutter(getHttpChannel().getHttpConfiguration().getRequestCookieCompliance());
                _cookies.addCookieField(field.getValue());
            }
        }

        //Javadoc for Request.getCookies() stipulates null for no cookies
        if (_cookies == null || _cookies.getCookies().length == 0)
            return null;

        return _cookies.getCookies();
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getDateHeader(java.lang.String)
     */
    @Override
    public long getDateHeader(String name)
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? -1 : metadata.getFields().getDateField(name);
    }

    @Override
    public DispatcherType getDispatcherType()
    {
        return _dispatcherType;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getHeader(java.lang.String)
     */
    @Override
    public String getHeader(String name)
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getFields().get(name);
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaderNames()
     */
    @Override
    public Enumeration<String> getHeaderNames()
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? Collections.emptyEnumeration() : metadata.getFields().getFieldNames();
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaders(java.lang.String)
     */
    @Override
    public Enumeration<String> getHeaders(String name)
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return Collections.emptyEnumeration();
        Enumeration<String> e = metadata.getFields().getValues(name);
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

    /*
     * @see javax.servlet.ServletRequest#getInputStream()
     */
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getIntHeader(java.lang.String)
     */
    @Override
    public int getIntHeader(String name)
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? -1 : (int)metadata.getFields().getLongField(name);
    }

    /*
     * @see javax.servlet.ServletRequest#getLocale()
     */
    @Override
    public Locale getLocale()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return Locale.getDefault();

        List<String> acceptable = metadata.getFields().getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // handle no locale
        if (acceptable.isEmpty())
            return Locale.getDefault();

        String language = acceptable.get(0);
        language = HttpFields.stripParameters(language);
        String country = "";
        int dash = language.indexOf('-');
        if (dash > -1)
        {
            country = language.substring(dash + 1).trim();
            language = language.substring(0, dash).trim();
        }
        return new Locale(language, country);
    }

    /*
     * @see javax.servlet.ServletRequest#getLocales()
     */
    @Override
    public Enumeration<Locale> getLocales()
    {
        MetaData.Request metadata = _metaData;
        if (metadata == null)
            return Collections.enumeration(__defaultLocale);

        List<String> acceptable = metadata.getFields().getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);

        // handle no locale
        if (acceptable.isEmpty())
            return Collections.enumeration(__defaultLocale);

        List<Locale> locales = acceptable.stream().map(language ->
        {
            language = HttpFields.stripParameters(language);
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

    /*
     * @see javax.servlet.ServletRequest#getLocalAddr()
     */
    @Override
    public String getLocalAddr()
    {
        if (_channel == null)
        {
            try
            {
                String name = InetAddress.getLocalHost().getHostAddress();
                if (StringUtil.ALL_INTERFACES.equals(name))
                    return null;
                return name;
            }
            catch (java.net.UnknownHostException e)
            {
                LOG.ignore(e);
            }
        }

        InetSocketAddress local = _channel.getLocalAddress();
        if (local == null)
            return "";
        InetAddress address = local.getAddress();
        if (address == null)
            return local.getHostString();
        return address.getHostAddress();
    }

    /*
     * @see javax.servlet.ServletRequest#getLocalName()
     */
    @Override
    public String getLocalName()
    {
        if (_channel != null)
        {
            InetSocketAddress local = _channel.getLocalAddress();
            if (local != null)
                return local.getHostString();
        }

        try
        {
            String name = InetAddress.getLocalHost().getHostName();
            if (StringUtil.ALL_INTERFACES.equals(name))
                return null;
            return name;
        }
        catch (java.net.UnknownHostException e)
        {
            LOG.ignore(e);
        }
        return null;
    }

    /*
     * @see javax.servlet.ServletRequest#getLocalPort()
     */
    @Override
    public int getLocalPort()
    {
        if (_channel == null)
            return 0;
        InetSocketAddress local = _channel.getLocalAddress();
        return local == null ? 0 : local.getPort();
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getMethod()
     */
    @Override
    public String getMethod()
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getMethod();
    }

    /*
     * @see javax.servlet.ServletRequest#getParameter(java.lang.String)
     */
    @Override
    public String getParameter(String name)
    {
        return getParameters().getValue(name, 0);
    }

    /*
     * @see javax.servlet.ServletRequest#getParameterMap()
     */
    @Override
    public Map<String, String[]> getParameterMap()
    {
        return Collections.unmodifiableMap(getParameters().toStringArrayMap());
    }

    /*
     * @see javax.servlet.ServletRequest#getParameterNames()
     */
    @Override
    public Enumeration<String> getParameterNames()
    {
        return Collections.enumeration(getParameters().keySet());
    }

    /*
     * @see javax.servlet.ServletRequest#getParameterValues(java.lang.String)
     */
    @Override
    public String[] getParameterValues(String name)
    {
        List<String> vals = getParameters().getValues(name);
        if (vals == null)
            return null;
        return vals.toArray(new String[vals.size()]);
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getPathInfo()
     */
    @Override
    public String getPathInfo()
    {
        return _pathInfo;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getPathTranslated()
     */
    @Override
    public String getPathTranslated()
    {
        if (_pathInfo == null || _context == null)
            return null;
        return _context.getRealPath(_pathInfo);
    }

    /*
     * @see javax.servlet.ServletRequest#getProtocol()
     */
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
        return _queryEncoding;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getQueryString()
     */
    @Override
    public String getQueryString()
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getURI().getQuery();
    }

    /*
     * @see javax.servlet.ServletRequest#getReader()
     */
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

    /*
     * @see javax.servlet.ServletRequest#getRealPath(java.lang.String)
     */
    @Override
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

    /*
     * @see javax.servlet.ServletRequest#getRemoteAddr()
     */
    @Override
    public String getRemoteAddr()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();

        if (remote == null)
            return "";

        InetAddress address = remote.getAddress();
        if (address == null)
            return remote.getHostString();

        return address.getHostAddress();
    }

    /*
     * @see javax.servlet.ServletRequest#getRemoteHost()
     */
    @Override
    public String getRemoteHost()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();
        return remote == null ? "" : remote.getHostString();
    }

    /*
     * @see javax.servlet.ServletRequest#getRemotePort()
     */
    @Override
    public int getRemotePort()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();
        return remote == null ? 0 : remote.getPort();
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getRemoteUser()
     */
    @Override
    public String getRemoteUser()
    {
        Principal p = getUserPrincipal();
        if (p == null)
            return null;
        return p.getName();
    }

    /*
     * @see javax.servlet.ServletRequest#getRequestDispatcher(java.lang.String)
     */
    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        // path is encoded, potentially with query

        path = URIUtil.compactPath(path);

        if (path == null || _context == null)
            return null;

        // handle relative path
        if (!path.startsWith("/"))
        {
            String relTo = URIUtil.addPaths(_servletPath, _pathInfo);
            int slash = relTo.lastIndexOf("/");
            if (slash > 1)
                relTo = relTo.substring(0, slash + 1);
            else
                relTo = "/";
            path = URIUtil.addPaths(relTo, path);
        }

        return _context.getRequestDispatcher(path);
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestedSessionId()
     */
    @Override
    public String getRequestedSessionId()
    {
        return _requestedSessionId;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestURI()
     */
    @Override
    public String getRequestURI()
    {
        MetaData.Request metadata = _metaData;
        return (metadata == null) ? null : metadata.getURI().getPath();
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestURL()
     */
    @Override
    public StringBuffer getRequestURL()
    {
        final StringBuffer url = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(url, getScheme(), getServerName(), getServerPort());
        url.append(getRequestURI());
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

    /*
     * @see javax.servlet.ServletRequest#getScheme()
     */
    @Override
    public String getScheme()
    {
        MetaData.Request metadata = _metaData;
        String scheme = metadata == null ? null : metadata.getURI().getScheme();
        return scheme == null ? HttpScheme.HTTP.asString() : scheme;
    }

    /*
     * @see javax.servlet.ServletRequest#getServerName()
     */
    @Override
    public String getServerName()
    {
        MetaData.Request metadata = _metaData;
        String name = metadata == null ? null : metadata.getURI().getHost();

        // Return already determined host
        if (name != null)
            return name;

        return findServerName();
    }

    private String findServerName()
    {
        MetaData.Request metadata = _metaData;
        // Return host from header field
        HttpField host = metadata == null ? null : metadata.getFields().getField(HttpHeader.HOST);
        if (host != null)
        {
            if (!(host instanceof HostPortHttpField) && host.getValue() != null && !host.getValue().isEmpty())
                host = new HostPortHttpField(host.getValue());
            if (host instanceof HostPortHttpField)
            {
                HostPortHttpField authority = (HostPortHttpField)host;
                metadata.getURI().setAuthority(authority.getHost(), authority.getPort());
                return authority.getHost();
            }
        }

        // Return host from connection
        String name = getLocalName();
        if (name != null)
            return name;

        // Return the local host
        try
        {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (java.net.UnknownHostException e)
        {
            LOG.ignore(e);
        }
        return null;
    }

    /*
     * @see javax.servlet.ServletRequest#getServerPort()
     */
    @Override
    public int getServerPort()
    {
        MetaData.Request metadata = _metaData;
        HttpURI uri = metadata == null ? null : metadata.getURI();
        int port = (uri == null || uri.getHost() == null) ? findServerPort() : uri.getPort();

        // If no port specified, return the default port for the scheme
        if (port <= 0)
        {
            if (getScheme().equalsIgnoreCase(URIUtil.HTTPS))
                return 443;
            return 80;
        }

        // return a specific port
        return port;
    }

    private int findServerPort()
    {
        MetaData.Request metadata = _metaData;
        // Return host from header field
        HttpField host = metadata == null ? null : metadata.getFields().getField(HttpHeader.HOST);
        if (host != null)
        {
            // TODO is this needed now?
            HostPortHttpField authority = (host instanceof HostPortHttpField)
                ? ((HostPortHttpField)host)
                : new HostPortHttpField(host.getValue());
            metadata.getURI().setAuthority(authority.getHost(), authority.getPort());
            return authority.getPort();
        }

        // Return host from connection
        if (_channel != null)
            return getLocalPort();

        return -1;
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getServletPath()
     */
    @Override
    public String getServletPath()
    {
        if (_servletPath == null)
            _servletPath = "";
        return _servletPath;
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
        if (_sessions != null)
        {
            for (Session s:_sessions)
                leaveSession(s);
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
                commitSession(s);
        }
    }

    /**
     * Find a session that this request has already entered for the
     * given SessionHandler 
     *
     * @param sessionHandler the SessionHandler (ie context) to check
     * @return
     */
    public HttpSession getSession(SessionHandler sessionHandler)
    {
        if (_sessions == null || _sessions.size() == 0 || sessionHandler == null)
            return null;
        
        HttpSession session = null;
        
        for (HttpSession s:_sessions)
        {
            Session ss =  Session.class.cast(s);
            if (sessionHandler == ss.getSessionHandler())
            {
                session = s;
                if (ss.isValid())
                    return session;
            }
        }
        return session;
    }
    
    /*
     * @see javax.servlet.http.HttpServletRequest#getSession()
     */
    @Override
    public HttpSession getSession()
    {
        return getSession(true);
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#getSession(boolean)
     */
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

    /**
     * @return Returns the uri.
     */
    public HttpURI getHttpURI()
    {
        MetaData.Request metadata = _metaData;
        return metadata == null ? null : metadata.getURI();
    }

    /**
     * @return Returns the original uri passed in metadata before customization/rewrite
     */
    public String getOriginalURI()
    {
        return _originalURI;
    }

    /**
     * @param uri the URI to set
     */
    public void setHttpURI(HttpURI uri)
    {
        MetaData.Request metadata = _metaData;
        metadata.setURI(uri);
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

    /*
     * @see javax.servlet.http.HttpServletRequest#getUserPrincipal()
     */
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

    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromCookie()
     */
    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        return _requestedSessionId != null && _requestedSessionIdFromCookie;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromUrl()
     */
    @Override
    public boolean isRequestedSessionIdFromUrl()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromURL()
     */
    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdValid()
     */
    @Override
    public boolean isRequestedSessionIdValid()
    {
        if (_requestedSessionId == null)
            return false;

        HttpSession session = getSession(false);
        return (session != null && _sessionHandler.getSessionIdManager().getId(_requestedSessionId).equals(_sessionHandler.getId(session)));
    }

    /*
     * @see javax.servlet.ServletRequest#isSecure()
     */
    @Override
    public boolean isSecure()
    {
        return _secure;
    }

    public void setSecure(boolean secure)
    {
        _secure = secure;
    }

    /*
     * @see javax.servlet.http.HttpServletRequest#isUserInRole(java.lang.String)
     */
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
    public void setMetaData(org.eclipse.jetty.http.MetaData.Request request)
    {
        _metaData = request;

        setMethod(request.getMethod());
        HttpURI uri = request.getURI();
        _originalURI = uri.isAbsolute() && request.getHttpVersion() != HttpVersion.HTTP_2 ? uri.toString() : uri.getPathQuery();

        String encoded = uri.getPath();
        String path;
        if (encoded == null)
        {
            path = uri.isAbsolute() ? "/" : null;
            uri.setPath(path);
        }
        else if (encoded.startsWith("/"))
        {
            path = (encoded.length() == 1) ? "/" : URIUtil.canonicalPath(URIUtil.decodePath(encoded));
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
            setPathInfo(encoded == null ? "" : encoded);
            throw new BadMessageException(400, "Bad URI");
        }
        setPathInfo(path);
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
        _metaData = null;
        _originalURI = null;

        if (_context != null)
            throw new IllegalStateException("Request in context!");

        if (_inputState == INPUT_READER)
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
                LOG.ignore(e);
                _reader = null;
            }
        }

        _dispatcherType = null;
        setAuthentication(Authentication.NOT_CHECKED);
        getHttpChannelState().recycle();
        if (_async != null)
            _async.reset();
        _async = null;
        _asyncNotSupportedSource = null;
        _handled = false;
        _attributes = Attributes.unwrap(_attributes);
        if (_attributes != null)
        {
            if (ServletAttributes.class.equals(_attributes.getClass()))
                _attributes.clearAttributes();
            else
                _attributes = null;
        }
        _contentType = null;
        _characterEncoding = null;
        _contextPath = null;
        if (_cookies != null)
            _cookies.reset();
        _cookiesExtracted = false;
        _context = null;
        _newContext = false;
        _pathInfo = null;
        _queryEncoding = null;
        _requestedSessionId = null;
        _requestedSessionIdFromCookie = false;
        _secure = false;
        _session = null;
        _sessionHandler = null;
        _scope = null;
        _servletPath = null;
        _timeStamp = 0;
        _queryParameters = null;
        _contentParameters = null;
        _parameters = null;
        _contentParamsExtracted = false;
        _inputState = INPUT_NONE;
        _multiParts = null;
        _remote = null;
        _sessions = null;
        _input.recycle();
        _requestAttributeListeners.clear();
    }

    /*
     * @see javax.servlet.ServletRequest#removeAttribute(java.lang.String)
     */
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

    public void setAsyncSupported(boolean supported, String source)
    {
        _asyncNotSupportedSource = supported ? null : (source == null ? "unknown" : source);
    }

    /*
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

    public void setAttributes(Attributes attributes)
    {
        _attributes = attributes;
    }

    public void setAsyncAttributes()
    {
        // Return if we have been async dispatched before.
        if (getAttribute(AsyncContext.ASYNC_REQUEST_URI) != null)
            return;

        String requestURI;
        String contextPath;
        String servletPath;
        String pathInfo;
        String queryString;

        // Have we been forwarded before?
        requestURI = (String)getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (requestURI != null)
        {
            contextPath = (String)getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH);
            servletPath = (String)getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH);
            pathInfo = (String)getAttribute(RequestDispatcher.FORWARD_PATH_INFO);
            queryString = (String)getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
        }
        else
        {
            requestURI = getRequestURI();
            contextPath = getContextPath();
            servletPath = getServletPath();
            pathInfo = getPathInfo();
            queryString = getQueryString();
        }

        // Unwrap the _attributes to get the base attributes instance.
        Attributes baseAttributes;
        if (_attributes == null)
            _attributes = baseAttributes = new ServletAttributes();
        else
            baseAttributes = Attributes.unwrap(_attributes);

        if (baseAttributes instanceof ServletAttributes)
        {
            // Set the AsyncAttributes on the ServletAttributes.
            ServletAttributes servletAttributes = (ServletAttributes)baseAttributes;
            servletAttributes.setAsyncAttributes(requestURI, contextPath, servletPath, pathInfo, queryString);
        }
        else
        {
            // If ServletAttributes has been replaced just set them on the top level Attributes.
            AsyncAttributes.applyAsyncAttributes(_attributes, requestURI, contextPath, servletPath, pathInfo, queryString);
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

    /*
     * @see javax.servlet.ServletRequest#setCharacterEncoding(java.lang.String)
     */
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
     * Set request context
     *
     * @param context context object
     */
    public void setContext(Context context)
    {
        _newContext = _context != context;
        if (context == null)
            _context = null;
        else
        {
            _context = context;
            _errorContext = context;
        }
    }

    /**
     * @return True if this is the first call of <code>takeNewContext()</code> since the last
     * {@link #setContext(org.eclipse.jetty.server.handler.ContextHandler.Context)} call.
     */
    public boolean takeNewContext()
    {
        boolean nc = _newContext;
        _newContext = false;
        return nc;
    }

    /**
     * Sets the "context path" for this request
     *
     * @param contextPath the context path for this request
     * @see HttpServletRequest#getContextPath()
     */
    public void setContextPath(String contextPath)
    {
        _contextPath = contextPath;
    }

    /**
     * @param cookies The cookies to set.
     */
    public void setCookies(Cookie[] cookies)
    {
        if (_cookies == null)
            _cookies = new CookieCutter(getHttpChannel().getHttpConfiguration().getRequestCookieCompliance());
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
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.setMethod(method);
    }

    public void setHttpVersion(HttpVersion version)
    {
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.setHttpVersion(version);
    }

    public boolean isHead()
    {
        MetaData.Request metadata = _metaData;
        return metadata != null && HttpMethod.HEAD.is(metadata.getMethod());
    }

    /**
     * @param pathInfo The pathInfo to set.
     */
    public void setPathInfo(String pathInfo)
    {
        _pathInfo = pathInfo;
    }

    /**
     * Set the character encoding used for the query string. This call will effect the return of getQueryString and getParamaters. It must be called before any
     * getParameter methods.
     *
     * The request attribute "org.eclipse.jetty.server.server.Request.queryEncoding" may be set as an alternate method of calling setQueryEncoding.
     *
     * @param queryEncoding the URI query character encoding
     */
    public void setQueryEncoding(String queryEncoding)
    {
        _queryEncoding = queryEncoding;
    }

    /**
     * @param queryString The queryString to set.
     */
    public void setQueryString(String queryString)
    {
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.getURI().setQuery(queryString);
        _queryEncoding = null; //assume utf-8
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

    public void setURIPathQuery(String requestURI)
    {
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.getURI().setPathQuery(requestURI);
    }

    /**
     * @param scheme The scheme to set.
     */
    public void setScheme(String scheme)
    {
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.getURI().setScheme(scheme);
    }

    /**
     * @param host The host to set.
     * @param port the port to set
     */
    public void setAuthority(String host, int port)
    {
        MetaData.Request metadata = _metaData;
        if (metadata != null)
            metadata.getURI().setAuthority(host, port);
    }

    /**
     * @param servletPath The servletPath to set.
     */
    public void setServletPath(String servletPath)
    {
        _servletPath = servletPath;
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
        AsyncContextEvent event = new AsyncContextEvent(_context, _async, state, this, servletRequest, servletResponse);
        event.setDispatchContext(getServletContext());

        String uri = unwrap(servletRequest).getRequestURI();
        if (_contextPath != null && uri.startsWith(_contextPath))
            uri = uri.substring(_contextPath.length());
        else
            // TODO probably need to strip encoded context from requestURI, but will do this for now:
            uri = URIUtil.encodePath(URIUtil.addPaths(getServletPath(), getPathInfo()));

        event.setDispatchPath(uri);
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
        if (_authentication instanceof Authentication.Deferred)
        {
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this, response));
            return !(_authentication instanceof Authentication.ResponseSent);
        }
        response.sendError(HttpStatus.UNAUTHORIZED_401);
        return false;
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
        if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpFields.valueParameters(contentType, null)))
            throw new ServletException("Unsupported Content-Type [" + contentType + "], expected [multipart/form-data]");
        return getParts(null);
    }

    private Collection<Part> getParts(MultiMap<String> params) throws IOException
    {
        if (_multiParts == null)
            _multiParts = (MultiParts)getAttribute(MULTIPARTS);

        if (_multiParts == null)
        {
            MultipartConfigElement config = (MultipartConfigElement)getAttribute(MULTIPART_CONFIG_ELEMENT);
            if (config == null)
                throw new IllegalStateException("No multipart config for servlet");

            _multiParts = newMultiParts(config);
            setAttribute(MULTIPARTS, _multiParts);
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

    public void mergeQueryParameters(String oldQuery, String newQuery, boolean updateQueryString)
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
                UrlEncoded.decodeTo(oldQuery, oldQueryParams, getQueryEncoding());
            }
            catch (Throwable ex)
            {
                throw new BadMessageException(400, "Bad query encoding", ex);
            }
        }

        MultiMap<String> mergedQueryParams;
        if (newQueryParams == null || newQueryParams.size() == 0)
            mergedQueryParams = oldQueryParams == null ? NO_PARAMS : oldQueryParams;
        else if (oldQueryParams == null || oldQueryParams.size() == 0)
            mergedQueryParams = newQueryParams == null ? NO_PARAMS : newQueryParams;
        else
        {
            // Parameters values are accumulated.
            mergedQueryParams = new MultiMap<>(newQueryParams);
            mergedQueryParams.addAllValues(oldQueryParams);
        }

        setQueryParameters(mergedQueryParams);
        resetParameters();

        if (updateQueryString)
        {
            if (newQuery == null)
                setQueryString(oldQuery);
            else if (oldQuery == null)
                setQueryString(newQuery);
            else
            {
                // Build the new merged query string, parameters in the
                // new query string hide parameters in the old query string.
                StringBuilder mergedQuery = new StringBuilder();
                if (newQuery != null)
                    mergedQuery.append(newQuery);
                for (Map.Entry<String, List<String>> entry : mergedQueryParams.entrySet())
                {
                    if (newQueryParams != null && newQueryParams.containsKey(entry.getKey()))
                        continue;
                    for (String value : entry.getValue())
                    {
                        if (mergedQuery.length() > 0)
                            mergedQuery.append("&");
                        URIUtil.encodePath(mergedQuery, entry.getKey());
                        mergedQuery.append('=');
                        URIUtil.encodePath(mergedQuery, value);
                    }
                }
                setQueryString(mergedQuery.toString());
            }
        }
    }

    /**
     * @see javax.servlet.http.HttpServletRequest#upgrade(java.lang.Class)
     */
    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        throw new ServletException("HttpServletRequest.upgrade() not supported in Jetty");
    }
}
