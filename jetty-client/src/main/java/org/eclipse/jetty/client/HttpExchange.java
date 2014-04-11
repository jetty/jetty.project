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
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.security.SecurityListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;

/**
 * <p>
 * An HTTP client API that encapsulates an exchange (a request and its response) with a HTTP server.
 * </p>
 *
 * This object encapsulates:
 * <ul>
 * <li>The HTTP server address, see {@link #setAddress(Address)}, or {@link #setURI(URI)}, or {@link #setURL(String)})
 * <li>The HTTP request method, URI and HTTP version (see {@link #setMethod(String)}, {@link #setRequestURI(String)}, and {@link #setVersion(int)})
 * <li>The request headers (see {@link #addRequestHeader(String, String)} or {@link #setRequestHeader(String, String)})
 * <li>The request content (see {@link #setRequestContent(Buffer)} or {@link #setRequestContentSource(InputStream)})
 * <li>The status of the exchange (see {@link #getStatus()})
 * <li>Callbacks to handle state changes (see the onXxx methods such as {@link #onRequestComplete()} or {@link #onResponseComplete()})
 * <li>The ability to intercept callbacks (see {@link #setEventListener(HttpEventListener)}
 * </ul>
 *
 * <p>
 * The HttpExchange class is intended to be used by a developer wishing to have close asynchronous interaction with the the exchange.<br />
 * Typically a developer will extend the HttpExchange class with a derived class that overrides some or all of the onXxx callbacks. <br />
 * There are also some predefined HttpExchange subtypes that can be used as a basis, see {@link org.eclipse.jetty.client.ContentExchange} and
 * {@link org.eclipse.jetty.client.CachedExchange}.
 * </p>
 *
 * <p>
 * Typically the HttpExchange is passed to the {@link HttpClient#send(HttpExchange)} method, which in turn selects a {@link HttpDestination} and calls its
 * {@link HttpDestination#send(HttpExchange)}, which then creates or selects a {@link AbstractHttpConnection} and calls its {@link AbstractHttpConnection#send(HttpExchange)}. A
 * developer may wish to directly call send on the destination or connection if they wish to bypass some handling provided (eg Cookie handling in the
 * HttpDestination).
 * </p>
 *
 * <p>
 * In some circumstances, the HttpClient or HttpDestination may wish to retry a HttpExchange (eg. failed pipeline request, authentication retry or redirection).
 * In such cases, the HttpClient and/or HttpDestination may insert their own HttpExchangeListener to intercept and filter the call backs intended for the
 * HttpExchange.
 * </p>
 */
public class HttpExchange
{
    static final Logger LOG = Log.getLogger(HttpExchange.class);

    public static final int STATUS_START = 0;
    public static final int STATUS_WAITING_FOR_CONNECTION = 1;
    public static final int STATUS_WAITING_FOR_COMMIT = 2;
    public static final int STATUS_SENDING_REQUEST = 3;
    public static final int STATUS_WAITING_FOR_RESPONSE = 4;
    public static final int STATUS_PARSING_HEADERS = 5;
    public static final int STATUS_PARSING_CONTENT = 6;
    public static final int STATUS_COMPLETED = 7;
    public static final int STATUS_EXPIRED = 8;
    public static final int STATUS_EXCEPTED = 9;
    public static final int STATUS_CANCELLING = 10;
    public static final int STATUS_CANCELLED = 11;

    // HTTP protocol fields
    private String _method = HttpMethods.GET;
    private Buffer _scheme = HttpSchemes.HTTP_BUFFER;
    private String _uri;
    private int _version = HttpVersions.HTTP_1_1_ORDINAL;
    private Address _address;
    private final HttpFields _requestFields = new HttpFields();
    private Buffer _requestContent;
    private InputStream _requestContentSource;

    private AtomicInteger _status = new AtomicInteger(STATUS_START);
    private boolean _retryStatus = false;
    // controls if the exchange will have listeners autoconfigured by the destination
    private boolean _configureListeners = true;
    private HttpEventListener _listener = new Listener();
    private volatile AbstractHttpConnection _connection;

    private Address _localAddress = null;

    // a timeout for this exchange
    private long _timeout = -1;
    private volatile Timeout.Task _timeoutTask;
    private long _lastStateChange=System.currentTimeMillis();
    private long _sent=-1;
    private int _lastState=-1;
    private int _lastStatePeriod=-1;

    boolean _onRequestCompleteDone;
    boolean _onResponseCompleteDone;
    boolean _onDone; // == onConnectionFail || onException || onExpired || onCancelled || onResponseCompleted && onRequestCompleted

