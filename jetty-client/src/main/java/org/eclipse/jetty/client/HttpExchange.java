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
import org.eclipse.jetty.util.log.Log;


/**
 * <p>An HTTP client API that encapsulates an exchange (a request and its response) with a HTTP server.</p>
 *
 * This object encapsulates:
 * <ul>
 * <li>The HTTP server address, see {@link #setAddress(Address)} or {@link #setURL(String)})
 * <li>The HTTP request method, URI and HTTP version (see {@link #setMethod(String)}, {@link #setURI(String)}, and {@link #setVersion(int)}
 * <li>The request headers (see {@link #addRequestHeader(String, String)} or {@link #setRequestHeader(String, String)})
 * <li>The request content (see {@link #setRequestContent(Buffer)} or {@link #setRequestContentSource(InputStream)})
 * <li>The status of the exchange (see {@link #getStatus()})
 * <li>Callbacks to handle state changes (see the onXxx methods such as {@link #onRequestComplete()} or {@link #onResponseComplete()})
 * <li>The ability to intercept callbacks (see {@link #setEventListener(HttpEventListener)}
 * </ul>
 *
 * <p>The HttpExchange class is intended to be used by a developer wishing to have close asynchronous
 * interaction with the the exchange.<br />
 * Typically a developer will extend the HttpExchange class with a derived
 * class that overrides some or all of the onXxx callbacks. <br />
 * There are also some predefined HttpExchange subtypes that can be used as a basis,
 * see {@link org.eclipse.jetty.client.ContentExchange} and {@link org.eclipse.jetty.client.CachedExchange}.</p>
 *
 * <p>Typically the HttpExchange is passed to the {@link HttpClient#send(HttpExchange)} method, which in
 * turn selects a {@link HttpDestination} and calls its {@link HttpDestination#send(HttpExchange), which
 * then creates or selects a {@link HttpConnection} and calls its {@link HttpConnection#send(HttpExchange).
 * A developer may wish to directly call send on the destination or connection if they wish to bypass
 * some handling provided (eg Cookie handling in the HttpDestination).</p>
 *
 * <p>In some circumstances, the HttpClient or HttpDestination may wish to retry a HttpExchange (eg. failed
 * pipeline request, authentication retry or redirection).  In such cases, the HttpClient and/or HttpDestination
 * may insert their own HttpExchangeListener to intercept and filter the call backs intended for the
 * HttpExchange.</p>
 */
public class HttpExchange
{
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
    private Buffer _requestContentChunk;
    private boolean _retryStatus = false;
    // controls if the exchange will have listeners autoconfigured by the destination
    private boolean _configureListeners = true;
    private HttpEventListener _listener = new Listener();
    private volatile HttpConnection _connection;

    boolean _onRequestCompleteDone;
    boolean _onResponseCompleteDone;
    boolean _onDone; // == onConnectionFail || onException || onExpired || onCancelled || onResponseCompleted && onRequestCompleted


    public int getStatus()
    {
        return _status.get();
    }

    /**
     * @param status the status to wait for
     * @throws InterruptedException if the waiting thread is interrupted
     * @deprecated Use {@link #waitForDone()} instead
     */
    @Deprecated
    public void waitForStatus(int status) throws InterruptedException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Wait until the exchange is "done".
     * Done is defined as when a final state has been passed to the
     * HttpExchange via the associated onXxx call.  Note that an
     * exchange can transit a final state when being used as part
     * of a dialog (eg {@link SecurityListener}.   Done status
     * is thus defined as:<pre>
     *   done == onConnectionFailed
     *        || onException
     *        || onExpire
     *        || onRequestComplete && onResponseComplete
     * </pre>
     * @return
     * @throws InterruptedException
     */
    public int waitForDone () throws InterruptedException
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
        synchronized(this)
        {
            _onRequestCompleteDone=false;
            _onResponseCompleteDone=false;
            _onDone=false;
            setStatus(STATUS_START);
        }
    }

