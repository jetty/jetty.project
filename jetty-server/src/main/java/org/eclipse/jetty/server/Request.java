//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

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
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.MultiPartInputStreamParser;
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
 * A request instance is created for each connection accepted by the server and recycled for each HTTP request received via that connection.
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
    public static final String __MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";
    public static final String __MULTIPART_INPUT_STREAM = "org.eclipse.jetty.multiPartInputStream";
    public static final String __MULTIPART_CONTEXT = "org.eclipse.jetty.multiPartContext";

    private static final Logger LOG = Log.getLogger(Request.class);
    private static final Collection<Locale> __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int __NONE = 0, _STREAM = 1, __READER = 2;

    private final HttpChannel<?> _channel;
    private final HttpFields _fields=new HttpFields();
    private final List<ServletRequestAttributeListener>  _requestAttributeListeners=new ArrayList<>();
    private final HttpInput<?> _input;
    
    public static class MultiPartCleanerListener implements ServletRequestListener
    {
        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            //Clean up any tmp files created by MultiPartInputStream
            MultiPartInputStreamParser mpis = (MultiPartInputStreamParser)sre.getServletRequest().getAttribute(__MULTIPART_INPUT_STREAM);
            if (mpis != null)
            {
                ContextHandler.Context context = (ContextHandler.Context)sre.getServletRequest().getAttribute(__MULTIPART_CONTEXT);

                //Only do the cleanup if we are exiting from the context in which a servlet parsed the multipart files
                if (context == sre.getServletContext())
                {
                    try
                    {
                        mpis.deleteParts();
                    }
                    catch (MultiException e)
                    {
                        sre.getServletContext().log("Errors deleting multipart tmp files", e);
                    }
                }
            }
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            //nothing to do, multipart config set up by ServletHolder.handle()
        }
        
    }
    
    

    private boolean _secure;
    private boolean _asyncSupported = true;
    private boolean _newContext;
    private boolean _cookiesExtracted = false;
    private boolean _handled = false;
    private boolean _paramsExtracted;
    private boolean _requestedSessionIdFromCookie = false;
    private volatile Attributes _attributes;
    private Authentication _authentication;
    private String _characterEncoding;
    private ContextHandler.Context _context;
    private String _contextPath;
    private CookieCutter _cookies;
    private DispatcherType _dispatcherType;
    private int _inputState = __NONE;
    private HttpMethod _httpMethod;
    private String _httpMethodString;
    private MultiMap<String> _queryParameters;
    private MultiMap<String> _contentParameters;
    private MultiMap<String> _parameters;
    private String _pathInfo;
    private int _port;
    private HttpVersion _httpVersion = HttpVersion.HTTP_1_1;
    private String _queryEncoding;
    private String _queryString;
    private BufferedReader _reader;
    private String _readerEncoding;
    private InetSocketAddress _remote;
    private String _requestedSessionId;
    private String _requestURI;
    private Map<Object, HttpSession> _savedNewSessions;
    private String _scheme = URIUtil.HTTP;
    private UserIdentity.Scope _scope;
    private String _serverName;
    private String _servletPath;
    private HttpSession _session;
    private SessionManager _sessionManager;
    private long _timeStamp;
    private HttpURI _uri;
    private MultiPartInputStreamParser _multiPartInputStream; //if the request is a multi-part mime
    private AsyncContextState _async;
    
    /* ------------------------------------------------------------ */
    public Request(HttpChannel<?> channel, HttpInput<?> input)
    {
        _channel = channel;
        _input = input;
    }

    /* ------------------------------------------------------------ */
    public HttpFields getHttpFields()
    {
        return _fields;
    }

    /* ------------------------------------------------------------ */
    public HttpInput<?> getHttpInput()
    {
        return _input;
    }

    /* ------------------------------------------------------------ */
    public void addEventListener(final EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener)
            _requestAttributeListeners.add((ServletRequestAttributeListener)listener);
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    public void extractParameters()
    {
        if (_paramsExtracted)
            return;

        _paramsExtracted = true;

        // Extract query string parameters; these may be replaced by a forward()
        // and may have already been extracted by mergeQueryParameters().
        if (_queryParameters == null)
            _queryParameters = extractQueryParameters();

        // Extract content parameters; these cannot be replaced by a forward()
        // once extracted and may have already been extracted by getParts() or
        // by a processing happening after a form-based authentication.
        if (_contentParameters == null)
            _contentParameters = extractContentParameters();

        _parameters = restoreParameters();
    }

    private MultiMap<String> extractQueryParameters()
    {
        MultiMap<String> result = new MultiMap<>();
        if (_uri != null && _uri.hasQuery())
        {
            if (_queryEncoding == null)
            {
                _uri.decodeQueryTo(result);
            }
            else
            {
                try
                {
                    _uri.decodeQueryTo(result, _queryEncoding);
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
        return result;
    }

    private MultiMap<String> extractContentParameters()
    {
        MultiMap<String> result = new MultiMap<>();

        String contentType = getContentType();
        if (contentType != null && !contentType.isEmpty())
        {
            contentType = HttpFields.valueParameters(contentType, null);
            int contentLength = getContentLength();
            if (contentLength != 0)
            {
                if (MimeTypes.Type.FORM_ENCODED.is(contentType) && _inputState == __NONE &&
                        (HttpMethod.POST.is(getMethod()) || HttpMethod.PUT.is(getMethod())))
                {
                    extractFormParameters(result);
                }
                else if (contentType.startsWith("multipart/form-data") &&
                        getAttribute(__MULTIPART_CONFIG_ELEMENT) != null &&
                        _multiPartInputStream == null)
                {
                    extractMultipartParameters(result);
                }
            }
        }

        return result;
    }

    public void extractFormParameters(MultiMap<String> params)
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

            if (maxFormContentSize < 0)
            {
                Object obj = _channel.getServer().getAttribute("org.eclipse.jetty.server.Request.maxFormContentSize");
                if (obj == null)
                    maxFormContentSize = 200000;
                else if (obj instanceof Number)
                {
                    Number size = (Number)obj;
                    maxFormContentSize = size.intValue();
                }
                else if (obj instanceof String)
                {
                    maxFormContentSize = Integer.valueOf((String)obj);
                }
            }

            if (maxFormKeys < 0)
            {
                Object obj = _channel.getServer().getAttribute("org.eclipse.jetty.server.Request.maxFormKeys");
                if (obj == null)
                    maxFormKeys = 1000;
                else if (obj instanceof Number)
                {
                    Number keys = (Number)obj;
                    maxFormKeys = keys.intValue();
                }
                else if (obj instanceof String)
                {
                    maxFormKeys = Integer.valueOf((String)obj);
                }
            }

            int contentLength = getContentLength();
            if (contentLength > maxFormContentSize && maxFormContentSize > 0)
            {
                throw new IllegalStateException("Form too large: " + contentLength + " > " + maxFormContentSize);
            }
            InputStream in = getInputStream();
            if (_input.isAsync())
                throw new IllegalStateException("Cannot extract parameters with async IO");

            UrlEncoded.decodeTo(in,params,getCharacterEncoding(),contentLength<0?maxFormContentSize:-1,maxFormKeys);
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn(e);
            else
                LOG.warn(e.toString());
        }
    }

    private void extractMultipartParameters(MultiMap<String> result)
    {
        try
        {
            getParts(result);
        }
        catch (IOException | ServletException e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn(e);
            else
                LOG.warn(e.toString());
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncContext getAsyncContext()
    {
        HttpChannelState state = getHttpChannelState();
        if (_async==null || state.isInitial() && !state.isAsync())
            throw new IllegalStateException(state.getStatusString());
        
        return _async;
    }

    /* ------------------------------------------------------------ */
    public HttpChannelState getHttpChannelState()
    {
        return _channel.getState();
    }

    /* ------------------------------------------------------------ */
    /**
     * Get Request Attribute.
     * <p>Also supports jetty specific attributes to gain access to Jetty APIs:
     * <dl>
     * <dt>org.eclipse.jetty.server.Server</dt><dd>The Jetty Server instance</dd>
     * <dt>org.eclipse.jetty.server.HttpChannel</dt><dd>The HttpChannel for this request</dd>
     * <dt>org.eclipse.jetty.server.HttpConnection</dt><dd>The HttpConnection or null if another transport is used</dd>
     * </dl>
     * While these attributes may look like security problems, they are exposing nothing that is not already
     * available via reflection from a Request instance.
     * </p>
     * @see javax.servlet.ServletRequest#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        if (name.startsWith("org.eclipse.jetty"))
        {
            if ("org.eclipse.jetty.server.Server".equals(name))
                return _channel.getServer();
            if ("org.eclipse.jetty.server.HttpChannel".equals(name))
                return _channel;
            if ("org.eclipse.jetty.server.HttpConnection".equals(name) &&
                _channel.getHttpTransport() instanceof HttpConnection)
                return _channel.getHttpTransport();
        }
        return (_attributes == null)?null:_attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        if (_attributes == null)
            return Collections.enumeration(Collections.<String>emptyList());

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
    public HttpChannel<?> getHttpChannel()
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
     * @see javax.servlet.ServletRequest.getContentLengthLong()
     */
    @Override
    public long getContentLengthLong()
    {
        return _fields.getLongField(HttpHeader.CONTENT_LENGTH.toString());
    }

    /* ------------------------------------------------------------ */
    public long getContentRead()
    {
        return _input.getContentRead();
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
        {
            if (_cookies == null || _cookies.getCookies().length == 0)
                return null;
            
            return _cookies.getCookies();
        }

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

        //Javadoc for Request.getCookies() stipulates null for no cookies
        if (_cookies == null || _cookies.getCookies().length == 0)
            return null;
        
        return _cookies.getCookies();
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
    public Enumeration<String> getHeaderNames()
    {
        return _fields.getFieldNames();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaders(java.lang.String)
     */
    @Override
    public Enumeration<String> getHeaders(String name)
    {
        Enumeration<String> e = _fields.getValues(name);
        if (e == null)
            return Collections.enumeration(Collections.<String>emptyList());
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

        if (_channel.isExpecting100Continue())
            _channel.continue100(_input.available());

        return _input;
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

        List<Locale> langs = new ArrayList<>();

        // convert to locals
        for (String language : acceptLanguage)
        {
            language = HttpFields.valueParameters(language, null);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0, dash).trim();
            }
            langs.add(new Locale(language, country));
        }

        if (langs.size() == 0)
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
        if (local==null)
            return "";
        InetAddress address = local.getAddress();
        if (address==null)
            return local.getHostString();
        return address.getHostAddress();
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
        return _httpMethodString;
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
        if (_parameters == null)
            _parameters = restoreParameters();
        return _parameters.getValue(name,0);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameterMap()
     */
    @Override
    public Map<String, String[]> getParameterMap()
    {
        if (!_paramsExtracted)
            extractParameters();
        if (_parameters == null)
            _parameters = restoreParameters();
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
        if (_parameters == null)
            _parameters = restoreParameters();
        return Collections.enumeration(_parameters.keySet());
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
        if (_parameters == null)
            _parameters = restoreParameters();
        List<String> vals = _parameters.getValues(name);
        if (vals == null)
            return null;
        return vals.toArray(new String[vals.size()]);
    }

    private MultiMap<String> restoreParameters()
    {
        MultiMap<String> result = new MultiMap<>();
        if (_queryParameters == null)
            _queryParameters = extractQueryParameters();
        result.addAllValues(_queryParameters);
        result.addAllValues(_contentParameters);
        return result;
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
    /**
     * Access the underlying Remote {@link InetSocketAddress} for this request.
     * 
     * @return the remote {@link InetSocketAddress} for this request, or null if the request has no remote (see {@link ServletRequest#getRemoteAddr()} for
     *         conditions that result in no remote address)
     */
    public InetSocketAddress getRemoteInetSocketAddress()
    {
        InetSocketAddress remote = _remote;
        if (remote == null)
            remote = _channel.getRemoteAddress();

        return remote;
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
        
        if (remote==null)
            return "";
        
        InetAddress address = remote.getAddress();
        if (address==null)
            return remote.getHostString();
        
        return address.getHostAddress();
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
        final StringBuffer url = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(url,getScheme(),getServerName(),getServerPort());
        url.append(getRequestURI());
        return url;
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
        StringBuilder url = new StringBuilder(128);
        URIUtil.appendSchemeHostPort(url,getScheme(),getServerName(),getServerPort());
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
        if (_serverName != null)
        {
            _port = _uri.getPort();
            return _serverName;
        }

        // Return host from header field
        String hostPort = _fields.getStringField(HttpHeader.HOST);
        
        _port=0;
        if (hostPort != null)
        {
            int len=hostPort.length();
            loop: for (int i = len; i-- > 0;)
            {
                char c2 = (char)(0xff & hostPort.charAt(i));
                switch (c2)
                {
                    case ']':
                        break loop;

                    case ':':
                        try
                        {
                            len=i;
                            _port = StringUtil.toInt(hostPort.substring(i+1));
                        }
                        catch (NumberFormatException e)
                        {
                            LOG.warn(e);
                            _serverName=hostPort;
                            _port=0;
                            return _serverName;
                        }
                        break loop;
                }
            }
            if (hostPort.charAt(0)=='[')
            {
                if (hostPort.charAt(len-1)!=']') 
                {
                    LOG.warn("Bad IPv6 "+hostPort);
                    _serverName=hostPort;
                    _port=0;
                    return _serverName;
                }
                _serverName = hostPort.substring(1,len-1);
            }
            else if (len==hostPort.length())
                _serverName=hostPort;
            else
                _serverName = hostPort.substring(0,len);

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
     * Add @override when 3.1 api is available
     */
    public String changeSessionId()
    {
        HttpSession session = getSession(false);
        if (session == null)
            throw new IllegalStateException("No session");

        if (session instanceof AbstractSession)
        {
            AbstractSession abstractSession =  ((AbstractSession)session);
            abstractSession.renewId(this);
            if (getRemoteUser() != null)
                abstractSession.setAttribute(AbstractSession.SESSION_KNOWN_ONLY_TO_AUTHENTICATED, Boolean.TRUE);
            if (abstractSession.isIdChanged())
                _channel.getResponse().addCookie(_sessionManager.getSessionCookie(abstractSession, getContextPath(), isSecure()));
        }

        return session.getId();
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
        
        if (getResponse().isCommitted())
            throw new IllegalStateException("Response is committed");

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
    public boolean isHandled()
    {
        return _handled;
    }

    @Override
    public boolean isAsyncStarted()
    {
       return getHttpChannelState().isAsyncStarted();
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
        return _secure;
    }

    /* ------------------------------------------------------------ */
    public void setSecure(boolean secure)
    {
        _secure=secure;
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
        if (_context != null)
            throw new IllegalStateException("Request in context!");
        
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

        _dispatcherType=null;
        setAuthentication(Authentication.NOT_CHECKED);
        getHttpChannelState().recycle();
        if (_async!=null)
            _async.reset();
        _async=null;
        _asyncSupported = true;
        _handled = false;
        if (_attributes != null)
            _attributes.clearAttributes();
        _characterEncoding = null;
        _contextPath = null;
        if (_cookies != null)
            _cookies.reset();
        _cookiesExtracted = false;
        _context = null;
        _newContext=false;
        _serverName = null;
        _httpMethod=null;
        _httpMethodString = null;
        _pathInfo = null;
        _port = 0;
        _httpVersion = HttpVersion.HTTP_1_1;
        _queryEncoding = null;
        _queryString = null;
        _requestedSessionId = null;
        _requestedSessionIdFromCookie = false;
        _secure=false;
        _session = null;
        _sessionManager = null;
        _requestURI = null;
        _scope = null;
        _scheme = URIUtil.HTTP;
        _servletPath = null;
        _timeStamp = 0;
        _uri = null;
        _queryParameters = null;
        _contentParameters = null;
        _parameters = null;
        _paramsExtracted = false;
        _inputState = __NONE;

        if (_savedNewSessions != null)
            _savedNewSessions.clear();
        _savedNewSessions=null;
        _multiPartInputStream = null;
        _remote=null;
        _fields.clear();
        _input.recycle();
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

        if (old_value != null && !_requestAttributeListeners.isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context,this,name,old_value);
            for (ServletRequestAttributeListener listener : _requestAttributeListeners)
                listener.attributeRemoved(event);
        }
    }

    /* ------------------------------------------------------------ */
    public void removeEventListener(final EventListener listener)
    {
        _requestAttributeListeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    public void saveNewSession(Object key, HttpSession session)
    {
        if (_savedNewSessions == null)
            _savedNewSessions = new HashMap<>();
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
     * {@link #setQueryEncoding}.
     *
     * @see javax.servlet.ServletRequest#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old_value = _attributes == null?null:_attributes.getAttribute(name);

        if ("org.eclipse.jetty.server.Request.queryEncoding".equals(name))
            setQueryEncoding(value == null?null:value.toString());
        else if ("org.eclipse.jetty.server.sendContent".equals(name))
            LOG.warn("Deprecated: org.eclipse.jetty.server.sendContent");
        
        if (_attributes == null)
            _attributes = new AttributesMap();
        _attributes.setAttribute(name,value);

        if (!_requestAttributeListeners.isEmpty())
        {
            final ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(_context,this,name,old_value == null?value:old_value);
            for (ServletRequestAttributeListener l : _requestAttributeListeners)
            {
                if (old_value == null)
                    l.attributeAdded(event);
                else if (value == null)
                    l.attributeRemoved(event);
                else
                    l.attributeReplaced(event);
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
        Response r=getResponse();
        if (_handled && r.getStatus()==0)
            r.setStatus(200);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param method
     *            The method to set.
     */
    public void setMethod(HttpMethod httpMethod, String method)
    {
        _httpMethod=httpMethod;
        _httpMethodString = method;
    }

    /* ------------------------------------------------------------ */
    public boolean isHead()
    {
        return HttpMethod.HEAD==_httpMethod;
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
     * getParameter methods.
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
        _queryEncoding = null; //assume utf-8
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
    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        HttpChannelState state = getHttpChannelState();
        if (_async==null)
            _async=new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_context,_async,state,this,this,getResponse());
        state.startAsync(event);
        return _async;
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        HttpChannelState state = getHttpChannelState();
        if (_async==null)
            _async=new AsyncContextState(state);
        AsyncContextEvent event = new AsyncContextEvent(_context,_async,state,this,servletRequest,servletResponse);
        event.setDispatchContext(getServletContext());
        event.setDispatchPath(URIUtil.addPaths(getServletPath(),getPathInfo()));
        state.startAsync(event);
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
        getParts();

        return _multiPartInputStream.getPart(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        if (getContentType() == null || !getContentType().startsWith("multipart/form-data"))
            throw new ServletException("Content-Type != multipart/form-data");
        return getParts(null);
    }

    private Collection<Part> getParts(MultiMap<String> params) throws IOException, ServletException
    {
        if (_multiPartInputStream == null)
            _multiPartInputStream = (MultiPartInputStreamParser)getAttribute(__MULTIPART_INPUT_STREAM);

        if (_multiPartInputStream == null)
        {
            MultipartConfigElement config = (MultipartConfigElement)getAttribute(__MULTIPART_CONFIG_ELEMENT);
            
            if (config == null)
                throw new IllegalStateException("No multipart config for servlet");
            
            _multiPartInputStream = new MultiPartInputStreamParser(getInputStream(),
                                                             getContentType(), config, 
                                                             (_context != null?(File)_context.getAttribute("javax.servlet.context.tempdir"):null));
            
            setAttribute(__MULTIPART_INPUT_STREAM, _multiPartInputStream);
            setAttribute(__MULTIPART_CONTEXT, _context);
            Collection<Part> parts = _multiPartInputStream.getParts(); //causes parsing
            ByteArrayOutputStream os = null;
            for (Part p:parts)
            {
                MultiPartInputStreamParser.MultiPart mp = (MultiPartInputStreamParser.MultiPart)p;
                if (mp.getContentDispositionFilename() == null)
                {
                    // Servlet Spec 3.0 pg 23, parts without filename must be put into params.
                    String charset = null;
                    if (mp.getContentType() != null)
                        charset = MimeTypes.getCharsetFromContentType(mp.getContentType());

                    try (InputStream is = mp.getInputStream())
                    {
                        if (os == null)
                            os = new ByteArrayOutputStream();
                        IO.copy(is, os);
                        String content=new String(os.toByteArray(),charset==null?StandardCharsets.UTF_8:Charset.forName(charset));
                        if (_contentParameters == null)
                            _contentParameters = params == null ? new MultiMap<String>() : params;
                        _contentParameters.add(mp.getName(), content);
                    }
                    os.reset();
                }
            }
        }

        return _multiPartInputStream.getParts();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void login(String username, String password) throws ServletException
    {
        if (_authentication instanceof Authentication.Deferred)
        {
            _authentication=((Authentication.Deferred)_authentication).login(username,password,this);
            if (_authentication == null)
                throw new Authentication.Failed("Authentication failed for username '"+username+"'");
        }
        else
        {
            throw new Authentication.Failed("Authenticated failed for username '"+username+"'. Already authenticated as "+_authentication);
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

    public void mergeQueryParameters(String newQuery, boolean updateQueryString)
    {
        MultiMap<String> newQueryParams = new MultiMap<>();
        // Have to assume ENCODING because we can't know otherwise.
        UrlEncoded.decodeTo(newQuery, newQueryParams, UrlEncoded.ENCODING, -1);

        MultiMap<String> oldQueryParams = _queryParameters;
        if (oldQueryParams == null && _queryString != null)
        {
            oldQueryParams = new MultiMap<>();
            UrlEncoded.decodeTo(_queryString, oldQueryParams, getQueryEncoding(), -1);
        }

        MultiMap<String> mergedQueryParams = newQueryParams;
        if (oldQueryParams != null)
        {
            // Parameters values are accumulated.
            mergedQueryParams = new MultiMap<>(newQueryParams);
            mergedQueryParams.addAllValues(oldQueryParams);
        }

        setQueryParameters(mergedQueryParams);
        resetParameters();

        if (updateQueryString)
        {
            // Build the new merged query string, parameters in the
            // new query string hide parameters in the old query string.
            StringBuilder mergedQuery = new StringBuilder(newQuery);
            for (Map.Entry<String, List<String>> entry : mergedQueryParams.entrySet())
            {
                if (newQueryParams.containsKey(entry.getKey()))
                    continue;
                for (String value : entry.getValue())
                    mergedQuery.append("&").append(entry.getKey()).append("=").append(value);
            }

            setQueryString(mergedQuery.toString());
        }
    }

    /**
     * @see javax.servlet.http.HttpServletRequest#upgrade(java.lang.Class)
     */
    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        if (getContext() == null)
            throw new ServletException ("Unable to instantiate "+handlerClass);

        try
        {
            //Instantiate an instance and inject it
            T h = getContext().createInstance(handlerClass);
            
            //TODO handle the rest of the upgrade process
            
            return h;
        }
        catch (Exception e)
        {
            if (e instanceof ServletException)
                throw (ServletException)e;
            throw new ServletException(e);
        }
    }
}
