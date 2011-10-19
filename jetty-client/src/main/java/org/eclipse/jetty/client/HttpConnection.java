// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.nio.SslSelectChannelEndPoint;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;

/**
 *
 * @version $Revision: 879 $ $Date: 2009-09-11 16:13:28 +0200 (Fri, 11 Sep 2009) $
 */
public class HttpConnection extends AbstractConnection implements Dumpable
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private HttpDestination _destination;
    private HttpGenerator _generator;
    private HttpParser _parser;
    private boolean _http11 = true;
    private int _status;
    private Buffer _connectionHeader;
    private Buffer _requestContentChunk;
    private boolean _requestComplete;
    private boolean _reserved;

    // The current exchange waiting for a response
    private volatile HttpExchange _exchange;
    private HttpExchange _pipeline;
    private final Timeout.Task _idleTimeout = new ConnectionIdleTask();
    private AtomicBoolean _idle = new AtomicBoolean(false);


    HttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
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

            if (_endp.isBlocking())
            {
                this.notify();
            }
            else
            {
                AsyncEndPoint scep = (AsyncEndPoint)_endp;
                scep.scheduleWrite();
            }

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

    public Connection handle() throws IOException
    {
        try
        {
            int no_progress = 0;

            boolean failed = false;
            while (_endp.isBufferingInput() || _endp.isOpen())
            {
                synchronized (this)
                {
                    while (_exchange == null)
                    {
                        if (_endp.isBlocking())
                        {
                            try
                            {
                                this.wait();
                            }
                            catch (InterruptedException e)
                            {
                                throw new InterruptedIOException();
                            }
                        }
                        else
                        {
                            long filled = _parser.fill();
                            if (filled < 0)
                            {
                                close();
                            }
                            else
                            {
                                // Hopefully just space?
                                _parser.skipCRLF();
                                if (_parser.isMoreInBuffer())
                                {
                                    LOG.warn("Unexpected data received but no request sent");
                                    close();
                                }
                            }
                            return this;
                        }
                    }
                }

                try
                {
                    if (_exchange.getStatus() == HttpExchange.STATUS_WAITING_FOR_COMMIT)
                    {
                        no_progress = 0;
                        commitRequest();
                    }

                    long io = 0;
                    _endp.flush();

                    if (_generator.isComplete())
                    {
                        if (!_requestComplete)
                        {
                            _requestComplete = true;
                            _exchange.getEventListener().onRequestComplete();
                        }
                    }
                    else
                    {
                        // Write as much of the request as possible
                        synchronized (this)
                        {
                            if (_exchange == null)
                                continue;
                        }

                        long flushed = _generator.flushBuffer();
                        io += flushed;

                        if (!_generator.isComplete())
                        {
                            if (_exchange!=null)
                            {
                                InputStream in = _exchange.getRequestContentSource();
                                if (in != null)
                                {
                                    if (_requestContentChunk == null || _requestContentChunk.length() == 0)
                                    {
                                        _requestContentChunk = _exchange.getRequestContentChunk();

                                        if (_requestContentChunk != null)
                                            _generator.addContent(_requestContentChunk,false);
                                        else
                                            _generator.complete();

                                        flushed = _generator.flushBuffer();
                                        io += flushed;
                                    }
                                }
                                else
                                    _generator.complete();
                            }
                            else
                                _generator.complete();
                        }
                    }

                    if (_generator.isComplete() && !_requestComplete)
                    {
                        _requestComplete = true;
                        _exchange.getEventListener().onRequestComplete();
                    }

                    // If we are not ended then parse available
                    if (!_parser.isComplete() && (_generator.isComplete() || _generator.isCommitted() && !_endp.isBlocking()))
                    {
                        long filled = _parser.parseAvailable();
                        io += filled;

                        if (_parser.isIdle() && (_endp.isInputShutdown() || !_endp.isOpen()))
                            throw new EofException();
                    }

                    if (io > 0)
                        no_progress = 0;
                    else if (no_progress++ >= 1 && !_endp.isBlocking())
                    {
                        // SSL may need an extra flush as it may have made "no progress" while actually doing a handshake.
                        if (_endp instanceof SslSelectChannelEndPoint && !_generator.isComplete() && !_generator.isEmpty())
                        {
                            long flushed = _generator.flushBuffer();
                            if (flushed>0)
                                continue;
                        }
                        return this;
                    }
                }
                catch (Throwable e)
                {
                    LOG.debug("Failure on " + _exchange, e);

                    if (e instanceof ThreadDeath)
                        throw (ThreadDeath)e;

                    failed = true;

                    synchronized (this)
                    {
                        if (_exchange != null)
                        {
                            // Cancelling the exchange causes an exception as we close the connection,
                            // but we don't report it as it is normal cancelling operation
                            if (_exchange.getStatus() != HttpExchange.STATUS_CANCELLING &&
                                    _exchange.getStatus() != HttpExchange.STATUS_CANCELLED)
                            {
                                _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                                _exchange.getEventListener().onException(e);
                            }
                        }
                        else
                        {
                            if (e instanceof IOException)
                                throw (IOException)e;

                            if (e instanceof Error)
                                throw (Error)e;

                            if (e instanceof RuntimeException)
                                throw (RuntimeException)e;

                            throw new RuntimeException(e);
                        }
                    }
                }
                finally
                {
                    boolean complete = false;
                    boolean close = failed; // always close the connection on error
                    if (!failed)
                    {
                        // are we complete?
                        if (_generator.isComplete())
                        {
                            if (!_requestComplete)
                            {
                                _requestComplete = true;
                                _exchange.getEventListener().onRequestComplete();
                            }

                            // we need to return the HttpConnection to a state that
                            // it can be reused or closed out
                            if (_parser.isComplete())
                            {
                                _exchange.cancelTimeout(_destination.getHttpClient());
                                complete = true;
                            }
                        }

                        // if the endpoint is closed, but the parser incomplete
                        if (!_endp.isOpen() && !(_parser.isComplete()||_parser.isIdle()))
                        {
                            // we wont be called again so let the parser see the close
                            complete=true;
                            _parser.parseAvailable();
                            // TODO should not need this
                            if (!(_parser.isComplete()||_parser.isIdle()))
                            {
                                LOG.warn("Incomplete {} {}",_parser,_endp);
                                if (_exchange!=null && !_exchange.isDone())
                                {
                                    _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                                    _exchange.getEventListener().onException(new EOFException("Incomplete"));
                                }
                            }
                        }
                    }

                    if (_endp.isInputShutdown() && !_parser.isComplete() && !_parser.isIdle())
                    {
                        if (_exchange!=null && !_exchange.isDone())
                        {
                            _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                            _exchange.getEventListener().onException(new EOFException("Incomplete"));
                        }
                        _endp.close();
                    }

                    if (complete || failed)
                    {
                        synchronized (this)
                        {
                            if (!close)
                                close = shouldClose();

                            reset(true);

                            no_progress = 0;
                            if (_exchange != null)
                            {
                                HttpExchange exchange=_exchange;
                                _exchange = null;

                                // Reset the maxIdleTime because it may have been changed
                                if (!close)
                                    _endp.setMaxIdleTime((int)_destination.getHttpClient().getIdleTimeout());

                                if (_status==HttpStatus.SWITCHING_PROTOCOLS_101)
                                {
                                    Connection switched=exchange.onSwitchProtocol(_endp);
                                    if (switched!=null)
                                    {
                                        // switched protocol!
                                        exchange = _pipeline;
                                        _pipeline = null;
                                        if (exchange!=null)
                                            _destination.send(exchange);

                                        return switched;
                                    }
                                }

                                if (_pipeline == null)
                                {
                                    if (!isReserved())
                                        _destination.returnConnection(this, close);
                                }
                                else
                                {
                                    if (close)
                                    {
                                        if (!isReserved())
                                            _destination.returnConnection(this,close);

                                        exchange = _pipeline;
                                        _pipeline = null;
                                        _destination.send(exchange);
                                    }
                                    else
                                    {
                                        exchange = _pipeline;
                                        _pipeline = null;
                                        send(exchange);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            _parser.returnBuffers();

            // Do we have more stuff to write?
            if (!_generator.isComplete() && _generator.getBytesBuffered()>0 && _endp.isOpen() && _endp instanceof AsyncEndPoint)
            {
                // Assume we are write blocked!
                ((AsyncEndPoint)_endp).scheduleWrite();
            }
        }

        return this;
    }

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

    public void closed()
    {
    }

    private void commitRequest() throws IOException
    {
        synchronized (this)
        {
            _status=0;
            if (_exchange.getStatus() != HttpExchange.STATUS_WAITING_FOR_COMMIT)
                throw new IllegalStateException();

            _exchange.setStatus(HttpExchange.STATUS_SENDING_REQUEST);
            _generator.setVersion(_exchange.getVersion());

            String method=_exchange.getMethod();
            String uri = _exchange.getURI();
            if (_destination.isProxied() && !HttpMethods.CONNECT.equals(method) && uri.startsWith("/"))
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
            }
            else
            {
                InputStream requestContentStream = _exchange.getRequestContentSource();
                if (requestContentStream != null)
                {
                    _generator.completeHeader(requestHeaders, false);
                    int available = requestContentStream.available();
                    if (available > 0)
                    {
                        // TODO deal with any known content length
                        // TODO reuse this buffer!
                        byte[] buf = new byte[available];
                        int length = requestContentStream.read(buf);
                        _generator.addContent(new ByteArrayBuffer(buf, 0, length), false);
                    }
                }
                else
                {
                    requestHeaders.remove(HttpHeaders.CONTENT_LENGTH);
                    _generator.completeHeader(requestHeaders, true);
                }
            }

            _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
        }
    }

    protected void reset(boolean returnBuffers) throws IOException
    {
        _requestComplete = false;
        _connectionHeader = null;
        _parser.reset();
        if (returnBuffers)
            _parser.returnBuffers();
        _generator.reset(returnBuffers);
        _http11 = true;
    }

    private boolean shouldClose()
    {
        if (_endp.isInputShutdown())
            return true;
        if (_connectionHeader!=null)
        {
            if (HttpHeaderValues.CLOSE_BUFFER.equals(_connectionHeader))
                return true;
            if (HttpHeaderValues.KEEP_ALIVE_BUFFER.equals(_connectionHeader))
                return false;
        }
        return !_http11;
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
            if (exchange!=null)
            {
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
            if (_endp instanceof AsyncEndPoint)
                ((AsyncEndPoint)_endp).scheduleIdle();
            HttpExchange exchange = _exchange;
            if (exchange!=null)
                exchange.setStatus(HttpExchange.STATUS_PARSING_CONTENT);
        }

        @Override
        public void content(Buffer ref) throws IOException
        {
            if (_endp instanceof AsyncEndPoint)
                ((AsyncEndPoint)_endp).scheduleIdle();
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
    }

    @Override
    public String toString()
    {
        return "HttpConnection@" + hashCode() + "//" + _destination.getAddress().getHost() + ":" + _destination.getAddress().getPort();
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
                    exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                    exchange.getEventListener().onException(new EOFException(reason+exch));
            }
        }

        _endp.close();
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
                _destination.returnIdleConnection(HttpConnection.this);
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
