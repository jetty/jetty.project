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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.security.Authentication;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;

/**
 *
 * @version $Revision: 879 $ $Date: 2009-09-11 16:13:28 +0200 (Fri, 11 Sep 2009) $
 */
public abstract class AbstractHttpConnection extends AbstractConnection implements Dumpable
{
    private static final Logger LOG = Log.getLogger(AbstractHttpConnection.class);

    protected HttpDestination _destination;
    protected HttpGenerator _generator;
    protected HttpParser _parser;
    protected boolean _http11 = true;
    protected int _status;
    protected Buffer _connectionHeader;
    protected boolean _reserved;

    // The current exchange waiting for a response
    protected volatile HttpExchange _exchange;
    protected HttpExchange _pipeline;
    private final Timeout.Task _idleTimeout = new ConnectionIdleTask();
    private AtomicBoolean _idle = new AtomicBoolean(false);


    AbstractHttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
    {
        super(endp);

        _generator = new HttpGenerator(requestBuffers,endp);
        _parser = new HttpParser(responseBuffers,endp,new Handler());
    }

    public void setReserved (boolean reserved)
    {
        _reserved = reserved;
    }

    public boolean isReserved()
    {
        return _reserved;
    }

    public HttpDestination getDestination()
    {
        return _destination;
    }

    public void setDestination(HttpDestination destination)
    {
        _destination = destination;
    }