    protected void expire(HttpDestination destination)
    {
        AbstractHttpConnection connection = _connection;
        if (getStatus() < HttpExchange.STATUS_COMPLETED)
            setStatus(HttpExchange.STATUS_EXPIRED);
        destination.exchangeExpired(this);
        if (connection != null)
            connection.exchangeExpired(this);
    }

    public int getStatus()
    {
        return _status.get();
    }

    /**
     * @param status
     *            the status to wait for
     * @throws InterruptedException
     *             if the waiting thread is interrupted
     * @deprecated Use {@link #waitForDone()} instead
     */
    @Deprecated
    public void waitForStatus(int status) throws InterruptedException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Wait until the exchange is "done". Done is defined as when a final state has been passed to the HttpExchange via the associated onXxx call. Note that an
     * exchange can transit a final state when being used as part of a dialog (eg {@link SecurityListener}. Done status is thus defined as:
     *
     * <pre>
     * done == onConnectionFailed || onException || onExpire || onRequestComplete &amp;&amp; onResponseComplete
     * </pre>
     *
     * @return the done status
     * @throws InterruptedException
     */
    public int waitForDone() throws InterruptedException
    {
        synchronized (this)
        {
            while (!isDone())
                this.wait();
            return _status.get();
        }
    }

    public void reset()
    {
        // TODO - this should do a cancel and wakeup everybody that was waiting.
        // might need a version number concept
        synchronized (this)
        {
            _timeoutTask = null;
            _onRequestCompleteDone = false;
            _onResponseCompleteDone = false;
            _onDone = false;
            setStatus(STATUS_START);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param newStatus
     * @return True if the status was actually set.
     */
    boolean setStatus(int newStatus)
    {
        boolean set = false;
        try
        {
            int oldStatus = _status.get();
            boolean ignored = false;
            if (oldStatus != newStatus)
            {
                long now = System.currentTimeMillis();
                _lastStatePeriod=(int)(now-_lastStateChange);
                _lastState=oldStatus;
                _lastStateChange=now;
                if (newStatus==STATUS_SENDING_REQUEST)
                    _sent=_lastStateChange;
            }
            
            // State machine: from which old status you can go into which new status
            switch (oldStatus)
            {
                case STATUS_START:
                    switch (newStatus)
                    {
                        case STATUS_START:
                        case STATUS_WAITING_FOR_CONNECTION:
                        case STATUS_WAITING_FOR_COMMIT:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_CONNECTION:
                    switch (newStatus)
                    {
                        case STATUS_WAITING_FOR_COMMIT:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_COMMIT:
                    switch (newStatus)
                    {
                        case STATUS_SENDING_REQUEST:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_SENDING_REQUEST:
                    switch (newStatus)
                    {
                        case STATUS_WAITING_FOR_RESPONSE:
                            if (set = _status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onRequestCommitted();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_RESPONSE:
                    switch (newStatus)
                    {
                        case STATUS_PARSING_HEADERS:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_PARSING_HEADERS:
                    switch (newStatus)
                    {
                        case STATUS_PARSING_CONTENT:
                            if (set = _status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onResponseHeaderComplete();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_PARSING_CONTENT:
                    switch (newStatus)
                    {
                        case STATUS_COMPLETED:
                            if (set = _status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onResponseComplete();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            set = setStatusExpired(newStatus,oldStatus);
                            break;
                    }
                    break;
                case STATUS_COMPLETED:
                    switch (newStatus)
                    {
                        case STATUS_START:
                        case STATUS_EXCEPTED:
                        case STATUS_WAITING_FOR_RESPONSE:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXPIRED:
                            // Don't change the status, it's too late
                            ignored = true;
                            break;
                    }
                    break;
                case STATUS_CANCELLING:
                    switch (newStatus)
                    {
                        case STATUS_EXCEPTED:
                        case STATUS_CANCELLED:
                            if (set = _status.compareAndSet(oldStatus,newStatus))
                                done();
                            break;
                        default:
                            // Ignore other statuses, we're cancelling
                            ignored = true;
                            break;
                    }
                    break;
                case STATUS_EXCEPTED:
                case STATUS_EXPIRED:
                case STATUS_CANCELLED:
                    switch (newStatus)
                    {
                        case STATUS_START:
                            set = _status.compareAndSet(oldStatus,newStatus);
                            break;
                            
                        case STATUS_COMPLETED:
                            ignored = true;
                            done();
                            break; 
                            
                        default:
                            ignored = true;
                            break;
                    }
                    break;
                default:
                    // Here means I allowed to set a state that I don't recognize
                    throw new AssertionError(oldStatus + " => " + newStatus);
            }

            if (!set && !ignored)
                throw new IllegalStateException(toState(oldStatus) + " => " + toState(newStatus));
            LOG.debug("setStatus {} {}",newStatus,this);
        }
        catch (IOException x)
        {
            LOG.warn(x);
        }
        return set;
    }

    private boolean setStatusExpired(int newStatus, int oldStatus)
    {
        boolean set;
        if (set = _status.compareAndSet(oldStatus,newStatus))
            getEventListener().onExpire();
        return set;
    }

    public boolean isDone()
    {
        synchronized (this)
        {
            return _onDone;
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public boolean isDone(int status)
    {
        return isDone();
    }

    public HttpEventListener getEventListener()
    {
        return _listener;
    }

    public void setEventListener(HttpEventListener listener)
    {
        _listener = listener;
    }

    public void setTimeout(long timeout)
    {
        _timeout = timeout;
    }

    public long getTimeout()
    {
        return _timeout;
    }

    /**
     * @param url
     *            an absolute URL (for example 'http://localhost/foo/bar?a=1')
     */
    public void setURL(String url)
    {
        setURI(URI.create(url));
    }

    /**
     * @param address
     *            the address of the server
     */
    public void setAddress(Address address)
    {
        _address = address;
    }

    /**
     * @return the address of the server
     */
    public Address getAddress()
    {
        return _address;
    }

    /**
     * the local address used by the connection
     *
     * Note: this method will not be populated unless the exchange has been executed by the HttpClient
     *
     * @return the local address used for the running of the exchange if available, null otherwise.
     */
    public Address getLocalAddress()
    {
        return _localAddress;
    }

    /**
     * @param scheme
     *            the scheme of the URL (for example 'http')
     */
    public void setScheme(Buffer scheme)
    {
        _scheme = scheme;
    }

    /**
     * @param scheme
     *            the scheme of the URL (for example 'http')
     */
    public void setScheme(String scheme)
    {
        if (scheme != null)
        {
            if (HttpSchemes.HTTP.equalsIgnoreCase(scheme))
                setScheme(HttpSchemes.HTTP_BUFFER);
            else if (HttpSchemes.HTTPS.equalsIgnoreCase(scheme))
                setScheme(HttpSchemes.HTTPS_BUFFER);
            else
                setScheme(new ByteArrayBuffer(scheme));
        }
    }

    /**
     * @return the scheme of the URL
     */
    public Buffer getScheme()
    {
        return _scheme;
    }

    /**
     * @param version
     *            the HTTP protocol version as integer, 9, 10 or 11 for 0.9, 1.0 or 1.1
     */
    public void setVersion(int version)
    {
        _version = version;
    }

    /**
     * @param version
     *            the HTTP protocol version as string
     */
    public void setVersion(String version)
    {
        CachedBuffer v = HttpVersions.CACHE.get(version);
        if (v == null)
            _version = 10;
        else
            _version = v.getOrdinal();
    }

    /**
     * @return the HTTP protocol version as integer
     * @see #setVersion(int)
     */
    public int getVersion()
    {
        return _version;
    }

    /**
     * @param method
     *            the HTTP method (for example 'GET')
     */
    public void setMethod(String method)
    {
        _method = method;
    }

    /**
     * @return the HTTP method
     */
    public String getMethod()
    {
        return _method;
    }

    /**
     * @return request URI
     * @see #getRequestURI()
     * @deprecated
     */
    @Deprecated
    public String getURI()
    {
        return getRequestURI();
    }

    /**
     * @return request URI
     */
    public String getRequestURI()
    {
        return _uri;
    }

    /**
     * Set the request URI
     *
     * @param uri
     *            new request URI
     * @see #setRequestURI(String)
     * @deprecated
     */
    @Deprecated
    public void setURI(String uri)
    {
        setRequestURI(uri);
    }

    /**
     * Set the request URI
     *
     * Per RFC 2616 sec5, Request-URI = "*" | absoluteURI | abs_path | authority<br/>
     * where:<br/>
     * <br/>
     * "*" - request applies to server itself<br/>
     * absoluteURI - required for proxy requests, e.g. http://localhost:8080/context<br/>
     * (this form is generated automatically by HttpClient)<br/>
     * abs_path - used for most methods, e.g. /context<br/>
     * authority - used for CONNECT method only, e.g. localhost:8080<br/>
     * <br/>
     * For complete definition of URI components, see RFC 2396 sec3.<br/>
     *
     * @param uri
     *            new request URI
     */
    public void setRequestURI(String uri)
    {
        _uri = uri;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param uri
     *            an absolute URI (for example 'http://localhost/foo/bar?a=1')
     */
    public void setURI(URI uri)
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("!Absolute URI: " + uri);

        if (uri.isOpaque())
            throw new IllegalArgumentException("Opaque URI: " + uri);

        if (LOG.isDebugEnabled())
            LOG.debug("URI = {}",uri.toASCIIString());

        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (port <= 0)
            port = "https".equalsIgnoreCase(scheme)?443:80;

        setScheme(scheme);
        setAddress(new Address(uri.getHost(),port));

        HttpURI httpUri = new HttpURI(uri);
        String completePath = httpUri.getCompletePath();
        setRequestURI(completePath == null?"/":completePath);
    }

    /**
     * Adds the specified request header
     *
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void addRequestHeader(String name, String value)
    {
        getRequestFields().add(name,value);
    }

    /**
     * Adds the specified request header
     *
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void addRequestHeader(Buffer name, Buffer value)
    {
        getRequestFields().add(name,value);
    }

    /**
     * Sets the specified request header
     *
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void setRequestHeader(String name, String value)
    {
        getRequestFields().put(name,value);
    }

    /**
     * Sets the specified request header
     *
     * @param name
     *            the header name
     * @param value
     *            the header value
     */
    public void setRequestHeader(Buffer name, Buffer value)
    {
        getRequestFields().put(name,value);
    }

    /**
     * @param value
     *            the content type of the request
     */
    public void setRequestContentType(String value)
    {
        getRequestFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,value);
    }

    /**
     * @return the request headers
     */
    public HttpFields getRequestFields()
    {
        return _requestFields;
    }

    /**
     * @param requestContent
     *            the request content
     */
    public void setRequestContent(Buffer requestContent)
    {
        _requestContent = requestContent;
    }

    /**
     * @param stream
     *            the request content as a stream
     */
    public void setRequestContentSource(InputStream stream)
    {
        _requestContentSource = stream;
        if (_requestContentSource != null && _requestContentSource.markSupported())
            _requestContentSource.mark(Integer.MAX_VALUE);
    }

    /**
     * @return the request content as a stream
     */
    public InputStream getRequestContentSource()
    {
        return _requestContentSource;
    }

    public Buffer getRequestContentChunk(Buffer buffer) throws IOException
    {
        synchronized (this)
        {
            if (_requestContentSource!=null)
            {
                if (buffer == null)
                    buffer = new ByteArrayBuffer(8192); // TODO configure

                int space = buffer.space();
                int length = _requestContentSource.read(buffer.array(),buffer.putIndex(),space);
                if (length >= 0)
                {
                    buffer.setPutIndex(buffer.putIndex()+length);
                    return buffer;
                }
            }
            return null;
        }
    }

    /**
     * @return the request content
     */
    public Buffer getRequestContent()
    {
        return _requestContent;
    }

    /**
     * @return whether a retry will be attempted or not
     */
    public boolean getRetryStatus()
    {
        return _retryStatus;
    }

    /**
     * @param retryStatus
     *            whether a retry will be attempted or not
     */
    public void setRetryStatus(boolean retryStatus)
    {
        _retryStatus = retryStatus;
    }

    /**
     * Initiates the cancelling of this exchange. The status of the exchange is set to {@link #STATUS_CANCELLING}. Cancelling the exchange is an asynchronous
     * operation with respect to the request/response, and as such checking the request/response status of a cancelled exchange may return undefined results
     * (for example it may have only some of the response headers being sent by the server). The cancelling of the exchange is completed when the exchange
     * status (see {@link #getStatus()}) is {@link #STATUS_CANCELLED}, and this can be waited using {@link #waitForDone()}.
     */
    public void cancel()
    {
        setStatus(STATUS_CANCELLING);
        abort();
    }

    private void done()
    {
        synchronized (this)
        {
            disassociate();
            _onDone = true;
            notifyAll();
        }
    }

    private void abort()
    {
        AbstractHttpConnection httpConnection = _connection;
        if (httpConnection != null)
        {
            try
            {
                // Closing the connection here will cause the connection
                // to be returned in HttpConnection.handle()
                httpConnection.close();
            }
            catch (IOException x)
            {
                LOG.debug(x);
            }
            finally
            {
                disassociate();
            }
        }
    }

    void associate(AbstractHttpConnection connection)
    {
        if (connection.getEndPoint().getLocalAddr() != null)
            _localAddress = new Address(connection.getEndPoint().getLocalAddr(),connection.getEndPoint().getLocalPort());

        _connection = connection;
        if (getStatus() == STATUS_CANCELLING)
            abort();
    }

    boolean isAssociated()
    {
        return this._connection != null;
    }

    AbstractHttpConnection disassociate()
    {
        AbstractHttpConnection result = _connection;
        this._connection = null;
        if (getStatus() == STATUS_CANCELLING)
            setStatus(STATUS_CANCELLED);
        return result;
    }

    public static String toState(int s)
    {
        String state;
        switch (s)
        {
            case STATUS_START:
                state = "START";
                break;
            case STATUS_WAITING_FOR_CONNECTION:
                state = "CONNECTING";
                break;
            case STATUS_WAITING_FOR_COMMIT:
                state = "CONNECTED";
                break;
            case STATUS_SENDING_REQUEST:
                state = "SENDING";
                break;
            case STATUS_WAITING_FOR_RESPONSE:
                state = "WAITING";
                break;
            case STATUS_PARSING_HEADERS:
                state = "HEADERS";
                break;
            case STATUS_PARSING_CONTENT:
                state = "CONTENT";
                break;
            case STATUS_COMPLETED:
                state = "COMPLETED";
                break;
            case STATUS_EXPIRED:
                state = "EXPIRED";
                break;
            case STATUS_EXCEPTED:
                state = "EXCEPTED";
                break;
            case STATUS_CANCELLING:
                state = "CANCELLING";
                break;
            case STATUS_CANCELLED:
                state = "CANCELLED";
                break;
            default:
                state = "UNKNOWN";
        }
        return state;
    }

    @Override
    public String toString()
    {
        String state=toState(getStatus());
        long now=System.currentTimeMillis();
        long forMs = now -_lastStateChange;
        String s= _lastState>=0
            ?String.format("%s@%x=%s//%s%s#%s(%dms)->%s(%dms)",getClass().getSimpleName(),hashCode(),_method,_address,_uri,toState(_lastState),_lastStatePeriod,state,forMs)
            :String.format("%s@%x=%s//%s%s#%s(%dms)",getClass().getSimpleName(),hashCode(),_method,_address,_uri,state,forMs);
        if (getStatus()>=STATUS_SENDING_REQUEST && _sent>0)
            s+="sent="+(now-_sent)+"ms";
        return s;
    }

    /**
     */
    protected Connection onSwitchProtocol(EndPoint endp) throws IOException
    {
        return null;
    }

    /**
     * Callback called when the request headers have been sent to the server. This implementation does nothing.
     *
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onRequestCommitted() throws IOException
    {
    }

    /**
     * Callback called when the request and its body have been sent to the server. This implementation does nothing.
     *
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onRequestComplete() throws IOException
    {
    }

    /**
     * Callback called when a response status line has been received from the server. This implementation does nothing.
     *
     * @param version
     *            the HTTP version
     * @param status
     *            the HTTP status code
     * @param reason
     *            the HTTP status reason string
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
    }

    /**
     * Callback called for each response header received from the server. This implementation does nothing.
     *
     * @param name
     *            the header name
     * @param value
     *            the header value
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
    }

    /**
     * Callback called when the response headers have been completely received from the server. This implementation does nothing.
     *
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onResponseHeaderComplete() throws IOException
    {
    }

    /**
     * Callback called for each chunk of the response content received from the server. This implementation does nothing.
     *
     * @param content
     *            the buffer holding the content chunk
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onResponseContent(Buffer content) throws IOException
    {
    }

    /**
     * Callback called when the entire response has been received from the server This implementation does nothing.
     *
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onResponseComplete() throws IOException
    {
    }

    /**
     * Callback called when an exception was thrown during an attempt to establish the connection with the server (for example the server is not listening).
     * This implementation logs a warning.
     *
     * @param x
     *            the exception thrown attempting to establish the connection with the server
     */
    protected void onConnectionFailed(Throwable x)
    {
        LOG.warn("CONNECTION FAILED " + this,x);
    }

    /**
     * Callback called when any other exception occurs during the handling of this exchange. This implementation logs a warning.
     *
     * @param x
     *            the exception thrown during the handling of this exchange
     */
    protected void onException(Throwable x)
    {
        LOG.warn("EXCEPTION " + this,x);
    }

    /**
     * Callback called when no response has been received within the timeout. This implementation logs a warning.
     */
    protected void onExpire()
    {
        LOG.warn("EXPIRED " + this);
    }

    /**
     * Callback called when the request is retried (due to failures or authentication). Implementations must reset any consumable content that needs to be sent.
     *
     * @throws IOException
     *             allowed to be thrown by overriding code
     */
    protected void onRetry() throws IOException
    {
        if (_requestContentSource != null)
        {
            if (_requestContentSource.markSupported())
            {
                _requestContent = null;
                _requestContentSource.reset();
            }
            else
            {
                throw new IOException("Unsupported retry attempt");
            }
        }
    }

    /**
     * @return true if the exchange should have listeners configured for it by the destination, false if this is being managed elsewhere
     * @see #setConfigureListeners(boolean)
     */
    public boolean configureListeners()
    {
        return _configureListeners;
    }

    /**
     * @param autoConfigure
     *            whether the listeners are configured by the destination or elsewhere
     */
    public void setConfigureListeners(boolean autoConfigure)
    {
        this._configureListeners = autoConfigure;
    }

    protected void scheduleTimeout(final HttpDestination destination)
    {
        assert _timeoutTask == null;

        _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                HttpExchange.this.expire(destination);
            }
        };

        HttpClient httpClient = destination.getHttpClient();
        long timeout = getTimeout();
        if (timeout > 0)
            httpClient.schedule(_timeoutTask,timeout);
        else
            httpClient.schedule(_timeoutTask);
    }

    protected void cancelTimeout(HttpClient httpClient)
    {
        Timeout.Task task = _timeoutTask;
        if (task != null)
            httpClient.cancel(task);
        _timeoutTask = null;
    }

    private class Listener implements HttpEventListener
    {
        public void onConnectionFailed(Throwable ex)
        {
            try
            {
                HttpExchange.this.onConnectionFailed(ex);
            }
            finally
            {
                done();
            }
        }

        public void onException(Throwable ex)
        {
            try
            {
                HttpExchange.this.onException(ex);
            }
            finally
            {
                done();
            }
        }

        public void onExpire()
        {
            try
            {
                HttpExchange.this.onExpire();
            }
            finally
            {
                done();
            }
        }

        public void onRequestCommitted() throws IOException
        {
            HttpExchange.this.onRequestCommitted();
        }

        public void onRequestComplete() throws IOException
        {
            try
            {
                HttpExchange.this.onRequestComplete();
            }
            finally
            {
                synchronized (HttpExchange.this)
                {
                    _onRequestCompleteDone = true;
                    // Member _onDone may already be true, for example
                    // because the exchange expired or has been canceled
                    _onDone |= _onResponseCompleteDone;
                    if (_onDone)
                        disassociate();
                    HttpExchange.this.notifyAll();
                }
            }
        }

        public void onResponseComplete() throws IOException
        {
            try
            {
                HttpExchange.this.onResponseComplete();
            }
            finally
            {
                synchronized (HttpExchange.this)
                {
                    _onResponseCompleteDone = true;
                    // Member _onDone may already be true, for example
                    // because the exchange expired or has been canceled
                    _onDone |= _onRequestCompleteDone;
                    if (_onDone)
                        disassociate();
                    HttpExchange.this.notifyAll();
                }
            }
        }

        public void onResponseContent(Buffer content) throws IOException
        {
            HttpExchange.this.onResponseContent(content);
        }

        public void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            HttpExchange.this.onResponseHeader(name,value);
        }

        public void onResponseHeaderComplete() throws IOException
        {
            HttpExchange.this.onResponseHeaderComplete();
        }

        public void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
        {
            HttpExchange.this.onResponseStatus(version,status,reason);
        }

        public void onRetry()
        {
            HttpExchange.this.setRetryStatus(true);
            try
            {
                HttpExchange.this.onRetry();
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
        }
    }

    /**
     * @deprecated use {@link org.eclipse.jetty.client.CachedExchange} instead
     */
    @Deprecated
    public static class CachedExchange extends org.eclipse.jetty.client.CachedExchange
    {
        public CachedExchange(boolean cacheFields)
        {
            super(cacheFields);
        }
    }

    /**
     * @deprecated use {@link org.eclipse.jetty.client.ContentExchange} instead
     */
    @Deprecated
    public static class ContentExchange extends org.eclipse.jetty.client.ContentExchange
    {
    }
}