    void setStatus(int newStatus)
    {
        try
        {
            int oldStatus = _status.get();
            boolean set = false;

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
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_CONNECTION:
                    switch (newStatus)
                    {
                        case STATUS_WAITING_FOR_COMMIT:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_COMMIT:
                    switch (newStatus)
                    {
                        case STATUS_SENDING_REQUEST:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onExpire();
                            break;
                    }
                    break;
                case STATUS_SENDING_REQUEST:
                    switch (newStatus)
                    {
                        case STATUS_WAITING_FOR_RESPONSE:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onRequestCommitted();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onExpire();
                            break;
                    }
                    break;
                case STATUS_WAITING_FOR_RESPONSE:
                    switch (newStatus)
                    {
                        case STATUS_PARSING_HEADERS:
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onExpire();
                            break;
                    }
                    break;
                case STATUS_PARSING_HEADERS:
                    switch (newStatus)
                    {
                        case STATUS_PARSING_CONTENT:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onResponseHeaderComplete();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onExpire();
                            break;
                    }
                    break;
                case STATUS_PARSING_CONTENT:
                    switch (newStatus)
                    {
                        case STATUS_COMPLETED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onResponseComplete();
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXCEPTED:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_EXPIRED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                getEventListener().onExpire();
                            break;
                    }
                    break;
                case STATUS_COMPLETED:
                    switch (newStatus)
                    {
                        case STATUS_START:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                        case STATUS_CANCELLING:
                        case STATUS_EXPIRED:
                            // Don't change the status, it's too late
                            set=true;
                            break;
                    }
                    break;
                case STATUS_CANCELLING:
                    switch (newStatus)
                    {
                        case STATUS_CANCELLED:
                            if (set=_status.compareAndSet(oldStatus,newStatus))
                                done();
                            break;
                        default:
                            // Ignore other statuses, we're cancelling
                            set=true;
                            break;
                    }
                    break;
                case STATUS_EXCEPTED:
                case STATUS_EXPIRED:
                case STATUS_CANCELLED:
                    switch (newStatus)
                    {
                        case STATUS_START:
                            set=_status.compareAndSet(oldStatus,newStatus);
                            break;
                    }
                    break;
                default:
                    // Here means I allowed to set a state that I don't recognize
                    throw new AssertionError(oldStatus + " => " + newStatus);
            }

            if (!set)
                throw new IllegalStateException(oldStatus + " => " + newStatus);
        }
        catch (IOException x)
        {
            Log.warn(x);
        }
    }

    public boolean isDone()
    {
        synchronized (this)
        {
            return _onDone;
        }
    }

    public HttpEventListener getEventListener()
    {
        return _listener;
    }

    public void setEventListener(HttpEventListener listener)
    {
        _listener=listener;
    }

    /**
     * @param url Including protocol, host and port
     */
    public void setURL(String url)
    {
        HttpURI uri = new HttpURI(url);
        String scheme = uri.getScheme();
        if (scheme != null)
        {
            if (HttpSchemes.HTTP.equalsIgnoreCase(scheme))
                setScheme(HttpSchemes.HTTP_BUFFER);
            else if (HttpSchemes.HTTPS.equalsIgnoreCase(scheme))
                setScheme(HttpSchemes.HTTPS_BUFFER);
            else
                setScheme(new ByteArrayBuffer(scheme));
        }

        int port = uri.getPort();
        if (port <= 0)
            port = "https".equalsIgnoreCase(scheme)?443:80;

        setAddress(new Address(uri.getHost(),port));

        String completePath = uri.getCompletePath();
        if (completePath == null)
            completePath = "/";

        setURI(completePath);
    }

    /**
     * @param address the address of the server
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
     * @param scheme the scheme of the URL (for example 'http')
     */
    public void setScheme(Buffer scheme)
    {
        _scheme = scheme;
    }

    /**
     * @return the scheme of the URL
     */
    public Buffer getScheme()
    {
        return _scheme;
    }

    /**
     * @param version the HTTP protocol version as integer, 9, 10 or 11 for 0.9, 1.0 or 1.1
     */
    public void setVersion(int version)
    {
        _version = version;
    }

    /**
     * @param version the HTTP protocol version as string
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
     * @param method the HTTP method (for example 'GET')
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
     * @return the path of the URL
     */
    public String getURI()
    {
        return _uri;
    }

    /**
     * @param uri the path of the URL (for example '/foo/bar?a=1')
     */
    public void setURI(String uri)
    {
        _uri = uri;
    }

    /**
     * Adds the specified request header
     * @param name the header name
     * @param value the header value
     */
    public void addRequestHeader(String name, String value)
    {
        getRequestFields().add(name,value);
    }

    /**
     * Adds the specified request header
     * @param name the header name
     * @param value the header value
     */
    public void addRequestHeader(Buffer name, Buffer value)
    {
        getRequestFields().add(name,value);
    }

    /**
     * Sets the specified request header
     * @param name the header name
     * @param value the header value
     */
    public void setRequestHeader(String name, String value)
    {
        getRequestFields().put(name,value);
    }

    /**
     * Sets the specified request header
     * @param name the header name
     * @param value the header value
     */
    public void setRequestHeader(Buffer name, Buffer value)
    {
        getRequestFields().put(name,value);
    }

    /**
     * @param value the content type of the request
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
     * @param requestContent the request content
     */
    public void setRequestContent(Buffer requestContent)
    {
        _requestContent = requestContent;
    }

    /**
     * @param stream the request content as a stream
     */
    public void setRequestContentSource(InputStream stream)
    {
        _requestContentSource = stream;
    }

    /**
     * @return the request content as a stream
     */
    public InputStream getRequestContentSource()
    {
        return _requestContentSource;
    }

