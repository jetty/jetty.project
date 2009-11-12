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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.security.Authorization;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.ssl.SslSelectChannelEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

/**
 *
 * @version $Revision: 879 $ $Date: 2009-09-11 16:13:28 +0200 (Fri, 11 Sep 2009) $
 */
public class HttpConnection implements Connection
{
    private HttpDestination _destination;
    private EndPoint _endp;
    private HttpGenerator _generator;
    private HttpParser _parser;
    private boolean _http11 = true;
    private Buffer _connectionHeader;
    private Buffer _requestContentChunk;
    private boolean _requestComplete;
    private boolean _reserved;
    // The current exchange waiting for a response
    private volatile HttpExchange _exchange;
    private HttpExchange _pipeline;
    private final Timeout.Task _timeout = new TimeoutTask();
    private AtomicBoolean _idle = new AtomicBoolean(false);

    public void dump() throws IOException
    {
        Log.info("endp=" + _endp + " " + _endp.isBufferingInput() + " " + _endp.isBufferingOutput());
        Log.info("generator=" + _generator);
        Log.info("parser=" + _parser.getState() + " " + _parser.isMoreInBuffer());
        Log.info("exchange=" + _exchange);
        if (_endp instanceof SslSelectChannelEndPoint)
            ((SslSelectChannelEndPoint)_endp).dump();
    }

    HttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
    {
        _endp = endp;
        _generator = new HttpGenerator(requestBuffers,endp);
        _parser = new HttpParser(responseBuffers,endp,new Handler());
    }

    public long getTimeStamp()
    {
        return -1;
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

            if (!_endp.isOpen())
                return false;

            _exchange = ex;
            _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_COMMIT);

            if (_endp.isBlocking())
            {
                this.notify();
            }
            else
            {
                SelectChannelEndPoint scep = (SelectChannelEndPoint)_endp;
                scep.scheduleWrite();
            }
            _destination.getHttpClient().schedule(_timeout);

