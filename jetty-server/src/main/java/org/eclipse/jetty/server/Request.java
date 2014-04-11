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
import java.nio.ByteBuffer;
import java.security.Principal;
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
import javax.servlet.AsyncEvent;
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
import javax.servlet.http.Part;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
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
    public static final String __MULTIPART_INPUT_STREAM = "org.eclipse.multiPartInputStream";
    public static final String __MULTIPART_CONTEXT = "org.eclipse.multiPartContext";
    private static final Logger LOG = Log.getLogger(Request.class);

    private static final String __ASYNC_FWD = "org.eclipse.asyncfwd";
    private static final Collection __defaultLocale = Collections.singleton(Locale.getDefault());
    private static final int __NONE = 0, _STREAM = 1, __READER = 2;

    public static class MultiPartCleanerListener implements ServletRequestListener
    {

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            //Clean up any tmp files created by MultiPartInputStream
            MultiPartInputStream mpis = (MultiPartInputStream)sre.getServletRequest().getAttribute(__MULTIPART_INPUT_STREAM);
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
    
    
    /* ------------------------------------------------------------ */
    public static Request getRequest(HttpServletRequest request)
    {
        if (request instanceof Request)
            return (Request)request;

        return AbstractHttpConnection.getCurrentConnection().getRequest();
    }
    protected final AsyncContinuation _async = new AsyncContinuation();
    private boolean _asyncSupported = true;
    private volatile Attributes _attributes;
    private Authentication _authentication;
    private MultiMap<String> _baseParameters;
    private String _characterEncoding;
    protected AbstractHttpConnection _connection;
    private ContextHandler.Context _context;
    private boolean _newContext;
    private String _contextPath;
    private CookieCutter _cookies;
    private boolean _cookiesExtracted = false;
    private DispatcherType _dispatcherType;
    private boolean _dns = false;
    private EndPoint _endp;
    private boolean _handled = false;
    private int _inputState = __NONE;
    private String _method;
    private MultiMap<String> _parameters;
    private boolean _paramsExtracted;
    private String _pathInfo;
    private int _port;
    private String _protocol = HttpVersions.HTTP_1_1;
    private String _queryEncoding;
    private String _queryString;
    private BufferedReader _reader;
    private String _readerEncoding;
    private String _remoteAddr;
    private String _remoteHost;
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

    private Buffer _timeStampBuffer;
    private HttpURI _uri;
    
    private MultiPartInputStream _multiPartInputStream; //if the request is a multi-part mime
    
    /* ------------------------------------------------------------ */
    public Request()
    {
    }

    /* ------------------------------------------------------------ */
    public Request(AbstractHttpConnection connection)
    {
        setConnection(connection);
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
            _baseParameters = new MultiMap(16);

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

                if (MimeTypes.FORM_ENCODED.equalsIgnoreCase(content_type) && _inputState == __NONE
                        && (HttpMethods.POST.equals(getMethod()) || HttpMethods.PUT.equals(getMethod())))
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
                            
                            if (maxFormContentSize < 0)
                            {
                                Object obj = _connection.getConnector().getServer().getAttribute("org.eclipse.jetty.server.Request.maxFormContentSize");
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
                                Object obj = _connection.getConnector().getServer().getAttribute("org.eclipse.jetty.server.Request.maxFormKeys");
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

                            if (content_length > maxFormContentSize && maxFormContentSize > 0)
                            {
                                throw new IllegalStateException("Form too large " + content_length + ">" + maxFormContentSize);
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
                Iterator iter = _baseParameters.entrySet().iterator();
                while (iter.hasNext())
                {
                    Map.Entry entry = (Map.Entry)iter.next();
                    String name = (String)entry.getKey();
                    Object values = entry.getValue();
                    for (int i = 0; i < LazyList.size(values); i++)
                        _parameters.add(name,LazyList.get(values,i));
                }
            }

            if (content_type != null && content_type.length()>0 && content_type.startsWith("multipart/form-data") && getAttribute(__MULTIPART_CONFIG_ELEMENT)!=null)
            {
                try
                {
                    getParts();
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn(e);
                    else
                        LOG.warn(e.toString());
                }
                catch (ServletException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn(e);
                    else
                        LOG.warn(e.toString());
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
    public AsyncContext getAsyncContext()
    {
        if (_async.isInitial() && !_async.isAsyncStarted())
            throw new IllegalStateException(_async.getStatusString());
        return _async;
    }

    /* ------------------------------------------------------------ */
    public AsyncContinuation getAsyncContinuation()
    {
        return _async;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        if ("org.eclipse.jetty.io.EndPoint.maxIdleTime".equalsIgnoreCase(name))
            return new Long(getConnection().getEndPoint().getMaxIdleTime());

        Object attr = (_attributes == null)?null:_attributes.getAttribute(name);
        if (attr == null && Continuation.ATTRIBUTE.equals(name))
            return _async;
        return attr;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getAttributeNames()
     */
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
    public String getCharacterEncoding()
    {
        return _characterEncoding;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connection.
     */
    public AbstractHttpConnection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentLength()
     */
    public int getContentLength()
    {
        return (int)_connection.getRequestFields().getLongField(HttpHeaders.CONTENT_LENGTH_BUFFER);
    }

    public long getContentRead()
    {
        if (_connection == null || _connection.getParser() == null)
            return -1;

        return ((HttpParser)_connection.getParser()).getContentRead();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
    public String getContentType()
    {
        return _connection.getRequestFields().getStringField(HttpHeaders.CONTENT_TYPE_BUFFER);
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
    public String getContextPath()
    {
        return _contextPath;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getCookies()
     */
    public Cookie[] getCookies()
    {
        if (_cookiesExtracted)
            return _cookies == null?null:_cookies.getCookies();

        _cookiesExtracted = true;

        Enumeration enm = _connection.getRequestFields().getValues(HttpHeaders.COOKIE_BUFFER);

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
    public long getDateHeader(String name)
    {
        return _connection.getRequestFields().getDateField(name);
    }

    /* ------------------------------------------------------------ */
    public DispatcherType getDispatcherType()
    {
        return _dispatcherType;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeader(java.lang.String)
     */
    public String getHeader(String name)
    {
        return _connection.getRequestFields().getStringField(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaderNames()
     */
    public Enumeration getHeaderNames()
    {
        return _connection.getRequestFields().getFieldNames();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getHeaders(java.lang.String)
     */
    public Enumeration getHeaders(String name)
    {
        Enumeration e = _connection.getRequestFields().getValues(name);
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
    public ServletInputStream getInputStream() throws IOException
    {
        if (_inputState != __NONE && _inputState != _STREAM)
            throw new IllegalStateException("READER");
        _inputState = _STREAM;
        return _connection.getInputStream();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getIntHeader(java.lang.String)
     */
    public int getIntHeader(String name)
    {
        return (int)_connection.getRequestFields().getLongField(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalAddr()
     */
    public String getLocalAddr()
    {
        return _endp == null?null:_endp.getLocalAddr();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocale()
     */
    public Locale getLocale()
    {
        Enumeration enm = _connection.getRequestFields().getValues(HttpHeaders.ACCEPT_LANGUAGE,HttpFields.__separators);

        // handle no locale
        if (enm == null || !enm.hasMoreElements())
            return Locale.getDefault();

        // sort the list in quality order
        List acceptLanguage = HttpFields.qualityList(enm);
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
    public Enumeration getLocales()
    {

        Enumeration enm = _connection.getRequestFields().getValues(HttpHeaders.ACCEPT_LANGUAGE,HttpFields.__separators);

        // handle no locale
        if (enm == null || !enm.hasMoreElements())
            return Collections.enumeration(__defaultLocale);

        // sort the list in quality order
        List acceptLanguage = HttpFields.qualityList(enm);

        if (acceptLanguage.size() == 0)
            return Collections.enumeration(__defaultLocale);

        Object langs = null;
        int size = acceptLanguage.size();

        // convert to locals
        for (int i = 0; i < size; i++)
        {
            String language = (String)acceptLanguage.get(i);
            language = HttpFields.valueParameters(language,null);
            String country = "";
            int dash = language.indexOf('-');
            if (dash > -1)
            {
                country = language.substring(dash + 1).trim();
                language = language.substring(0,dash).trim();
            }
            langs = LazyList.ensureSize(langs,size);
            langs = LazyList.add(langs,new Locale(language,country));
        }

        if (LazyList.size(langs) == 0)
            return Collections.enumeration(__defaultLocale);

        return Collections.enumeration(LazyList.getList(langs));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalName()
     */
    public String getLocalName()
    {
        if (_endp == null)
            return null;
        if (_dns)
            return _endp.getLocalHost();

        String local = _endp.getLocalAddr();
        if (local != null && local.indexOf(':') >= 0)
            local = "[" + local + "]";
        return local;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getLocalPort()
     */
    public int getLocalPort()
    {
        return _endp == null?0:_endp.getLocalPort();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getMethod()
     */
    public String getMethod()
    {
        return _method;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getParameter(java.lang.String)
     */
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
    public Enumeration getParameterNames()
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
    public String getPathInfo()
    {
        return _pathInfo;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getPathTranslated()
     */
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
    public String getProtocol()
    {
        return _protocol;
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
    public String getRemoteAddr()
    {
        if (_remoteAddr != null)
            return _remoteAddr;
        return _endp == null?null:_endp.getRemoteAddr();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRemoteHost()
     */
    public String getRemoteHost()
    {
        if (_dns)
        {
            if (_remoteHost != null)
            {
                return _remoteHost;
            }
            return _endp == null?null:_endp.getRemoteHost();
        }
        return getRemoteAddr();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getRemotePort()
     */
    public int getRemotePort()
    {
        return _endp == null?0:_endp.getRemotePort();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getRemoteUser()
     */
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
    public String getRequestedSessionId()
    {
        return _requestedSessionId;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getRequestURI()
     */
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
        return _connection._response;
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
    public String getScheme()
    {
        return _scheme;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getServerName()
     */
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
        Buffer hostPort = _connection.getRequestFields().get(HttpHeaders.HOST_BUFFER);
        if (hostPort != null)
        {
            loop: for (int i = hostPort.putIndex(); i-- > hostPort.getIndex();)
            {
                char ch = (char)(0xff & hostPort.peek(i));
                switch (ch)
                {
                    case ']':
                        break loop;

                    case ':':
                        _serverName = BufferUtil.to8859_1_String(hostPort.peek(hostPort.getIndex(),i - hostPort.getIndex()));
                        try
                        {
                            _port = BufferUtil.toInt(hostPort.peek(i + 1,hostPort.putIndex() - i - 1));
                        }
                        catch (NumberFormatException e)
                        {
                            try
                            {
                                if (_connection != null)
                                    _connection._generator.sendError(HttpStatus.BAD_REQUEST_400,"Bad Host header",null,true);
                            }
                            catch (IOException e1)
                            {
                                throw new RuntimeException(e1);
                            }
                        }
                        return _serverName;
                }
            }
            if (_serverName == null || _port < 0)
            {
                _serverName = BufferUtil.to8859_1_String(hostPort);
                _port = 0;
            }

            return _serverName;
        }

        // Return host from connection
        if (_connection != null)
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
                    _port = _endp == null?0:_endp.getLocalPort();
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
    public String getServletPath()
    {
        if (_servletPath == null)
            _servletPath = "";
        return _servletPath;
    }

    /* ------------------------------------------------------------ */
    public ServletResponse getServletResponse()
    {
        return _connection.getResponse();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getSession()
     */
    public HttpSession getSession()
    {
        return getSession(true);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#getSession(boolean)
     */
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
            _connection.getResponse().addCookie(cookie);

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
     * Get Request TimeStamp
     *
     * @return The time that the request was received.
     */
    public Buffer getTimeStampBuffer()
    {
        if (_timeStampBuffer == null && _timeStamp > 0)
            _timeStampBuffer = HttpFields.__dateCache.formatBuffer(_timeStamp);
        return _timeStampBuffer;
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

    public boolean isAsyncStarted()
    {
       return _async.isAsyncStarted();
    }


    /* ------------------------------------------------------------ */
    public boolean isAsyncSupported()
    {
        return _asyncSupported;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromCookie()
     */
    public boolean isRequestedSessionIdFromCookie()
    {
        return _requestedSessionId != null && _requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromUrl()
     */
    public boolean isRequestedSessionIdFromUrl()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdFromURL()
     */
    public boolean isRequestedSessionIdFromURL()
    {
        return _requestedSessionId != null && !_requestedSessionIdFromCookie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isRequestedSessionIdValid()
     */
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
    public boolean isSecure()
    {
        return _connection.isConfidential(this);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletRequest#isUserInRole(java.lang.String)
     */
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
        _contextPath = null;
        if (_cookies != null)
            _cookies.reset();
        _cookiesExtracted = false;
        _context = null;
        _serverName = null;
        _method = null;
        _pathInfo = null;
        _port = 0;
        _protocol = HttpVersions.HTTP_1_1;
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
        _timeStampBuffer = null;
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
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#removeAttribute(java.lang.String)
     */
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
                    ((AbstractHttpConnection.Output)getServletResponse().getOutputStream()).sendContent(value);
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
                    synchronized (byteBuffer)
                    {
                        NIOBuffer buffer = byteBuffer.isDirect()?new DirectNIOBuffer(byteBuffer,true):new IndirectNIOBuffer(byteBuffer,true);
                        ((AbstractHttpConnection.Output)getServletResponse().getOutputStream()).sendResponse(buffer);
                    }
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
            else if ("org.eclipse.jetty.io.EndPoint.maxIdleTime".equalsIgnoreCase(name))
            {
                try
                {
                    getConnection().getEndPoint().setMaxIdleTime(Integer.valueOf(value.toString()));
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
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        if (_inputState != __NONE)
            return;

        _characterEncoding = encoding;

        // check encoding is supported
        if (!StringUtil.isUTF8(encoding))
            // noinspection ResultOfMethodCallIgnored
            "".getBytes(encoding);
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
    // final so we can safely call this from constructor
    protected final void setConnection(AbstractHttpConnection connection)
    {
        _connection = connection;
        _async.setConnection(connection);
        _endp = connection.getEndPoint();
        _dns = connection.getResolveNames();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletRequest#getContentType()
     */
    public void setContentType(String contentType)
    {
        _connection.getRequestFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,contentType);

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
    public void setMethod(String method)
    {
        _method = method;
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
     * @param protocol
     *            The protocol to set.
     */
    public void setProtocol(String protocol)
    {
        _protocol = protocol;
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
        _queryEncoding = null; //assume utf-8
    }

    /* ------------------------------------------------------------ */
    /**
     * @param addr
     *            The address to set.
     */
    public void setRemoteAddr(String addr)
    {
        _remoteAddr = addr;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param host
     *            The host to set.
     */
    public void setRemoteHost(String host)
    {
        _remoteHost = host;
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
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        _async.startAsync();
        return _async;
    }

    /* ------------------------------------------------------------ */
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (!_asyncSupported)
            throw new IllegalStateException("!asyncSupported");
        _async.startAsync(_context,servletRequest,servletResponse);
        return _async;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return (_handled?"[":"(") + getMethod() + " " + _uri + (_handled?"]@":")@") + hashCode() + " " + super.toString();
    }

    /* ------------------------------------------------------------ */
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
    public Part getPart(String name) throws IOException, ServletException
    {                
        getParts();
        return _multiPartInputStream.getPart(name);
    }

    /* ------------------------------------------------------------ */
    public Collection<Part> getParts() throws IOException, ServletException
    {
        if (getContentType() == null || !getContentType().startsWith("multipart/form-data"))
            throw new ServletException("Content-Type != multipart/form-data");
        
        if (_multiPartInputStream == null)
            _multiPartInputStream = (MultiPartInputStream)getAttribute(__MULTIPART_INPUT_STREAM);
        
        if (_multiPartInputStream == null)
        {
            MultipartConfigElement config = (MultipartConfigElement)getAttribute(__MULTIPART_CONFIG_ELEMENT);
            
            if (config == null)
                throw new IllegalStateException("No multipart config for servlet");
            
            _multiPartInputStream = new MultiPartInputStream(getInputStream(), 
                                                             getContentType(), config, 
                                                             (_context != null?(File)_context.getAttribute("javax.servlet.context.tempdir"):null));
            
            setAttribute(__MULTIPART_INPUT_STREAM, _multiPartInputStream);
            setAttribute(__MULTIPART_CONTEXT, _context);
            Collection<Part> parts = _multiPartInputStream.getParts(); //causes parsing 
            for (Part p:parts)
            {
                MultiPartInputStream.MultiPart mp = (MultiPartInputStream.MultiPart)p;
                if (mp.getContentDispositionFilename() == null)
                {
                    //Servlet Spec 3.0 pg 23, parts without filenames must be put into init params
                    String charset = null;
                    if (mp.getContentType() != null)
                        charset = MimeTypes.getCharsetFromContentType(new ByteArrayBuffer(mp.getContentType()));

                    ByteArrayOutputStream os = null;
                    InputStream is = mp.getInputStream(); //get the bytes regardless of being in memory or in temp file
                    try
                    {
                        os = new ByteArrayOutputStream();
                        IO.copy(is, os);
                        String content=new String(os.toByteArray(),charset==null?StringUtil.__UTF8:charset);   
                        getParameter(""); //cause params to be evaluated
                        getParameters().add(mp.getName(), content);
                    }
                    finally
                    {
                        IO.close(os);
                        IO.close(is);
                    }
                }
            }
        }

        return _multiPartInputStream.getParts();
    }

    /* ------------------------------------------------------------ */
    public void login(String username, String password) throws ServletException
    {
        if (_authentication instanceof Authentication.Deferred) 
        {
            _authentication=((Authentication.Deferred)_authentication).login(username,password,this);
            if (_authentication == null)
                throw new ServletException();
        } 
        else 
        {
            throw new ServletException("Authenticated as "+_authentication);
        }
    }

    /* ------------------------------------------------------------ */
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
        UrlEncoded.decodeTo(query,parameters, StringUtil.__UTF8); //have to assume UTF-8 because we can't know otherwise

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
                UrlEncoded.decodeTo(_queryString,overridden_old_query,getQueryEncoding());//decode using any queryencoding set for the request
                
                
                MultiMap<String> overridden_new_query = new MultiMap<String>();
                UrlEncoded.decodeTo(query,overridden_new_query,StringUtil.__UTF8); //have to assume utf8 as we cannot know otherwise

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
