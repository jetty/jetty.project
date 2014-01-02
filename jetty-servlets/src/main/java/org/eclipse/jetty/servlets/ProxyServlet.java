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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.HostMap;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Asynchronous Proxy Servlet.
 *
 * Forward requests to another server either as a standard web proxy (as defined by RFC2616) or as a transparent proxy.
 * <p>
 * This servlet needs the jetty-util and jetty-client classes to be available to the web application.
 * <p>
 * To facilitate JMX monitoring, the "HttpClient" and "ThreadPool" are set as context attributes prefixed with the servlet name.
 * <p>
 * The following init parameters may be used to configure the servlet:
 * <ul>
 * <li>name - Name of Proxy servlet (default: "ProxyServlet"
 * <li>maxThreads - maximum threads
 * <li>maxConnections - maximum connections per destination
 * <li>timeout - the period in ms the client will wait for a response from the proxied server
 * <li>idleTimeout - the period in ms a connection to proxied server can be idle for before it is closed
 * <li>requestHeaderSize - the size of the request header buffer (d. 6,144)
 * <li>requestBufferSize - the size of the request buffer (d. 12,288)
 * <li>responseHeaderSize - the size of the response header buffer (d. 6,144)
 * <li>responseBufferSize - the size of the response buffer (d. 32,768)
 * <li>HostHeader - Force the host header to a particular value
 * <li>whiteList - comma-separated list of allowed proxy destinations
 * <li>blackList - comma-separated list of forbidden proxy destinations
 * </ul>
 *
 * @see org.eclipse.jetty.server.handler.ConnectHandler
 */
public class ProxyServlet implements Servlet
{
    protected Logger _log;
    protected HttpClient _client;
    protected String _hostHeader;

    protected HashSet<String> _DontProxyHeaders = new HashSet<String>();
    {
        _DontProxyHeaders.add("proxy-connection");
        _DontProxyHeaders.add("connection");
        _DontProxyHeaders.add("keep-alive");
        _DontProxyHeaders.add("transfer-encoding");
        _DontProxyHeaders.add("te");
        _DontProxyHeaders.add("trailer");
        _DontProxyHeaders.add("proxy-authorization");
        _DontProxyHeaders.add("proxy-authenticate");
        _DontProxyHeaders.add("upgrade");
    }

    protected ServletConfig _config;
    protected ServletContext _context;
    protected HostMap<PathMap> _white = new HostMap<PathMap>();
    protected HostMap<PathMap> _black = new HostMap<PathMap>();

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
     */
    public void init(ServletConfig config) throws ServletException
    {
        _config = config;
        _context = config.getServletContext();

        _hostHeader = config.getInitParameter("HostHeader");

        try
        {
            _log = createLogger(config);

            _client = createHttpClient(config);

            if (_context != null)
            {
                _context.setAttribute(config.getServletName() + ".ThreadPool",_client.getThreadPool());
                _context.setAttribute(config.getServletName() + ".HttpClient",_client);
            }

            String white = config.getInitParameter("whiteList");
            if (white != null)
            {
                parseList(white,_white);
            }
            String black = config.getInitParameter("blackList");
            if (black != null)
            {
                parseList(black,_black);
            }
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    public void destroy()
    {
        try
        {
            _client.stop();
        }
        catch (Exception x)
        {
            _log.debug(x);
        }
    }


    /**
     * Create and return a logger based on the ServletConfig for use in the
     * proxy servlet
     *
     * @param config
     * @return Logger
     */
    protected Logger createLogger(ServletConfig config)
    {
        return Log.getLogger("org.eclipse.jetty.servlets." + config.getServletName());
    }

    /**
     * Create and return an HttpClientInstance
     *
     * @return HttpClient
     */
    protected HttpClient createHttpClientInstance()
    {
        return new HttpClient();
    }

    /**
     * Create and return an HttpClient based on ServletConfig
     *
     * By default this implementation will create an instance of the
     * HttpClient for use by this proxy servlet.
     *
     * @param config
     * @return HttpClient
     * @throws Exception
     */
    protected HttpClient createHttpClient(ServletConfig config) throws Exception
    {
        HttpClient client = createHttpClientInstance();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);

        String t = config.getInitParameter("maxThreads");

        if (t != null)
        {
            client.setThreadPool(new QueuedThreadPool(Integer.parseInt(t)));
        }
        else
        {
            client.setThreadPool(new QueuedThreadPool());
        }

        ((QueuedThreadPool)client.getThreadPool()).setName(config.getServletName());

        t = config.getInitParameter("maxConnections");

        if (t != null)
        {
            client.setMaxConnectionsPerAddress(Integer.parseInt(t));
        }

        t = config.getInitParameter("timeout");

        if ( t != null )
        {
            client.setTimeout(Long.parseLong(t));
        }

        t = config.getInitParameter("idleTimeout");

        if ( t != null )
        {
            client.setIdleTimeout(Long.parseLong(t));
        }

        t = config.getInitParameter("requestHeaderSize");

        if ( t != null )
        {
            client.setRequestHeaderSize(Integer.parseInt(t));
        }

        t = config.getInitParameter("requestBufferSize");

        if ( t != null )
        {
            client.setRequestBufferSize(Integer.parseInt(t));
        }

        t = config.getInitParameter("responseHeaderSize");

        if ( t != null )
        {
            client.setResponseHeaderSize(Integer.parseInt(t));
        }

        t = config.getInitParameter("responseBufferSize");

        if ( t != null )
        {
            client.setResponseBufferSize(Integer.parseInt(t));
        }

        client.start();

        return client;
    }

    /* ------------------------------------------------------------ */
    /**
     * Helper function to process a parameter value containing a list of new entries and initialize the specified host map.
     *
     * @param list
     *            comma-separated list of new entries
     * @param hostMap
     *            target host map
     */
    private void parseList(String list, HostMap<PathMap> hostMap)
    {
        if (list != null && list.length() > 0)
        {
            int idx;
            String entry;

            StringTokenizer entries = new StringTokenizer(list,",");
            while (entries.hasMoreTokens())
            {
                entry = entries.nextToken();
                idx = entry.indexOf('/');

                String host = idx > 0?entry.substring(0,idx):entry;
                String path = idx > 0?entry.substring(idx):"/*";

                host = host.trim();
                PathMap pathMap = hostMap.get(host);
                if (pathMap == null)
                {
                    pathMap = new PathMap(true);
                    hostMap.put(host,pathMap);
                }
                if (path != null)
                {
                    pathMap.put(path,path);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Check the request hostname and path against white- and blacklist.
     *
     * @param host
     *            hostname to check
     * @param path
     *            path to check
     * @return true if request is allowed to be proxied
     */
    public boolean validateDestination(String host, String path)
    {
        if (_white.size() > 0)
        {
            boolean match = false;

            Object whiteObj = _white.getLazyMatches(host);
            if (whiteObj != null)
            {
                List whiteList = (whiteObj instanceof List)?(List)whiteObj:Collections.singletonList(whiteObj);

                for (Object entry : whiteList)
                {
                    PathMap pathMap = ((Map.Entry<String, PathMap>)entry).getValue();
                    if (match = (pathMap != null && (pathMap.size() == 0 || pathMap.match(path) != null)))
                        break;
                }
            }

            if (!match)
                return false;
        }

        if (_black.size() > 0)
        {
            Object blackObj = _black.getLazyMatches(host);
            if (blackObj != null)
            {
                List blackList = (blackObj instanceof List)?(List)blackObj:Collections.singletonList(blackObj);

                for (Object entry : blackList)
                {
                    PathMap pathMap = ((Map.Entry<String, PathMap>)entry).getValue();
                    if (pathMap != null && (pathMap.size() == 0 || pathMap.match(path) != null))
                        return false;
                }
            }
        }

        return true;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Servlet#getServletConfig()
     */
    public ServletConfig getServletConfig()
    {
        return _config;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the hostHeader.
     *
     * @return the hostHeader
     */
    public String getHostHeader()
    {
        return _hostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the hostHeader.
     *
     * @param hostHeader
     *            the hostHeader to set
     */
    public void setHostHeader(String hostHeader)
    {
        _hostHeader = hostHeader;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Servlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        final int debug = _log.isDebugEnabled()?req.hashCode():0;

        final HttpServletRequest request = (HttpServletRequest)req;
        final HttpServletResponse response = (HttpServletResponse)res;

        if ("CONNECT".equalsIgnoreCase(request.getMethod()))
        {
            handleConnect(request,response);
        }
        else
        {
            final InputStream in = request.getInputStream();
            final OutputStream out = response.getOutputStream();

            final Continuation continuation = ContinuationSupport.getContinuation(request);

            if (!continuation.isInitial())
                response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT); // Need better test that isInitial
            else
            {

                String uri = request.getRequestURI();
                if (request.getQueryString() != null)
                    uri += "?" + request.getQueryString();

                HttpURI url = proxyHttpURI(request,uri);

                if (debug != 0)
                    _log.debug(debug + " proxy " + uri + "-->" + url);

                if (url == null)
                {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }

                HttpExchange exchange = new HttpExchange()
                {
                    @Override
                    protected void onRequestCommitted() throws IOException
                    {
                    }

                    @Override
                    protected void onRequestComplete() throws IOException
                    {
                    }

                    @Override
                    protected void onResponseComplete() throws IOException
                    {
                        if (debug != 0)
                            _log.debug(debug + " complete");
                        continuation.complete();
                    }

                    @Override
                    protected void onResponseContent(Buffer content) throws IOException
                    {
                        if (debug != 0)
                            _log.debug(debug + " content" + content.length());
                        content.writeTo(out);
                    }

                    @Override
                    protected void onResponseHeaderComplete() throws IOException
                    {
                    }

                    @Override
                    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
                    {
                        if (debug != 0)
                            _log.debug(debug + " " + version + " " + status + " " + reason);

                        if (reason != null && reason.length() > 0)
                            response.setStatus(status,reason.toString());
                        else
                            response.setStatus(status);
                    }

                    @Override
                    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
                    {
                        String nameString = name.toString();
                        String s = nameString.toLowerCase(Locale.ENGLISH);
                        if (!_DontProxyHeaders.contains(s) || (HttpHeaders.CONNECTION_BUFFER.equals(name) && HttpHeaderValues.CLOSE_BUFFER.equals(value)))
                        {
                            if (debug != 0)
                                _log.debug(debug + " " + name + ": " + value);

                            String filteredHeaderValue = filterResponseHeaderValue(nameString,value.toString(),request);
                            if (filteredHeaderValue != null && filteredHeaderValue.trim().length() > 0)
                            {
                                if (debug != 0)
                                    _log.debug(debug + " " + name + ": (filtered): " + filteredHeaderValue);
                                response.addHeader(nameString,filteredHeaderValue);
                            }
                        }
                        else if (debug != 0)
                            _log.debug(debug + " " + name + "! " + value);
                    }

                    @Override
                    protected void onConnectionFailed(Throwable ex)
                    {
                        handleOnConnectionFailed(ex,request,response);

                        // it is possible this might trigger before the
                        // continuation.suspend()
                        if (!continuation.isInitial())
                        {
                            continuation.complete();
                        }
                    }

                    @Override
                    protected void onException(Throwable ex)
                    {
                        if (ex instanceof EofException)
                        {
                            _log.ignore(ex);
                            //return;
                        }
                        handleOnException(ex,request,response);

                        // it is possible this might trigger before the
                        // continuation.suspend()
                        if (!continuation.isInitial())
                        {
                            continuation.complete();
                        }
                    }

                    @Override
                    protected void onExpire()
                    {
                        handleOnExpire(request,response);
                        continuation.complete();
                    }

                };

                exchange.setScheme(HttpSchemes.HTTPS.equals(request.getScheme())?HttpSchemes.HTTPS_BUFFER:HttpSchemes.HTTP_BUFFER);
                exchange.setMethod(request.getMethod());
                exchange.setURL(url.toString());
                exchange.setVersion(request.getProtocol());


                if (debug != 0)
                    _log.debug(debug + " " + request.getMethod() + " " + url + " " + request.getProtocol());

                // check connection header
                String connectionHdr = request.getHeader("Connection");
                if (connectionHdr != null)
                {
                    connectionHdr = connectionHdr.toLowerCase(Locale.ENGLISH);
                    if (connectionHdr.indexOf("keep-alive") < 0 && connectionHdr.indexOf("close") < 0)
                        connectionHdr = null;
                }

                // force host
                if (_hostHeader != null)
                    exchange.setRequestHeader("Host",_hostHeader);

                // copy headers
                boolean xForwardedFor = false;
                boolean hasContent = false;
                long contentLength = -1;
                Enumeration<?> enm = request.getHeaderNames();
                while (enm.hasMoreElements())
                {
                    // TODO could be better than this!
                    String hdr = (String)enm.nextElement();
                    String lhdr = hdr.toLowerCase(Locale.ENGLISH);

                    if ("transfer-encoding".equals(lhdr))
                    {
                        if (request.getHeader("transfer-encoding").indexOf("chunk")>=0)
                            hasContent = true;
                    }
                    
                    if (_DontProxyHeaders.contains(lhdr))
                        continue;
                    if (connectionHdr != null && connectionHdr.indexOf(lhdr) >= 0)
                        continue;
                    if (_hostHeader != null && "host".equals(lhdr))
                        continue;

                    if ("content-type".equals(lhdr))
                        hasContent = true;
                    else if ("content-length".equals(lhdr))
                    {
                        contentLength = request.getContentLength();
                        exchange.setRequestHeader(HttpHeaders.CONTENT_LENGTH,Long.toString(contentLength));
                        if (contentLength > 0)
                            hasContent = true;
                    }
                    else if ("x-forwarded-for".equals(lhdr))
                        xForwardedFor = true;

                    Enumeration<?> vals = request.getHeaders(hdr);
                    while (vals.hasMoreElements())
                    {
                        String val = (String)vals.nextElement();
                        if (val != null)
                        {
                            if (debug != 0)
                                _log.debug(debug + " " + hdr + ": " + val);

                            exchange.setRequestHeader(hdr,val);
                        }
                    }
                }

                // Proxy headers
                exchange.setRequestHeader("Via","1.1 (jetty)");
                if (!xForwardedFor)
                {
                    exchange.addRequestHeader("X-Forwarded-For",request.getRemoteAddr());
                    exchange.addRequestHeader("X-Forwarded-Proto",request.getScheme());
                    exchange.addRequestHeader("X-Forwarded-Host",request.getHeader("Host"));
                    exchange.addRequestHeader("X-Forwarded-Server",request.getLocalName());
                }

                if (hasContent)
                {
                    exchange.setRequestContentSource(in);
                }

                customizeExchange(exchange, request);

                /*
                 * we need to set the timeout on the continuation to take into
                 * account the timeout of the HttpClient and the HttpExchange
                 */
                long ctimeout = (_client.getTimeout() > exchange.getTimeout()) ? _client.getTimeout() : exchange.getTimeout();

                // continuation fudge factor of 1000, underlying components
                // should fail/expire first from exchange
                if ( ctimeout == 0 )
                {
                    continuation.setTimeout(0);  // ideally never times out
                }
                else
                {
                    continuation.setTimeout(ctimeout + 1000);
                }

                customizeContinuation(continuation);

                continuation.suspend(response);
                _client.send(exchange);

            }
        }
    }

    /* ------------------------------------------------------------ */
    public void handleConnect(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String uri = request.getRequestURI();

        String port = "";
        String host = "";

        int c = uri.indexOf(':');
        if (c >= 0)
        {
            port = uri.substring(c + 1);
            host = uri.substring(0,c);
            if (host.indexOf('/') > 0)
                host = host.substring(host.indexOf('/') + 1);
        }

        // TODO - make this async!

        InetSocketAddress inetAddress = new InetSocketAddress(host,Integer.parseInt(port));

        // if (isForbidden(HttpMessage.__SSL_SCHEME,addrPort.getHost(),addrPort.getPort(),false))
        // {
        // sendForbid(request,response,uri);
        // }
        // else
        {
            InputStream in = request.getInputStream();
            OutputStream out = response.getOutputStream();

            Socket socket = new Socket(inetAddress.getAddress(),inetAddress.getPort());

            response.setStatus(200);
            response.setHeader("Connection","close");
            response.flushBuffer();
            // TODO prevent real close!

            IO.copyThread(socket.getInputStream(),out);
            IO.copy(in,socket.getOutputStream());
        }
    }

    /* ------------------------------------------------------------ */
    protected HttpURI proxyHttpURI(HttpServletRequest request, String uri) throws MalformedURLException
    {
        return proxyHttpURI(request.getScheme(), request.getServerName(), request.getServerPort(), uri);
    }

    protected HttpURI proxyHttpURI(String scheme, String serverName, int serverPort, String uri) throws MalformedURLException
    {
        if (!validateDestination(serverName,uri))
            return null;

        return new HttpURI(scheme + "://" + serverName + ":" + serverPort + uri);
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Servlet#getServletInfo()
     */
    public String getServletInfo()
    {
        return "Proxy Servlet";
    }


    /**
     * Extension point for subclasses to customize an exchange. Useful for setting timeouts etc. The default implementation does nothing.
     *
     * @param exchange
     * @param request
     */
    protected void customizeExchange(HttpExchange exchange, HttpServletRequest request)
    {

    }

    /**
     * Extension point for subclasses to customize the Continuation after it's initial creation in the service method. Useful for setting timeouts etc. The
     * default implementation does nothing.
     *
     * @param continuation
     */
    protected void customizeContinuation(Continuation continuation)
    {

    }

    /**
     * Extension point for custom handling of an HttpExchange's onConnectionFailed method. The default implementation delegates to
     * {@link #handleOnException(Throwable, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)}
     *
     * @param ex
     * @param request
     * @param response
     */
    protected void handleOnConnectionFailed(Throwable ex, HttpServletRequest request, HttpServletResponse response)
    {
        handleOnException(ex,request,response);
    }

    /**
     * Extension point for custom handling of an HttpExchange's onException method. The default implementation sets the response status to
     * HttpServletResponse.SC_INTERNAL_SERVER_ERROR (503)
     *
     * @param ex
     * @param request
     * @param response
     */
    protected void handleOnException(Throwable ex, HttpServletRequest request, HttpServletResponse response)
    {
        if (ex instanceof IOException)
        {
            _log.warn(ex.toString());
            _log.debug(ex);
        }
        else
            _log.warn(ex);
        
        if (!response.isCommitted())
        {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Extension point for custom handling of an HttpExchange's onExpire method. The default implementation sets the response status to
     * HttpServletResponse.SC_GATEWAY_TIMEOUT (504)
     *
     * @param request
     * @param response
     */
    protected void handleOnExpire(HttpServletRequest request, HttpServletResponse response)
    {
        if (!response.isCommitted())
        {
            response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
        }
    }

    /**
     * Extension point for remote server response header filtering. The default implementation returns the header value as is. If null is returned, this header
     * won't be forwarded back to the client.
     * 
     * @param headerName
     * @param headerValue
     * @param request
     * @return filteredHeaderValue
     */
    protected String filterResponseHeaderValue(String headerName, String headerValue, HttpServletRequest request)
    {
        return headerValue;
    }

    /**
     * Transparent Proxy.
     * 
     * This convenience extension to ProxyServlet configures the servlet as a transparent proxy. The servlet is configured with init parameters:
     * <ul>
     * <li>ProxyTo - a URI like http://host:80/context to which the request is proxied.
     * <li>Prefix - a URI prefix that is striped from the start of the forwarded URI.
     * </ul>
     * For example, if a request was received at /foo/bar and the ProxyTo was http://host:80/context and the Prefix was /foo, then the request would be proxied
     * to http://host:80/context/bar
     * 
     */
    public static class Transparent extends ProxyServlet
    {
        String _prefix;
        String _proxyTo;

        public Transparent()
        {
        }

        public Transparent(String prefix, String host, int port)
        {
            this(prefix,"http",host,port,null);
        }

        public Transparent(String prefix, String schema, String host, int port, String path)
        {
            try
            {
                if (prefix != null)
                {
                    _prefix = new URI(prefix).normalize().toString();
                }
                _proxyTo = new URI(schema,null,host,port,path,null,null).normalize().toString();
            }
            catch (URISyntaxException ex)
            {
                _log.debug("Invalid URI syntax",ex);
            }
        }

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);

            String prefix = config.getInitParameter("Prefix");
            _prefix = prefix == null?_prefix:prefix;

            // Adjust prefix value to account for context path
            String contextPath = _context.getContextPath();
            _prefix = _prefix == null?contextPath:(contextPath + _prefix);

            String proxyTo = config.getInitParameter("ProxyTo");
            _proxyTo = proxyTo == null?_proxyTo:proxyTo;

            if (_proxyTo == null)
                throw new UnavailableException("ProxyTo parameter is requred.");

            if (!_prefix.startsWith("/"))
                throw new UnavailableException("Prefix parameter must start with a '/'.");

            _log.info(config.getServletName() + " @ " + _prefix + " to " + _proxyTo);
        }

        @Override
        protected HttpURI proxyHttpURI(final String scheme, final String serverName, int serverPort, final String uri) throws MalformedURLException
        {
            try
            {
                if (!uri.startsWith(_prefix))
                    return null;

                URI dstUri = new URI(_proxyTo + uri.substring(_prefix.length())).normalize();

                if (!validateDestination(dstUri.getHost(),dstUri.getPath()))
                    return null;

                return new HttpURI(dstUri.toString());
            }
            catch (URISyntaxException ex)
            {
                throw new MalformedURLException(ex.getMessage());
            }
        }
    }
}