    public Buffer getRequestContentChunk() throws IOException
    {
        synchronized (this)
        {
            if (_requestContentChunk == null)
                _requestContentChunk = new ByteArrayBuffer(4096); // TODO configure
            else
            {
                if (_requestContentChunk.hasContent())
                    throw new IllegalStateException();
                _requestContentChunk.clear();
            }

            int read = _requestContentChunk.capacity();
            int length = _requestContentSource.read(_requestContentChunk.array(),0,read);
            if (length >= 0)
            {
                _requestContentChunk.setPutIndex(length);
                return _requestContentChunk;
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
     * @param retryStatus whether a retry will be attempted or not
     */
    public void setRetryStatus(boolean retryStatus)
    {
        _retryStatus = retryStatus;
    }

    /**
     * Initiates the cancelling of this exchange.
     * The status of the exchange is set to {@link #STATUS_CANCELLING}.
     * Cancelling the exchange is an asynchronous operation with respect to the request/response,
     * and as such checking the request/response status of a cancelled exchange may return undefined results
     * (for example it may have only some of the response headers being sent by the server).
     * The cancelling of the exchange is completed when the exchange status (see {@link #getStatus()}) is
     * {@link #STATUS_CANCELLED}, and this can be waited using {@link #waitForDone()}.
     */
    public void cancel()
    {
        setStatus(STATUS_CANCELLING);
        abort();
    }

    private void done()
    {
        synchronized(this)
        {
            disassociate();
            _onDone=true;
            notifyAll();
        }
    }

    private void abort()
    {
        HttpConnection httpConnection = _connection;
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
                Log.debug(x);
            }
        }
    }

    void associate(HttpConnection connection)
    {
        _connection = connection;
        if (getStatus() == STATUS_CANCELLING)
            abort();
    }

    boolean isAssociated()
    {
        return this._connection != null;
    }

    HttpConnection disassociate()
    {
        HttpConnection result = _connection;
        this._connection = null;
        if (getStatus() == STATUS_CANCELLING)
            setStatus(STATUS_CANCELLED);
        return result;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "@" + hashCode() + "=" + _method + "//" + _address + _uri + "#" + getStatus();
    }

    /**
     * Callback called when the request headers have been sent to the server.
     * This implementation does nothing.
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onRequestCommitted() throws IOException
    {
    }

    /**
     * Callback called when the request and its body have been sent to the server.
     * This implementation does nothing.
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onRequestComplete() throws IOException
    {
    }

    /**
     * Callback called when a response status line has been received from the server.
     * This implementation does nothing.
     * @param version the HTTP version
     * @param status the HTTP status code
     * @param reason the HTTP status reason string
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
    }

    /**
     * Callback called for each response header received from the server.
     * This implementation does nothing.
     * @param name the header name
     * @param value the header value
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
    {
    }

    /**
     * Callback called when the response headers have been completely received from the server.
     * This implementation does nothing.
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onResponseHeaderComplete() throws IOException
    {
    }

    /**
     * Callback called for each chunk of the response content received from the server.
     * This implementation does nothing.
     * @param content the buffer holding the content chunk
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onResponseContent(Buffer content) throws IOException
    {
    }

    /**
     * Callback called when the entire response has been received from the server
     * This implementation does nothing.
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onResponseComplete() throws IOException
    {
    }

    /**
     * Callback called when an exception was thrown during an attempt to establish the connection
     * with the server (for example the server is not listening).
     * This implementation logs a warning.
     * @param x the exception thrown attempting to establish the connection with the server
     */
    protected void onConnectionFailed(Throwable x)
    {
        Log.warn("CONNECTION FAILED " + this,x);
    }

    /**
     * Callback called when any other exception occurs during the handling of this exchange.
     * This implementation logs a warning.
     * @param x the exception thrown during the handling of this exchange
     */
    protected void onException(Throwable x)
    {
        Log.warn("EXCEPTION " + this,x);
    }

    /**
     * Callback called when no response has been received within the timeout.
     * This implementation logs a warning.
     */
    protected void onExpire()
    {
        Log.warn("EXPIRED " + this);
    }

    /**
     * Callback called when the request is retried (due to failures or authentication).
     * Implementations may need to reset any consumable content that needs to be sent.
     * This implementation does nothing.
     * @throws IOException allowed to be thrown by overriding code
     */
    protected void onRetry() throws IOException
    {
    }

    /**
     * @return true if the exchange should have listeners configured for it by the destination,
     * false if this is being managed elsewhere
     * @see #setConfigureListeners(boolean)
     */
    public boolean configureListeners()
    {
        return _configureListeners;
    }

    /**
     * @param autoConfigure whether the listeners are configured by the destination or elsewhere
     */
    public void setConfigureListeners(boolean autoConfigure)
    {
        this._configureListeners = autoConfigure;
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
                synchronized(HttpExchange.this)
                {
                    _onRequestCompleteDone=true;
                    _onDone=_onResponseCompleteDone;
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
                synchronized(HttpExchange.this)
                {
                    _onResponseCompleteDone=true;
                    _onDone=_onRequestCompleteDone;
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
            HttpExchange.this.setRetryStatus( true );
            try
            {
                HttpExchange.this.onRetry();
            }
            catch (IOException e)
            {
                Log.debug(e);
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
