package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.Enumeration;
import java.util.HashSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class ProxyRule extends PatternRule
{
    private static final Logger _log = Log.getLogger(ProxyRule.class);

    private HttpClient _client;
    private String _hostHeader;
    private String _proxyTo;

    private HashSet<String> _DontProxyHeaders = new HashSet<String>();
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

    /* ------------------------------------------------------------ */
    public ProxyRule()
    {
        _handling = true;
        _terminating = true;

    }

    /* ------------------------------------------------------------ */
    private void initializeClient() throws Exception
    {
        _client = new HttpClient();
        _client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _client.setThreadPool(new QueuedThreadPool());
        _client.start();
    }

    /* ------------------------------------------------------------ */
    private HttpURI proxyHttpURI(String uri) throws MalformedURLException
    {
        return new HttpURI(_proxyTo + uri);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected String apply(String target, HttpServletRequest request, final HttpServletResponse response) throws IOException
    {
        synchronized (this)
        {
            if (_client == null)
            {
                try
                {
                    initializeClient();
                }
                catch (Exception e)
                {
                    throw new IOException("Unable to proxy: " + e.getMessage());
                }
            }
        }

        final int debug = _log.isDebugEnabled()?request.hashCode():0;

        final InputStream in = request.getInputStream();
        final OutputStream out = response.getOutputStream();

        HttpURI url = createUrl(request,debug);

        if (url == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return target;
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

            @SuppressWarnings("deprecation")
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
                String s = name.toString().toLowerCase();
                if (!_DontProxyHeaders.contains(s) || (HttpHeaders.CONNECTION_BUFFER.equals(name) && HttpHeaderValues.CLOSE_BUFFER.equals(value)))
                {
                    if (debug != 0)
                        _log.debug(debug + " " + name + ": " + value);

                    response.addHeader(name.toString(),value.toString());
                }
                else if (debug != 0)
                    _log.debug(debug + " " + name + "! " + value);
            }

            @Override
            protected void onConnectionFailed(Throwable ex)
            {
                _log.warn(ex.toString());
                _log.debug(ex);
                if (!response.isCommitted())
                {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }

            @Override
            protected void onException(Throwable ex)
            {
                if (ex instanceof EofException)
                {
                    _log.ignore(ex);
                    return;
                }
                _log.warn(ex.toString());
                _log.debug(ex);
                if (!response.isCommitted())
                {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }

            @Override
            protected void onExpire()
            {
                if (!response.isCommitted())
                {
                    response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
                }
            }

        };

        exchange.setMethod(request.getMethod());
        exchange.setURL(url.toString());
        exchange.setVersion(request.getProtocol());

        if (debug != 0)
            _log.debug(debug + " " + request.getMethod() + " " + url + " " + request.getProtocol());

        boolean hasContent = createHeaders(request,debug,exchange);

        if (hasContent)
            exchange.setRequestContentSource(in);

        /*
         * we need to set the timeout on the continuation to take into account the timeout of the HttpClient and the HttpExchange
         */
        long ctimeout = (_client.getTimeout() > exchange.getTimeout())?_client.getTimeout():exchange.getTimeout();
        exchange.setTimeout(ctimeout);

        _client.send(exchange);
        try
        {
            exchange.waitForDone();
        }
        catch (InterruptedException e)
        {
            _log.info("Exception while waiting for response on proxied request", e);
        }
        return target;
    }

    /* ------------------------------------------------------------ */
    private HttpURI createUrl(HttpServletRequest request, final int debug) throws MalformedURLException
    {
        String uri = request.getRequestURI();
        if (request.getQueryString() != null)
            uri += "?" + request.getQueryString();
        uri = PathMap.pathInfo(_pattern,uri);
        if(uri==null)
            uri = "/";
        HttpURI url = proxyHttpURI(uri);

        if (debug != 0)
            _log.debug(debug + " proxy " + uri + "-->" + url);
        return url;
    }

    /* ------------------------------------------------------------ */
    private boolean createHeaders(final HttpServletRequest request, final int debug, HttpExchange exchange)
    {
        // check connection header
        String connectionHdr = request.getHeader("Connection");
        if (connectionHdr != null)
        {
            connectionHdr = connectionHdr.toLowerCase();
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
            String lhdr = hdr.toLowerCase();

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
            exchange.addRequestHeader("X-Forwarded-Host",request.getServerName());
            exchange.addRequestHeader("X-Forwarded-Server",request.getLocalName());
        }
        return hasContent;
    }

    /* ------------------------------------------------------------ */
    public void setProxyTo(String proxyTo)
    {
        this._proxyTo = proxyTo;
    }
}