    public boolean send(HttpExchange ex) throws IOException
    {
        LOG.debug("Send {} on {}",ex,this);
        synchronized (this)
        {
            if (_exchange != null)
            {
                if (_pipeline != null)
                    throw new IllegalStateException(this + " PIPELINED!!!  _exchange=" + _exchange);
                _pipeline = ex;
                return true;
            }

            _exchange = ex;
            _exchange.associate(this);

            // The call to associate() may have closed the connection, check if it's the case
            if (!_endp.isOpen())
            {
                _exchange.disassociate();
                _exchange = null;
                return false;
            }

            _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_COMMIT);

            adjustIdleTimeout();

            return true;
        }
    }

    private void adjustIdleTimeout() throws IOException
    {
        // Adjusts the idle timeout in case the default or exchange timeout
        // are greater. This is needed for long polls, where one wants an
        // aggressive releasing of idle connections (so idle timeout is small)
        // but still allow long polls to complete normally

        long timeout = _exchange.getTimeout();
        if (timeout <= 0)
            timeout = _destination.getHttpClient().getTimeout();

        long endPointTimeout = _endp.getMaxIdleTime();

        if (timeout > 0 && timeout > endPointTimeout)
        {
            // Make it larger than the exchange timeout so that there are
            // no races between the idle timeout and the exchange timeout
            // when trying to close the endpoint
            _endp.setMaxIdleTime(2 * (int)timeout);
        }
    }

    public abstract Connection handle() throws IOException;


    public boolean isIdle()
    {
        synchronized (this)
        {
            return _exchange == null;
        }
    }

    public boolean isSuspended()
    {
        return false;
    }

    public void onClose()
    {
    }

    /**
     * @throws IOException
     */
    protected void commitRequest() throws IOException
    {
        synchronized (this)
        {
            _status=0;
            if (_exchange.getStatus() != HttpExchange.STATUS_WAITING_FOR_COMMIT)
                throw new IllegalStateException();

            _exchange.setStatus(HttpExchange.STATUS_SENDING_REQUEST);
            _generator.setVersion(_exchange.getVersion());

            String method=_exchange.getMethod();
            String uri = _exchange.getRequestURI();
            if (_destination.isProxied())
            {
                if (!HttpMethods.CONNECT.equals(method) && uri.startsWith("/"))
                {
                    boolean secure = _destination.isSecure();
                    String host = _destination.getAddress().getHost();
                    int port = _destination.getAddress().getPort();
                    StringBuilder absoluteURI = new StringBuilder();
                    absoluteURI.append(secure ? HttpSchemes.HTTPS : HttpSchemes.HTTP);
                    absoluteURI.append("://");
                    absoluteURI.append(host);
                    // Avoid adding default ports
                    if (!(secure && port == 443 || !secure && port == 80))
                        absoluteURI.append(":").append(port);
                    absoluteURI.append(uri);
                    uri = absoluteURI.toString();
                }
                Authentication auth = _destination.getProxyAuthentication();
                if (auth != null)
                    auth.setCredentials(_exchange);
            }

            _generator.setRequest(method, uri);
            _parser.setHeadResponse(HttpMethods.HEAD.equalsIgnoreCase(method));

            HttpFields requestHeaders = _exchange.getRequestFields();
            if (_exchange.getVersion() >= HttpVersions.HTTP_1_1_ORDINAL)
            {
                if (!requestHeaders.containsKey(HttpHeaders.HOST_BUFFER))
                    requestHeaders.add(HttpHeaders.HOST_BUFFER,_destination.getHostHeader());
            }

            Buffer requestContent = _exchange.getRequestContent();
            if (requestContent != null)
            {
                requestHeaders.putLongField(HttpHeaders.CONTENT_LENGTH, requestContent.length());
                _generator.completeHeader(requestHeaders,false);
                _generator.addContent(new View(requestContent),true);
                _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
            }
            else
            {
                InputStream requestContentStream = _exchange.getRequestContentSource();
                if (requestContentStream != null)
                {
                    _generator.completeHeader(requestHeaders, false);
                }
                else
                {
                    requestHeaders.remove(HttpHeaders.CONTENT_LENGTH);
                    _generator.completeHeader(requestHeaders, true);
                    _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
                }
            }
        }
    }

    protected void reset() throws IOException
    {
        _connectionHeader = null;
        _parser.reset();
        _generator.reset();
        _http11 = true;
    }


    private class Handler extends HttpParser.EventHandler
    {
        @Override
        public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
        {
            // System.out.println( method.toString() + "///" + url.toString() +
            // "///" + version.toString() );
            // TODO validate this is acceptable, the <!DOCTYPE goop was coming
            // out here
            // throw new IllegalStateException();
        }

        @Override
        public void startResponse(Buffer version, int status, Buffer reason) throws IOException
        {
            HttpExchange exchange = _exchange;
            if (exchange==null)
            {
                LOG.warn("No exchange for response");
                _endp.close();
                return;
            }

            switch(status)
            {
                case HttpStatus.CONTINUE_100:
                case HttpStatus.PROCESSING_102:
                    // TODO check if appropriate expect was sent in the request.
                    exchange.setEventListener(new NonFinalResponseListener(exchange));
                    break;

                case HttpStatus.OK_200:
                    // handle special case for CONNECT 200 responses
                    if (HttpMethods.CONNECT.equalsIgnoreCase(exchange.getMethod()))
                        _parser.setHeadResponse(true);
                    break;
            }

            _http11 = HttpVersions.HTTP_1_1_BUFFER.equals(version);
            _status=status;
            exchange.getEventListener().onResponseStatus(version,status,reason);
            exchange.setStatus(HttpExchange.STATUS_PARSING_HEADERS);

        }

        @Override
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            HttpExchange exchange = _exchange;
            if (exchange!=null)
            {
                if (HttpHeaders.CACHE.getOrdinal(name) == HttpHeaders.CONNECTION_ORDINAL)
                {
                    _connectionHeader = HttpHeaderValues.CACHE.lookup(value);
                }
                exchange.getEventListener().onResponseHeader(name,value);
            }
        }

        @Override
        public void headerComplete() throws IOException
        {
            HttpExchange exchange = _exchange;
            if (exchange!=null)
            {
                exchange.setStatus(HttpExchange.STATUS_PARSING_CONTENT);
                if (HttpMethods.CONNECT.equalsIgnoreCase(exchange.getMethod()))
                    _parser.setPersistent(true);
            }
        }

        @Override
        public void content(Buffer ref) throws IOException
        {
            HttpExchange exchange = _exchange;
            if (exchange!=null)
                exchange.getEventListener().onResponseContent(ref);
        }

        @Override
        public void messageComplete(long contextLength) throws IOException
        {
            HttpExchange exchange = _exchange;
            if (exchange!=null)
                exchange.setStatus(HttpExchange.STATUS_COMPLETED);
        }

        @Override
        public void earlyEOF()
        {
            HttpExchange exchange = _exchange;
            if (exchange!=null)
            {
                if (!exchange.isDone())
                {
                    if (exchange.setStatus(HttpExchange.STATUS_EXCEPTED))
                        exchange.getEventListener().onException(new EofException("early EOF"));
                }
            }
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s %s g=%s p=%s",
                super.toString(),
                _destination == null ? "?.?.?.?:??" : _destination.getAddress(),
                _generator,
                _parser);
    }

    public String toDetailString()
    {
        return toString() + " ex=" + _exchange + " idle for " + _idleTimeout.getAge();
    }

    public void close() throws IOException
    {
        //if there is a live, unfinished exchange, set its status to be
        //excepted and wake up anyone waiting on waitForDone()

        HttpExchange exchange = _exchange;
        if (exchange != null && !exchange.isDone())
        {
            switch (exchange.getStatus())
            {
                case HttpExchange.STATUS_CANCELLED:
                case HttpExchange.STATUS_CANCELLING:
                case HttpExchange.STATUS_COMPLETED:
                case HttpExchange.STATUS_EXCEPTED:
                case HttpExchange.STATUS_EXPIRED:
                    break;
                case HttpExchange.STATUS_PARSING_CONTENT:
                    if (_endp.isInputShutdown() && _parser.isState(HttpParser.STATE_EOF_CONTENT))
                        break;
                default:
                    String exch= exchange.toString();
                    String reason = _endp.isOpen()?(_endp.isInputShutdown()?"half closed: ":"local close: "):"closed: ";
                    if (exchange.setStatus(HttpExchange.STATUS_EXCEPTED))
                        exchange.getEventListener().onException(new EofException(reason+exch));
            }
        }

        if (_endp.isOpen())
        {
            _endp.close();
            _destination.returnConnection(this, true);
        }
    }

    public void setIdleTimeout()
    {
        synchronized (this)
        {
            if (_idle.compareAndSet(false, true))
                _destination.getHttpClient().scheduleIdle(_idleTimeout);
            else
                throw new IllegalStateException();
        }
    }

    public boolean cancelIdleTimeout()
    {
        synchronized (this)
        {
            if (_idle.compareAndSet(true, false))
            {
                _destination.getHttpClient().cancel(_idleTimeout);
                return true;
            }
        }

        return false;
    }

    protected void exchangeExpired(HttpExchange exchange)
    {
        synchronized (this)
        {
            // We are expiring an exchange, but the exchange is pending
            // Cannot reuse the connection because the reply may arrive, so close it
            if (_exchange == exchange)
            {
                try
                {
                    _destination.returnConnection(this, true);
                }
                catch (IOException x)
                {
                    LOG.ignore(x);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.Dumpable#dump()
     */
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.Dumpable#dump(java.lang.Appendable, java.lang.String)
     */
    public void dump(Appendable out, String indent) throws IOException
    {
        synchronized (this)
        {
            out.append(String.valueOf(this)).append("\n");
            AggregateLifeCycle.dump(out,indent,Collections.singletonList(_endp));
        }
    }

    /* ------------------------------------------------------------ */
    private class ConnectionIdleTask extends Timeout.Task
    {
        /* ------------------------------------------------------------ */
        @Override
        public void expired()
        {
            // Connection idle, close it
            if (_idle.compareAndSet(true, false))
            {
                _destination.returnIdleConnection(AbstractHttpConnection.this);
            }
        }
    }


    /* ------------------------------------------------------------ */
    private class NonFinalResponseListener implements HttpEventListener
    {
        final HttpExchange _exchange;
        final HttpEventListener _next;

        /* ------------------------------------------------------------ */
        public NonFinalResponseListener(HttpExchange exchange)
        {
            _exchange=exchange;
            _next=exchange.getEventListener();
        }

        /* ------------------------------------------------------------ */
        public void onRequestCommitted() throws IOException
        {
        }

        /* ------------------------------------------------------------ */
        public void onRequestComplete() throws IOException
        {
        }

        /* ------------------------------------------------------------ */
        public void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
        {
        }

        /* ------------------------------------------------------------ */
        public void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            _next.onResponseHeader(name,value);
        }

        /* ------------------------------------------------------------ */
        public void onResponseHeaderComplete() throws IOException
        {
            _next.onResponseHeaderComplete();
        }

        /* ------------------------------------------------------------ */
        public void onResponseContent(Buffer content) throws IOException
        {
        }

        /* ------------------------------------------------------------ */
        public void onResponseComplete() throws IOException
        {
            _exchange.setEventListener(_next);
            _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
            _parser.reset();
        }

        /* ------------------------------------------------------------ */
        public void onConnectionFailed(Throwable ex)
        {
            _exchange.setEventListener(_next);
            _next.onConnectionFailed(ex);
        }

        /* ------------------------------------------------------------ */
        public void onException(Throwable ex)
        {
            _exchange.setEventListener(_next);
            _next.onException(ex);
        }

        /* ------------------------------------------------------------ */
        public void onExpire()
        {
            _exchange.setEventListener(_next);
            _next.onExpire();
        }

        /* ------------------------------------------------------------ */
        public void onRetry()
        {
            _exchange.setEventListener(_next);
            _next.onRetry();
        }
    }
}