            return true;
        }
    }

    public void handle() throws IOException
    {
        if (_exchange != null)
            _exchange.associate(this);

        try
        {
            int no_progress = 0;
            long flushed = 0;

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
                            // Hopefully just space?
                            _parser.fill();
                            _parser.skipCRLF();
                            if (_parser.isMoreInBuffer())
                            {
                                Log.warn("Unexpected data received but no request sent");
                                close();
                            }
                            return;
                        }
                    }
                    if (!_exchange.isAssociated())
                        _exchange.associate(this);
                }

                if (_exchange.getStatus() == HttpExchange.STATUS_WAITING_FOR_COMMIT)
                {
                    no_progress = 0;
                    commitRequest();
                }

                try
                {
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
                            flushed = _generator.flushBuffer();
                            io += flushed;
                        }

                        if (!_generator.isComplete())
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
                                    io += _generator.flushBuffer();
                                }
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
                    if (!_parser.isComplete() && _generator.isCommitted())
                    {
                        long filled = _parser.parseAvailable();
                        io += filled;
                    }

                    if (io > 0)
                        no_progress = 0;
                    else if (no_progress++ >= 2 && !_endp.isBlocking())
                    {
                        // SSL may need an extra flush as it may have made "no progress" while actually doing a handshake.
                        if (_endp instanceof SslSelectChannelEndPoint && !_generator.isComplete() && !_generator.isEmpty())
                        {
                            if (_generator.flushBuffer()>0)
                                continue;
                        }
                        return;
                    }
                }
                catch (Throwable e)
                {
                    Log.debug("Failure on " + _exchange, e);

                    if (e instanceof ThreadDeath)
                        throw (ThreadDeath)e;

                    synchronized (this)
                    {
                        if (_exchange != null)
                        {
                            // Cancelling the exchange causes an exception as we close the connection,
                            // but we don't report it as it is normal cancelling operation
                            if (_exchange.getStatus() != HttpExchange.STATUS_CANCELLING)
                            {
                                _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                                _exchange.getEventListener().onException(e);
                            }
                        }
                    }

                    failed = true;
                    if (e instanceof IOException)
                        throw (IOException)e;

                    if (e instanceof Error)
                        throw (Error)e;

                    if (e instanceof RuntimeException)
                        throw (RuntimeException)e;

                    throw new RuntimeException(e);
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
                                _destination.getHttpClient().cancel(_timeout);
                                complete = true;
                            }
                        }
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
                                _exchange.disassociate();
                                _exchange = null;

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

                                        HttpExchange exchange = _pipeline;
                                        _pipeline = null;
                                        _destination.send(exchange);
                                    }
                                    else
                                    {
                                        HttpExchange exchange = _pipeline;
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
            if (_exchange != null && _exchange.isAssociated())
                _exchange.disassociate();
        }
    }

    public boolean isIdle()
    {
        synchronized (this)
        {
            return _exchange == null;
        }
    }

    /**
     * @see org.eclipse.jetty.io.Connection#isSuspended()
     */
    public boolean isSuspended()
    {
        return false;
    }

    public EndPoint getEndPoint()
    {
        return _endp;
    }

    private void commitRequest() throws IOException
    {
        synchronized (this)
        {
            if (_exchange.getStatus() != HttpExchange.STATUS_WAITING_FOR_COMMIT)
                throw new IllegalStateException();

            _exchange.setStatus(HttpExchange.STATUS_SENDING_REQUEST);
            _generator.setVersion(_exchange.getVersion());

            String uri = _exchange.getURI();
            if (_destination.isProxied() && uri.startsWith("/"))
            {
                // TODO suppress port 80 or 443
                uri = (_destination.isSecure()?HttpSchemes.HTTPS:HttpSchemes.HTTP) + "://" + _destination.getAddress().getHost() + ":"
                        + _destination.getAddress().getPort() + uri;
                Authorization auth = _destination.getProxyAuthentication();
                if (auth != null)
                    auth.setCredentials(_exchange);
            }

            _generator.setRequest(_exchange.getMethod(), uri);

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
                _generator.addContent(requestContent,true);
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
        _parser.reset(returnBuffers);
        _generator.reset(returnBuffers);
        _http11 = true;
    }

    private boolean shouldClose()
    {
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
                _http11 = HttpVersions.HTTP_1_1_BUFFER.equals(version);
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
            HttpExchange exchange = _exchange;
            if (exchange!=null)
                exchange.setStatus(HttpExchange.STATUS_PARSING_CONTENT);
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
    }

    @Override
    public String toString()
    {
        return "HttpConnection@" + hashCode() + "//" + _destination.getAddress().getHost() + ":" + _destination.getAddress().getPort();
    }

    public String toDetailString()
    {
        return toString() + " ex=" + _exchange + " " + _timeout.getAge();
    }

    public void close() throws IOException
    {
        _endp.close();
    }

    public void setIdleTimeout(long expire)
    {
        synchronized (this)
        {
            if (_idle.compareAndSet(false,true))
                _destination.getHttpClient().scheduleIdle(_timeout);
            else
                throw new IllegalStateException();
        }
    }
    
    public boolean cancelIdleTimeout()
    {
        synchronized (this)
        {
            if (_idle.compareAndSet(true,false))
            {
                _destination.getHttpClient().cancel(_timeout);
                return true;
            }
        }
        
        return false;
    }
    
    private class TimeoutTask extends Timeout.Task
    {
        @Override
        public void expired()
        {
            HttpExchange ex = null;
            try
            {
                synchronized (HttpConnection.this)
                {
                    ex = _exchange;
                    _exchange = null;
                    if (ex != null)
                    {
                        ex.disassociate();
                        _destination.returnConnection(HttpConnection.this, true);
                    }
                    else if (_idle.compareAndSet(true,false))
                    {
                        _destination.returnIdleConnection(HttpConnection.this);
                    }
                }
            }
            catch (Exception e)
            {
                Log.debug(e);
            }
            finally
            {
                try
                {
                    close();
                }
                catch (IOException e)
                {
                    Log.ignore(e);
                }

                if (ex != null && ex.getStatus() < HttpExchange.STATUS_COMPLETED)
                {
                    ex.setStatus(HttpExchange.STATUS_EXPIRED);
                }
            }
        }
    }

}
