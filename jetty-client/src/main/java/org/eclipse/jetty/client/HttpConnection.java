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

import org.eclipse.jetty.client.security.Authorization;
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
 * 
 * 
 */
public class HttpConnection implements Connection
{
    HttpDestination _destination;
    EndPoint _endp;
    HttpGenerator _generator;
    HttpParser _parser;
    boolean _http11 = true;
    Buffer _connectionHeader;
    Buffer _requestContentChunk;
    long _last;
    boolean _requestComplete;
    public String _message;
    public Throwable _throwable;
    public boolean _reserved;

    /* The current exchange waiting for a response */
    volatile HttpExchange _exchange;
    HttpExchange _pipeline;

    public void dump() throws IOException
    {
        Log.info("endp=" + _endp + " " + _endp.isBufferingInput() + " " + _endp.isBufferingOutput());
        Log.info("generator=" + _generator);
        Log.info("parser=" + _parser.getState() + " " + _parser.isMoreInBuffer());
        Log.info("exchange=" + _exchange);
        if (_endp instanceof SslSelectChannelEndPoint)
            ((SslSelectChannelEndPoint)_endp).dump();
    }

    Timeout.Task _timeout = new Timeout.Task()
    {
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
                        _destination.returnConnection(HttpConnection.this,true);
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
                    _endp.close();
                }
                catch (IOException e)
                {
                    Log.ignore(e);
                }

                if (ex!=null && ex.getStatus() < HttpExchange.STATUS_COMPLETED)
                {
                    ex.setStatus(HttpExchange.STATUS_EXPIRED);
                }
            }
        }
    };

    /* ------------------------------------------------------------ */
    HttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
    {
        _endp = endp;
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
    
    /* ------------------------------------------------------------ */
    public HttpDestination getDestination()
    {
        return _destination;
    }

    /* ------------------------------------------------------------ */
    public void setDestination(HttpDestination destination)
    {
        _destination = destination;
    }

    /* ------------------------------------------------------------ */
    public boolean send(HttpExchange ex) throws IOException
    {
        // _message =
        // Thread.currentThread().getName()+": Generator instance="+_generator
        // .hashCode()+" state= "+_generator.getState()+" _exchange="+_exchange;
        _throwable = new Throwable();
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

            ex.setStatus(HttpExchange.STATUS_WAITING_FOR_COMMIT);
            _exchange = ex;

            if (_endp.isBlocking())
                this.notify();
            else
            {
                SelectChannelEndPoint scep = (SelectChannelEndPoint)_endp;
                scep.scheduleWrite();
            }

            if (!_endp.isBlocking())
                _destination.getHttpClient().schedule(_timeout);

            return true;
        }
    }

    /* ------------------------------------------------------------ */
    public void handle() throws IOException
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
                            Log.warn("unexpected data");
                            _endp.close();
                        }

                        return;
                    }
                }
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
            catch (IOException e)
            {
                synchronized (this)
                {
                    if (_exchange != null)
                    {
                        _exchange.getEventListener().onException(e);
                        _exchange.setStatus(HttpExchange.STATUS_EXCEPTED);
                    }
                }
                failed = true;
                Log.warn("IOE on "+_exchange);
                throw e;
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
                        flushed = -1;
                        if (_exchange != null)
                        {
                            _exchange = null;

                            if (_pipeline == null)
                            {
                                if (!isReserved())
                                    _destination.returnConnection(this,close);
                                if (close)
                                    return;
                            }
                            else
                            {
                                if (close)
                                {
                                    if (!isReserved())
                                        _destination.returnConnection(this,close);
                                    _destination.send(_pipeline);
                                    _pipeline = null;
                                    return;
                                }

                                HttpExchange ex = _pipeline;
                                _pipeline = null;

                                send(ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        synchronized (this)
        {
            return _exchange == null;
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.Connection#isSuspended()
     */
    public boolean isSuspended()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public EndPoint getEndPoint()
    {
        return _endp;
    }

    /* ------------------------------------------------------------ */
    private void commitRequest() throws IOException
    {
        synchronized (this)
        {
            if (_exchange.getStatus() != HttpExchange.STATUS_WAITING_FOR_COMMIT)
                throw new IllegalStateException();

            _exchange.setStatus(HttpExchange.STATUS_SENDING_REQUEST);
            _generator.setVersion(_exchange._version);

            String uri = _exchange._uri;
            if (_destination.isProxied() && uri.startsWith("/"))
            {
                // TODO suppress port 80 or 443
                uri = (_destination.isSecure()?HttpSchemes.HTTPS:HttpSchemes.HTTP) + "://" + _destination.getAddress().getHost() + ":"
                        + _destination.getAddress().getPort() + uri;
                Authorization auth = _destination.getProxyAuthentication();
                if (auth != null)
                    auth.setCredentials(_exchange);
            }

            _generator.setRequest(_exchange._method,uri);

            if (_exchange._version >= HttpVersions.HTTP_1_1_ORDINAL)
            {
                if (!_exchange._requestFields.containsKey(HttpHeaders.HOST_BUFFER))
                    _exchange._requestFields.add(HttpHeaders.HOST_BUFFER,_destination.getHostHeader());
            }

            if (_exchange._requestContent != null)
            {
                _exchange._requestFields.putLongField(HttpHeaders.CONTENT_LENGTH,_exchange._requestContent.length());
                _generator.completeHeader(_exchange._requestFields,false);
                _generator.addContent(_exchange._requestContent,true);
            }
            else if (_exchange._requestContentSource != null)
            {
                _generator.completeHeader(_exchange._requestFields,false);
                int available = _exchange._requestContentSource.available();
                if (available > 0)
                {
                    // TODO deal with any known content length

                    // TODO reuse this buffer!
                    byte[] buf = new byte[available];
                    int length = _exchange._requestContentSource.read(buf);
                    _generator.addContent(new ByteArrayBuffer(buf,0,length),false);
                }
            }
            else
            {
                _exchange._requestFields.remove(HttpHeaders.CONTENT_LENGTH); // TODO
                // :
                // should
                // not
                // be
                // needed
                _generator.completeHeader(_exchange._requestFields,true);
            }

            _exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
        }
    }

    /* ------------------------------------------------------------ */
    protected void reset(boolean returnBuffers) throws IOException
    {
        _requestComplete = false;
        _connectionHeader = null;
        _parser.reset(returnBuffers);
        _generator.reset(returnBuffers);
        _http11 = true;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return "HttpConnection@" + hashCode() + "//" + _destination.getAddress().getHost() + ":" + _destination.getAddress().getPort();
    }

    /* ------------------------------------------------------------ */
    public String toDetailString()
    {
        return toString() + " ex=" + _exchange + " " + _timeout.getAge();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the last
     */
    public long getLast()
    {
        return _last;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param last
     *            the last to set
     */
    public void setLast(long last)
    {
        _last = last;
    }

    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        _endp.close();
    }

}
