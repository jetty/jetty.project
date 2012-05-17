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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

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
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.MultiPartInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
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
 * A request instance is created for each {@link AbstractHttpConnection} accepted by the server and recycled for each HTTP request received via that connection.
 * An effort is made to avoid reparsing headers and cookies that are likely to be the same for requests from the same connection.
 *
 * <p>
 * The form content that a request can process is limited to protect from Denial of Service attacks. The size in bytes is limited by
 * {@link ContextHandler#getMaxFormContentSize()} or if there is no context then the "org.eclipse.jetty.server.Request.maxFormContentSize" {@link Server}
 * attribute. The number of parameters keys is limited by {@link ContextHandler#getMaxFormKeys()} or if there is no context then the
 * "org.eclipse.jetty.server.Request.maxFormKeys" {@link Server} attribute.
 *
 *
 */
public class Request implements HttpServletRequest
{
    public static final String __MULTIPART_CONFIG_ELEMENT = "org.eclipse.multipartConfig";
    private static final Logger LOG = Log.getLogger(Request.class);

    private static final Collection<Locale> __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int __NONE = 0, _STREAM = 1, __READER = 2;

    private final HttpChannel _channel;
    private HttpFields _fields;
    private final HttpChannelState _async;

    private boolean _asyncSupported = true;
    private volatile Attributes _attributes;
    private Authentication _authentication;
    private MultiMap<String> _baseParameters;
    private String _characterEncoding;
    private ContextHandler.Context _context;
    private boolean _newContext;
    private String _contextPath;
    private CookieCutter _cookies;
    private boolean _cookiesExtracted = false;
    private DispatcherType _dispatcherType;
    private boolean _handled = false;
    private int _inputState = __NONE;
    private HttpMethod _httpMethod;
    private String _method;
    private MultiMap<String> _parameters;
    private boolean _paramsExtracted;
    private String _pathInfo;
    private int _port;
    private HttpVersion _httpVersion = HttpVersion.HTTP_1_1;
    private String _queryEncoding;
    private String _queryString;
    private BufferedReader _reader;
    private String _readerEncoding;
    private InetSocketAddress _remote;
    private Object _requestAttributeListeners;
    private String _requestedSessionId;
    private boolean _requestedSessionIdFromCookie = false;
    private String _requestURI;
    private Map<Object, HttpSession> _savedNewSessions;
    private String _scheme = URIUtil.HTTP;
    private UserIdentity.Scope _scope;
    private String _serverName;
    private String _servletPath;
    private HttpSession _session;
    private SessionManager _sessionManager;
    private long _timeStamp;
    private long _dispatchTime;

    private HttpURI _uri;

    private MultiPartInputStream _multiPartInputStream; //if the request is a multi-part mime


    /* ------------------------------------------------------------ */
    public Request(HttpChannel channel)
    {
        _channel = channel;
        _async=channel.getState();
        _fields=_channel.getRequestFields();
    }
    
    /* ------------------------------------------------------------ */
    public void addEventListener(final EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener)
            _requestAttributeListeners = LazyList.add(_requestAttributeListeners,listener);
        if (listener instanceof ContinuationListener)
            throw new IllegalArgumentException(listener.getClass().toString());
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    /* ------------------------------------------------------------ */
    /**
     * Extract Parameters from query string and/or form _content.
     */
    public void extractParameters()
    {
        if (_baseParameters == null)
            _baseParameters = new MultiMap<String>(16);

        if (_paramsExtracted)
        {
            if (_parameters == null)
                _parameters = _baseParameters;
            return;
        }

        _paramsExtracted = true;

        try
        {
            // Handle query string
            if (_uri != null && _uri.hasQuery())
            {
                if (_queryEncoding == null)
                    _uri.decodeQueryTo(_baseParameters);
                else
                {
                    try
                    {
                        _uri.decodeQueryTo(_baseParameters,_queryEncoding);
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

            // handle any _content.
            String encoding = getCharacterEncoding();
            String content_type = getContentType();
            if (content_type != null && content_type.length() > 0)
            {
                content_type = HttpFields.valueParameters(content_type,null);

                if (MimeTypes.Type.FORM_ENCODED.is(content_type) && _inputState == __NONE
                        && (HttpMethod.POST.equals(getMethod()) || HttpMethod.PUT.equals(getMethod())))
                {
                    int content_length = getContentLength();
                    if (content_length != 0)
                    {
                        try
                        {
                            int maxFormContentSize = -1;
                            int maxFormKeys = -1;

                            if (_context != null)
                            {
                                maxFormContentSize = _context.getContextHandler().getMaxFormContentSize();
                                maxFormKeys = _context.getContextHandler().getMaxFormKeys();
                            }
                            else
                            {
                                Number size = (Number)_channel.getServer()
                                        .getAttribute("org.eclipse.jetty.server.Request.maxFormContentSize");
                                maxFormContentSize = size == null?200000:size.intValue();
                                Number keys = (Number)_channel.getServer().getAttribute("org.eclipse.jetty.server.Request.maxFormKeys");
                                maxFormKeys = keys == null?1000:keys.intValue();
                            }

                            if (content_length > maxFormContentSize && maxFormContentSize > 0)
                            {
                                throw new IllegalStateException("Form too large" + content_length + ">" + maxFormContentSize);
                            }
                            InputStream in = getInputStream();

                            // Add form params to query params
                            UrlEncoded.decodeTo(in,_baseParameters,encoding,content_length < 0?maxFormContentSize:-1,maxFormKeys);
                        }
                        catch (IOException e)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.warn(e);
                            else
                                LOG.warn(e.toString());
                        }
                    }
                }
            }

            if (_parameters == null)
                _parameters = _baseParameters;
            else if (_parameters != _baseParameters)
            {
                // Merge parameters (needed if parameters extracted after a forward).
                Iterator<?> iter = _baseParameters.entrySet().iterator();
                while (iter.hasNext())
                {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>)iter.next();
                    String name = (String)entry.getKey();
                    Object values = entry.getValue();
                    for (int i = 0; i < LazyList.size(values); i++)
                        _parameters.add(name,LazyList.get(values,i));
                }
            }
        }
        finally
        {
            // ensure params always set (even if empty) after extraction
            if (_parameters == null)
                _parameters = _baseParameters;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncContext getAsyncContext()
    {
        if (_async.isInitial() && !_async.isAsyncStarted())
            throw new IllegalStateException(_async.getStatusString());
        return _async;
    }

    /* ------------------------------------------------------------ */
    public HttpChannelState getAsyncContinuation()
    {
        return _async;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        Object attr = (_attributes == null)?null:_attributes.getAttribute(name);
        if (attr == null && Continuation.ATTRIBUTE.equals(name))
            return _async;
        return attr;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getAttributeNames()
     */
    @Override
    public Enumeration getAttributeNames()
    {
        if (_attributes == null)
            return Collections.enumeration(Collections.EMPTY_LIST);

        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public Attributes getAttributes()
    {
        if (_attributes == null)
            _attributes = new AttributesMap();
        return _attributes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the authentication.
     *
     * @return the authentication
     */
    public Authentication getAuthentication()
    {
        return _authentication;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getCharacterEncoding()
     */
    @Override
    public String getCharacterEncoding()
    {
        return _characterEncoding;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connection.
     */
    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentLength()
     */
    @Override
    public int getContentLength()
    {
        return (int)_fields.getLongField(HttpHeader.CONTENT_LENGTH.toString());
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
    @Override
    public String getContentType()
    {
        return _fields.getStringField(HttpHeader.CONTENT_TYPE);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The current {@link Context context} used for this request, or <code>null</code> if {@link #setContext} has not yet been called.
     */
    public Context getContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getContextPath()
     */
    @Override
    public String getContextPath()
    {
        return _contextPath;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getCookies()
     */
    @Override
    public Cookie[] getCookies()
    {
        if (_cookiesExtracted)
            return _cookies == null?null:_cookies.getCookies();

        _cookiesExtracted = true;

        Enumeration<?> enm = _fields.getValues(HttpHeader.COOKIE.toString());

        // Handle no cookies
        if (enm != null)
        {
            if (_cookies == null)
                _cookies = new CookieCutter();

            while (enm.hasMoreElements())
            {
                String c = (String)enm.nextElement();
                _cookies.addCookieField(c);
            }
        }

        return _cookies == null?null:_cookies.getCookies();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getDateHeader(java.lang.String)
     */
    @Override
    public long getDateHeader(String name)
    {
        return _fields.getDateField(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public DispatcherType getDispatcherType()
    {
        return _dispatcherType;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeader(java.lang.String)
     */
    @Override
    public String getHeader(String name)
    {
        return _fields.getStringField(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaderNames()
     */
    @Override
    public Enumeration getHeaderNames()
    {
        return _fields.getFieldNames();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaders(java.lang.String)
     */
    @Override
    public Enumeration getHeaders(String name)
    {
        Enumeration<?> e = _fields.getValues(name);
        if (e == null)
            return Collections.enumeration(Collections.EMPTY_LIST);
        return e;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the inputState.
     */
    public int getInputState()
    {
        return _inputState;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getInputStream()
     */
    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        if (_inputState != __NONE && _inputState != _STREAM)
            throw new IllegalStateException("READER");
        _inputState = _STREAM;
        return _channel.getInputStream();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getIntHeader(java.lang.String)
     */
    @Override
    public int getIntHeader(String name)
    {
        return (int)_fields.getLongField(name);
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocale()
     */
    @Override
    public Locale getLocale()
    {
        Enumeration<String> enm = _fields.getValues(HttpHeader.ACCEPT_LANGUAGE.toString(),HttpFields.__separators);

        // handle no locale
        if (enm == null || !enm.hasMoreElements())
            return Locale.getDefault();

        // sort the list in quality order
        List<?> acceptLanguage = HttpFields.qualityList(enm);
        if (acceptLanguage.size() == 0)
            return Locale.getDefault();

        int size = acceptLanguage.size();

        if (size > 0)
        {
            String language = (String)acceptLanguage.get(0);
            language = HttpFields.valueParameters(language,null);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0,dash).trim();
            }
            return new Locale(language,country);
        }

        return Locale.getDefault();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocales()
     */
    @Override
    public Enumeration<Locale> getLocales()
    {

        Enumeration<String> enm = _fields.getValues(HttpHeader.ACCEPT_LANGUAGE.toString(),HttpFields.__separators);

        // handle no locale
        if (enm == null || !enm.hasMoreElements())
            return Collections.enumeration(__defaultLocale);

        // sort the list in quality order
        List<String> acceptLanguage = HttpFields.qualityList(enm);

        if (acceptLanguage.size() == 0)
            return Collections.enumeration(__defaultLocale);

        List<Locale> langs = new ArrayList<Locale>();
        int size = acceptLanguage.size();

        // convert to locals
        for (int i = 0; i < size; i++)
        {
            String language = acceptLanguage.get(i);
            language = HttpFields.valueParameters(language,null);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0,dash).trim();
            }
            langs.add(new Locale(language,country));
        }

        if (LazyList.size(langs) == 0)
            return Collections.enumeration(__defaultLocale);

        return Collections.enumeration(langs);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalAddr()
     */
    @Override
    public String getLocalAddr()
    {
        InetSocketAddress local=_channel.getLocalAddress();
        return local.getAddress().getHostAddress();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalName()
     */
    @Override
    public String getLocalName()
    {
        InetSocketAddress local=_channel.getLocalAddress();
        return local.getHostString();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalPort()
     */
    @Override
    public int getLocalPort()
    {
        InetSocketAddress local=_channel.getLocalAddress();
        return local.getPort();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getMethod()
     */
    @Override
    public String getMethod()
    {
        return _method;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameter(java.lang.String)
     */
    @Override
    public String getParameter(String name)
    {
        if (!_paramsExtracted)
            extractParameters();
        return (String)_parameters.getValue(name,0);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameterMap()
     */
    @Override
    public Map getParameterMap()
    {
        if (!_paramsExtracted)
            extractParameters();

        return Collections.unmodifiableMap(_parameters.toStringArrayMap());
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameterNames()
     */
    @Override
    public Enumeration<String> getParameterNames()
    {
        if (!_paramsExtracted)
            extractParameters();
        return Collections.enumeration(_parameters.keySet());
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the parameters.
     */
    public MultiMap<String> getParameters()
    {
        return _parameters;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameterValues(java.lang.String)
     */
    @Override
    public String[] getParameterValues(String name)
    {
        if (!_paramsExtracted)
            extractParameters();
        List<Object> vals = _parameters.getValues(name);
        if (vals == null)
            return null;
        return vals.toArray(new String[vals.size()]);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getPathInfo()
     */
    @Override
    public String getPathInfo()
    {
        return _pathInfo;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getProtocol()
     */
    @Override
    public String getProtocol()
    {
        return _httpVersion.toString();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getProtocol()
     */
    public HttpVersion getHttpVersion()
    {
        return _httpVersion;
    }

    /* ------------------------------------------------------------ */
    public String getQueryEncoding()
    {
        return _queryEncoding;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getQueryString()
     */
    @Override
    public String getQueryString()
    {
        if (_queryString == null && _uri != null)
        {
            if (_queryEncoding == null)
                _queryString = _uri.getQuery();
            else
                _queryString = _uri.getQuery(_queryEncoding);
        }
        return _queryString;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getReader()
     */
    @Override
    public BufferedReader getReader() throws IOException
    {
        if (_inputState != __NONE && _inputState != __READER)
            throw new IllegalStateException("STREAMED");

        if (_inputState == __READER)
            return _reader;

        String encoding = getCharacterEncoding();
        if (encoding == null)
            encoding = StringUtil.__ISO_8859_1;

        if (_reader == null || !encoding.equalsIgnoreCase(_readerEncoding))
        {
            final ServletInputStream in = getInputStream();
            _readerEncoding = encoding;
            _reader = new BufferedReader(new InputStreamReader(in,encoding))
            {
                @Override
                public void close() throws IOException
                {
                    in.close();
                }
            };
        }
        _inputState = __READER;
        return _reader;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRemoteAddr()
     */
    @Override
    public String getRemoteAddr()
    {
        InetSocketAddress remote=_remote;
        if (remote==null)
            remote=_channel.getRemoteAddress();
        return remote==null?"":remote.getAddress().getHostAddress();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRemoteHost()
     */
    @Override
    public String getRemoteHost()
    {
        InetSocketAddress remote=_remote;
        if (remote==null)
            remote=_channel.getRemoteAddress();
        return remote==null?"":remote.getHostString();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRemotePort()
     */
    @Override
    public int getRemotePort()
    {
        InetSocketAddress remote=_remote;
        if (remote==null)
            remote=_channel.getRemoteAddress();
        return remote==null?0:remote.getPort();
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRequestDispatcher(java.lang.String)
     */
    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        if (path == null || _context == null)
            return null;

        // handle relative path
        if (!path.startsWith("/"))
        {
            String relTo = URIUtil.addPaths(_servletPath,_pathInfo);
            int slash = relTo.lastIndexOf("/");
            if (slash > 1)
                relTo = relTo.substring(0,slash + 1);
            else
                relTo = "/";
            path = URIUtil.addPaths(relTo,path);
        }

        return _context.getRequestDispatcher(path);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestedSessionId()
     */
    @Override
    public String getRequestedSessionId()
    {
        return _requestedSessionId;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestURI()
     */
    @Override
    public String getRequestURI()
    {
        if (_requestURI == null && _uri != null)
            _requestURI = _uri.getPathAndParam();
        return _requestURI;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestURL()
     */
    @Override
    public StringBuffer getRequestURL()
    {
        final StringBuffer url = new StringBuffer(48);
        synchronized (url)
        {
            String scheme = getScheme();
            int port = getServerPort();

            url.append(scheme);
            url.append("://");
            url.append(getServerName());
            if (_port > 0 && ((scheme.equalsIgnoreCase(URIUtil.HTTP) && port != 80) || (scheme.equalsIgnoreCase(URIUtil.HTTPS) && port != 443)))
            {
                url.append(':');
                url.append(_port);
            }

            url.append(getRequestURI());
            return url;
        }
    }

    /* ------------------------------------------------------------ */
    public Response getResponse()
    {
        return _channel.getResponse();
    }

    /* ------------------------------------------------------------ */
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
        StringBuilder url = new StringBuilder(48);
        String scheme = getScheme();
        int port = getServerPort();

        url.append(scheme);
        url.append("://");
        url.append(getServerName());

        if (port > 0 && ((scheme.equalsIgnoreCase("http") && port != 80) || (scheme.equalsIgnoreCase("https") && port != 443)))
        {
            url.append(':');
            url.append(port);
        }
        return url;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getScheme()
     */
    @Override
    public String getScheme()
    {
        return _scheme;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getServerName()
     */
    @Override
    public String getServerName()
    {
        // Return already determined host
        if (_serverName != null)
            return _serverName;

        if (_uri == null)
            throw new IllegalStateException("No uri");

        // Return host from absolute URI
        _serverName = _uri.getHost();
        _port = _uri.getPort();
        if (_serverName != null)
            return _serverName;

        // Return host from header field
        String hostPort = _fields.getStringField(HttpHeader.HOST);
        if (hostPort != null)
        {
            loop: for (int i = hostPort.length(); i-- > 0;)
            {
                char ch = (char)(0xff & hostPort.charAt(i));
                switch (ch)
                {
                    case ']':
                        break loop;

                    case ':':
                        _serverName = hostPort.substring(0,i);
                        try
                        {
                            _port = StringUtil.toInt(hostPort.substring(i+1));
                        }
                        catch (NumberFormatException e)
                        {
                            if (_channel != null)
                                _channel.sendError(HttpStatus.BAD_REQUEST_400,"Bad Host header",null,true);
                        }
                        return _serverName;
                }
            }

            if (_serverName == null || _port < 0)
            {
                _serverName = hostPort;
                _port = 0;
            }

            return _serverName;
        }

        // Return host from connection
        if (_channel != null)
        {
            _serverName = getLocalName();
            _port = getLocalPort();
            if (_serverName != null && !StringUtil.ALL_INTERFACES.equals(_serverName))
                return _serverName;
        }

        // Return the local host
        try
        {
            _serverName = InetAddress.getLocalHost().getHostAddress();
        }
        catch (java.net.UnknownHostException e)
        {
            LOG.ignore(e);
        }
        return _serverName;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getServerPort()
     */
    @Override
    public int getServerPort()
    {
        if (_port <= 0)
        {
            if (_serverName == null)
                getServerName();

            if (_port <= 0)
            {
                if (_serverName != null && _uri != null)
                    _port = _uri.getPort();
                else
                {
                    InetSocketAddress local = _channel.getLocalAddress();
                    _port = local == null?0:local.getPort();
                }
            }
        }

        if (_port <= 0)
        {
            if (getScheme().equalsIgnoreCase(URIUtil.HTTPS))
                return 443;
            return 80;
        }
        return _port;
    }

    /* ------------------------------------------------------------ */
    @Override
    public ServletContext getServletContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public String getServletName()
    {
        if (_scope != null)
            return _scope.getName();
        return null;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    public ServletResponse getServletResponse()
    {
        return _channel.getResponse();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getSession()
     */
    @Override
    public HttpSession getSession()
    {
        return getSession(true);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getSession(boolean)
     */
    @Override
    public HttpSession getSession(boolean create)
    {
        if (_session != null)
        {
            if (_sessionManager != null && !_sessionManager.isValid(_session))
                _session = null;
            else
                return _session;
        }

        if (!create)
            return null;

        if (_sessionManager == null)
            throw new IllegalStateException("No SessionManager");

        _session = _sessionManager.newHttpSession(this);
        HttpCookie cookie = _sessionManager.getSessionCookie(_session,getContextPath(),isSecure());
        if (cookie != null)
            _channel.getResponse().addCookie(cookie);

        return _session;
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
     * Get Request TimeStamp
     *
     * @return The time that the request was received.
     */
    public long getTimeStamp()
    {
        return _timeStamp;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the uri.
     */
    public HttpURI getUri()
    {
        return _uri;
    }

    /* ------------------------------------------------------------ */
    public UserIdentity getUserIdentity()
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getUserIdentity();
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The resolved user Identity, which may be null if the {@link Authentication} is not {@link Authentication.User} (eg.
     *         {@link Authentication.Deferred}).
     */
    public UserIdentity getResolvedUserIdentity()
    {
        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).getUserIdentity();
        return null;
    }

    /* ------------------------------------------------------------ */
    public UserIdentity.Scope getUserIdentityScope()
    {
        return _scope;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /**
     * Get timestamp of the request dispatch
     *
     * @return timestamp
     */
    public long getDispatchTime()
    {
        return _dispatchTime;
    }

    /* ------------------------------------------------------------ */
    public boolean isHandled()
    {
        return _handled;
    }

    @Override
    public boolean isAsyncStarted()
    {
       return _async.isAsyncStarted();
    }


    /* ------------------------------------------------------------ */
    @Override
    public boolean isAsyncSupported()
    {
        return _asyncSupported;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromCookie()
     */
    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        return _requestedSessionId != null && _requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromUrl()
     */
    @Override
    public boolean isRequestedSessionIdFromUrl()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromURL()
     */
    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdValid()
     */
    @Override
    public boolean isRequestedSessionIdValid()
    {
        if (_requestedSessionId == null)
            return false;

        HttpSession session = getSession(false);
        return (session != null && _sessionManager.getSessionIdManager().getClusterId(_requestedSessionId).equals(_sessionManager.getClusterId(session)));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#isSecure()
     */
    @Override
    public boolean isSecure()
    {
        return _channel.getHttpConnector().isConfidential(this);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isUserInRole(java.lang.String)
     */
    @Override
    public boolean isUserInRole(String role)
    {
        if (_authentication instanceof Authentication.Deferred)
            setAuthentication(((Authentication.Deferred)_authentication).authenticate(this));

        if (_authentication instanceof Authentication.User)
            return ((Authentication.User)_authentication).isUserInRole(_scope,role);
        return false;
    }

    /* ------------------------------------------------------------ */
    public HttpSession recoverNewSession(Object key)
    {
        if (_savedNewSessions == null)
            return null;
        return _savedNewSessions.get(key);
    }

    /* ------------------------------------------------------------ */
    protected void recycle()
    {
        if (_inputState == __READER)
        {
            try
            {
                int r = _reader.read();
                while (r != -1)
                    r = _reader.read();
            }
            catch (Exception e)
            {
                LOG.ignore(e);
                _reader = null;
            }
        }

        setAuthentication(Authentication.NOT_CHECKED);
        _async.recycle();
        _asyncSupported = true;
        _handled = false;
        if (_context != null)
            throw new IllegalStateException("Request in context!");
        if (_attributes != null)
            _attributes.clearAttributes();
        _characterEncoding = null;
        if (_cookies != null)
            _cookies.reset();
        _cookiesExtracted = false;
        _context = null;
        _serverName = null;
        _method = null;
        _pathInfo = null;
        _port = 0;
        _httpVersion = HttpVersion.HTTP_1_1;
        _queryEncoding = null;
        _queryString = null;
        _requestedSessionId = null;
        _requestedSessionIdFromCookie = false;
        _session = null;
        _sessionManager = null;
        _requestURI = null;
        _scope = null;
        _scheme = URIUtil.HTTP;
        _servletPath = null;
        _timeStamp = 0;
        _uri = null;
        if (_baseParameters != null)
            _baseParameters.clear();
        _parameters = null;
        _paramsExtracted = false;
        _inputState = __NONE;

        if (_savedNewSessions != null)
            _savedNewSessions.clear();
        _savedNewSessions=null;
        _multiPartInputStream = null;
        _remote=null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
        Object old_value = _attributes == null?null:_attributes.getAttribute(name);

        if (_attributes != null)
            _attributes.removeAttribute(name);

        if (old_value != null)
        {
            if (_requestAttributeListeners != null)
            {
                final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context,this,name,old_value);
                final int size = LazyList.size(_requestAttributeListeners);
                for (int i = 0; i < size; i++)
                {
                    final EventListener listener = (ServletRequestAttributeListener)LazyList.get(_requestAttributeListeners,i);
                    if (listener instanceof ServletRequestAttributeListener)
                    {
                        final ServletRequestAttributeListener l = (ServletRequestAttributeListener)listener;
                        l.attributeRemoved(event);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void removeEventListener(final EventListener listener)
    {
        _requestAttributeListeners = LazyList.remove(_requestAttributeListeners,listener);
    }

    /* ------------------------------------------------------------ */
    public void saveNewSession(Object key, HttpSession session)
    {
        if (_savedNewSessions == null)
            _savedNewSessions = new HashMap<Object, HttpSession>();
        _savedNewSessions.put(key,session);
    }

    /* ------------------------------------------------------------ */
    public void setAsyncSupported(boolean supported)
    {
        _asyncSupported = supported;
    }

    /* ------------------------------------------------------------ */
    /*
     * Set a request attribute. if the attribute name is "org.eclipse.jetty.server.server.Request.queryEncoding" then the value is also passed in a call to
     * {@link #setQueryEncoding}. <p> if the attribute name is "org.eclipse.jetty.server.server.ResponseBuffer", then the response buffer is flushed with @{link
     * #flushResponseBuffer} <p> if the attribute name is "org.eclipse.jetty.io.EndPoint.maxIdleTime", then the value is passed to the associated {@link
     * EndPoint#setMaxIdleTime}.
     *
     * @see javax.servlet.ServletRequest#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old_value = _attributes == null?null:_attributes.getAttribute(name);

        if (name.startsWith("org.eclipse.jetty."))
        {
            if ("org.eclipse.jetty.server.Request.queryEncoding".equals(name))
                setQueryEncoding(value == null?null:value.toString());
            else if ("org.eclipse.jetty.server.sendContent".equals(name))
            {
                try
                {
                    ((HttpChannel.Output)getServletResponse().getOutputStream()).sendContent(value);
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
            else if ("org.eclipse.jetty.server.ResponseBuffer".equals(name))
            {
                try
                {
                    final ByteBuffer byteBuffer = (ByteBuffer)value;
                    throw new IOException("not implemented");
                    //((HttpChannel.Output)getServletResponse().getOutputStream()).sendResponse(byteBuffer);

                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        if (_attributes == null)
            _attributes = new AttributesMap();
        _attributes.setAttribute(name,value);

        if (_requestAttributeListeners != null)
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context,this,name,old_value == null?value:old_value);
            final int size = LazyList.size(_requestAttributeListeners);
            for (int i = 0; i < size; i++)
            {
                final EventListener listener = (ServletRequestAttributeListener)LazyList.get(_requestAttributeListeners,i);
                if (listener instanceof ServletRequestAttributeListener)
                {
                    final ServletRequestAttributeListener l = (ServletRequestAttributeListener)listener;

                    if (old_value == null)
                        l.attributeAdded(event);
                    else if (value == null)
                        l.attributeRemoved(event);
                    else
                        l.attributeReplaced(event);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public void setAttributes(Attributes attributes)
    {
        _attributes = attributes;
    }

    /* ------------------------------------------------------------ */

    /* ------------------------------------------------------------ */
    /**
     * Set the authentication.
     *
     * @param authentication
     *            the authentication to set
     */
    public void setAuthentication(Authentication authentication)
    {
        _authentication = authentication;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#setCharacterEncoding(java.lang.String)
     */
    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        if (_inputState != __NONE)
            return;

        _characterEncoding = encoding;

        // check encoding is supported
        if (!StringUtil.isUTF8(encoding))
            Charset.forName(encoding);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#setCharacterEncoding(java.lang.String)
     */
    public void setCharacterEncodingUnchecked(String encoding)
    {
        _characterEncoding = encoding;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
    public void setContentType(String contentType)
    {
        _fields.put(HttpHeader.CONTENT_TYPE,contentType);

    }

    /* ------------------------------------------------------------ */
    /**
     * Set request context
     *
     * @param context
     *            context object
     */
    public void setContext(Context context)
    {
        _newContext = _context != context;
        _context = context;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if this is the first call of {@link #takeNewContext()} since the last
     *         {@link #setContext(org.eclipse.jetty.server.handler.ContextHandler.Context)} call.
     */
    public boolean takeNewContext()
    {
        boolean nc = _newContext;
        _newContext = false;
        return nc;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the "context path" for this request
     *
     * @see HttpServletRequest#getContextPath()
     */
    public void setContextPath(String contextPath)
    {
        _contextPath = contextPath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cookies
     *            The cookies to set.
     */
    public void setCookies(Cookie[] cookies)
    {
        if (_cookies == null)
            _cookies = new CookieCutter();
        _cookies.setCookies(cookies);
    }

    /* ------------------------------------------------------------ */
    public void setDispatcherType(DispatcherType type)
    {
        _dispatcherType = type;
    }

    /* ------------------------------------------------------------ */
    public void setHandled(boolean h)
    {
        _handled = h;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param method
     *            The method to set.
     */
    public void setMethod(HttpMethod httpMethod, String method)
    {
        _httpMethod=httpMethod;
        _method = method;
    }

    /* ------------------------------------------------------------ */
    public boolean isHead()
    {
        return HttpMethod.HEAD==_httpMethod;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param parameters
     *            The parameters to set.
     */
    public void setParameters(MultiMap<String> parameters)
    {
        _parameters = (parameters == null)?_baseParameters:parameters;
        if (_paramsExtracted && _parameters == null)
            throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param pathInfo
     *            The pathInfo to set.
     */
    public void setPathInfo(String pathInfo)
    {
        _pathInfo = pathInfo;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param version
     *            The protocol to set.
     */
    public void setHttpVersion(HttpVersion version)
    {
        _httpVersion = version;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the character encoding used for the query string. This call will effect the return of getQueryString and getParamaters. It must be called before any
     * geParameter methods.
     *
     * The request attribute "org.eclipse.jetty.server.server.Request.queryEncoding" may be set as an alternate method of calling setQueryEncoding.
     *
     * @param queryEncoding
     */
    public void setQueryEncoding(String queryEncoding)
    {
        _queryEncoding = queryEncoding;
        _queryString = null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param queryString
     *            The queryString to set.
     */
    public void setQueryString(String queryString)
    {
        _queryString = queryString;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param addr
     *            The address to set.
     */
    public void setRemoteAddr(InetSocketAddress addr)
    {
        _remote = addr;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param requestedSessionId
     *            The requestedSessionId to set.
     */
    public void setRequestedSessionId(String requestedSessionId)
    {
        _requestedSessionId = requestedSessionId;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param requestedSessionIdCookie
     *            The requestedSessionIdCookie to set.
     */
    public void setRequestedSessionIdFromCookie(boolean requestedSessionIdCookie)
    {
        _requestedSessionIdFromCookie = requestedSessionIdCookie;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param requestURI
     *            The requestURI to set.
     */
    public void setRequestURI(String requestURI)
    {
        _requestURI = requestURI;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param scheme
     *            The scheme to set.
     */
    public void setScheme(String scheme)
    {
        _scheme = scheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param host
     *            The host to set.
     */
    public void setServerName(String host)
    {
        _serverName = host;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param port
     *            The port to set.
     */
    public void setServerPort(int port)
    {
        _port = port;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletPath
     *            The servletPath to set.
     */
    public void setServletPath(String servletPath)
    {
        _servletPath = servletPath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param session
     *            The session to set.
     */
    public void setSession(HttpSession session)
    {
        _session = session;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sessionManager
     *            The sessionManager to set.
     */
    public void setSessionManager(SessionManager sessionManager)
    {
        _sessionManager = sessionManager;
    }

    /* ------------------------------------------------------------ */
    public void setTimeStamp(long ts)
    {
        _timeStamp = ts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param uri
     *            The uri to set.
     */
    public void setUri(HttpURI uri)
    {
        _uri = uri;
    }

    /* ------------------------------------------------------------ */
    public void setUserIdentityScope(UserIdentity.Scope scope)
    {
        _scope = scope;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set timetstamp of request dispatch
     *
     * @param value
     *            timestamp
     */
    public void setDispatchTime(long value)
    {
        _dispatchTime = value;
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        _async.suspend(_context,this,_channel.getResponse());
        return _async;
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        _async.suspend(_context,servletRequest,servletResponse);
        return _async;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return (_handled?"[":"(") + getMethod() + " " + _uri + (_handled?"]@":")@") + hashCode() + " " + super.toString();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        if (_authentication instanceof Authentication.Deferred)
        {
        	setAuthentication(((Authentication.Deferred)_authentication).authenticate(this,response));
            return !(_authentication instanceof Authentication.ResponseSent);
        }
        response.sendError(HttpStatus.UNAUTHORIZED_401);
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        if (getContentType() == null || !getContentType().startsWith("multipart/form-data"))
            return null;

        if (_multiPartInputStream == null)
        {
            _multiPartInputStream = new MultiPartInputStream(getInputStream(),
                                                             getContentType(),(MultipartConfigElement)getAttribute(__MULTIPART_CONFIG_ELEMENT),
                                                             (_context != null?(File)_context.getAttribute("javax.servlet.context.tempdir"):null));
        }
        return _multiPartInputStream.getPart(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        if (getContentType() == null || !getContentType().startsWith("multipart/form-data"))
            return Collections.emptyList();

        if (_multiPartInputStream == null)
        {
            _multiPartInputStream = new MultiPartInputStream(getInputStream(),
                                                             getContentType(),(MultipartConfigElement)getAttribute(__MULTIPART_CONFIG_ELEMENT),
                                                             (_context != null?(File)_context.getAttribute("javax.servlet.context.tempdir"):null));
        }
        return _multiPartInputStream.getParts();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void login(String username, String password) throws ServletException
    {
        if (_authentication instanceof Authentication.Deferred)
        {
            _authentication=((Authentication.Deferred)_authentication).login(username,password);
            if (_authentication == null)
                throw new ServletException();
        }
        else
        {
            throw new ServletException("Authenticated as "+_authentication);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void logout() throws ServletException
    {
        if (_authentication instanceof Authentication.User)
            ((Authentication.User)_authentication).logout();
        _authentication=Authentication.UNAUTHENTICATED;
    }

    /* ------------------------------------------------------------ */
    /**
     * Merge in a new query string. The query string is merged with the existing parameters and {@link #setParameters(MultiMap)} and
     * {@link #setQueryString(String)} are called with the result. The merge is according to the rules of the servlet dispatch forward method.
     *
     * @param query
     *            The query string to merge into the request.
     */
    public void mergeQueryString(String query)
    {
        // extract parameters from dispatch query
        MultiMap<String> parameters = new MultiMap<String>();
        UrlEncoded.decodeTo(query,parameters,getCharacterEncoding());

        boolean merge_old_query = false;

        // Have we evaluated parameters
        if (!_paramsExtracted)
            extractParameters();

        // Are there any existing parameters?
        if (_parameters != null && _parameters.size() > 0)
        {
            // Merge parameters; new parameters of the same name take precedence.
            Iterator<Entry<String, Object>> iter = _parameters.entrySet().iterator();
            while (iter.hasNext())
            {
                Map.Entry<String, Object> entry = iter.next();
                String name = entry.getKey();

                // If the names match, we will need to remake the query string
                if (parameters.containsKey(name))
                    merge_old_query = true;

                // Add the old values to the new parameter map
                Object values = entry.getValue();
                for (int i = 0; i < LazyList.size(values); i++)
                    parameters.add(name,LazyList.get(values,i));
            }
        }

        if (_queryString != null && _queryString.length() > 0)
        {
            if (merge_old_query)
            {
                StringBuilder overridden_query_string = new StringBuilder();
                MultiMap<String> overridden_old_query = new MultiMap<String>();
                UrlEncoded.decodeTo(_queryString,overridden_old_query,getCharacterEncoding());

                MultiMap<String> overridden_new_query = new MultiMap<String>();
                UrlEncoded.decodeTo(query,overridden_new_query,getCharacterEncoding());

                Iterator<Entry<String, Object>> iter = overridden_old_query.entrySet().iterator();
                while (iter.hasNext())
                {
                    Map.Entry<String, Object> entry = iter.next();
                    String name = entry.getKey();
                    if (!overridden_new_query.containsKey(name))
                    {
                        Object values = entry.getValue();
                        for (int i = 0; i < LazyList.size(values); i++)
                        {
                            overridden_query_string.append("&").append(name).append("=").append(LazyList.get(values,i));
                        }
                    }
                }

                query = query + overridden_query_string;
            }
            else
            {
                query = query + "&" + _queryString;
            }
        }

        setParameters(parameters);
        setQueryString(query);
    }
}
