//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static javax.servlet.RequestDispatcher.ERROR_STATUS_CODE;


/**
 * HttpChannel represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both a HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
 *
 */
public class HttpChannel implements Runnable, HttpOutput.Interceptor
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);
    private final AtomicBoolean _committed = new AtomicBoolean();
    private final AtomicLong _requests = new AtomicLong();
    private final Connector _connector;
    private final Executor _executor;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private MetaData.Response _committedMetaData;
    private RequestLog _requestLog;
    private long _oldIdleTimeout;

    /** Bytes written after interception (eg after compression) */
    private long _written;

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        _connector = connector;
        _configuration = configuration;
        _endPoint = endPoint;
        _transport = transport;

        _state = new HttpChannelState(this);
        _request = new Request(this, newHttpInput(_state));
        _response = new Response(this, newHttpOutput());

        _executor = connector == null ? null : connector.getServer().getThreadPool();
        _requestLog = connector == null ? null : connector.getServer().getRequestLog();

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{},{}",this,_endPoint,_endPoint.getConnection(),_state);
    }

    protected HttpInput newHttpInput(HttpChannelState state)
    {
        return new HttpInput(state);
    }

    protected HttpOutput newHttpOutput()
    {
        return new HttpOutput(this);
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    public long getBytesWritten()
    {
        return _written;
    }

    /**
     * @return the number of requests handled by this connection
     */
    public long getRequests()
    {
        return _requests.get();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpTransport getHttpTransport()
    {
        return _transport;
    }

    public RequestLog getRequestLog()
    {
        return _requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        _requestLog = requestLog;
    }

    public void addRequestLog(RequestLog requestLog)
    {
        if (_requestLog==null)
            _requestLog = requestLog;
        else if (_requestLog instanceof RequestLogCollection)
            ((RequestLogCollection) _requestLog).add(requestLog);
        else
            _requestLog = new RequestLogCollection(_requestLog, requestLog);
    }

    public MetaData.Response getCommittedMetaData()
    {
        return _committedMetaData;
    }

    /**
     * Get the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#getIdleTimeout()}, but may be
     * overridden by channels that have timeouts different from their connections.
     * @return the idle timeout (in milliseconds)
     */
    public long getIdleTimeout()
    {
        return _endPoint.getIdleTimeout();
    }

    /**
     * Set the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#setIdleTimeout(long)}, but may be
     * overridden by channels that have timeouts different from their connections.
     * @param timeoutMs the idle timeout in milliseconds
     */
    public void setIdleTimeout(long timeoutMs)
    {
        _endPoint.setIdleTimeout(timeoutMs);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return _connector.getByteBufferPool();
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    @Override
    public boolean isOptimizedForDirectBuffers()
    {
        return getHttpTransport().isOptimizedForDirectBuffers();
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Request getRequest()
    {
        return _request;
    }

    public Response getResponse()
    {
        return _response;
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    public InetSocketAddress getLocalAddress()
    {
        return _endPoint.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return _endPoint.getRemoteAddress();
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @param available estimate of the number of bytes that are available
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    public void recycle()
    {
        _committed.set(false);
        _request.recycle();
        _response.recycle();
        _committedMetaData=null;
        _requestLog=_connector==null?null:_connector.getServer().getRequestLog();
        _written=0;
    }

    public void asyncReadFillInterested()
    {
    }

    @Override
    public void run()
    {
        handle();
    }

    /**
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {} ", this,_request.getHttpURI());

        HttpChannelState.Action action = _state.handling();

        // Loop here to handle async request redispatches.
        // The loop is controlled by the call to async.unhandle in the
        // finally block below.  Unhandle will return false only if an async dispatch has
        // already happened when unhandle is called.
        loop: while (!getServer().isStopped())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} action {}",this,action);

                switch(action)
                {
                    case TERMINATED:
                    case WAIT:
                        break loop;

                    case DISPATCH:
                    {
                        if (!_request.hasMetaData())
                            throw new IllegalStateException("state=" + _state);
                        _request.setHandled(false);
                        _response.getHttpOutput().reopen();

                        try
                        {
                            _request.setDispatcherType(DispatcherType.REQUEST);

                            List<HttpConfiguration.Customizer> customizers = _configuration.getCustomizers();
                            if (!customizers.isEmpty())
                            {
                                for (HttpConfiguration.Customizer customizer : customizers)
                                {
                                    customizer.customize(getConnector(), _configuration, _request);
                                    if (_request.isHandled())
                                        break;
                                }
                            }

                            if (!_request.isHandled())
                                getServer().handle(this);
                        }
                        finally
                        {
                            _request.setDispatcherType(null);
                        }
                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        _request.setHandled(false);
                        _response.getHttpOutput().reopen();

                        try
                        {
                            _request.setDispatcherType(DispatcherType.ASYNC);
                            getServer().handleAsync(this);
                        }
                        finally
                        {
                            _request.setDispatcherType(null);
                        }
                        break;
                    }

                    case ERROR_DISPATCH:
                    {
                        if (_response.isCommitted())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform Error Dispatch because the response is already committed, aborting");
                            _transport.abort((Throwable)_request.getAttribute(ERROR_EXCEPTION));
                        }
                        else
                        {
                            _response.reset();
                            Integer icode = (Integer)_request.getAttribute(ERROR_STATUS_CODE);
                            int code = icode != null ? icode : HttpStatus.INTERNAL_SERVER_ERROR_500;
                            _response.setStatus(code);
                            _request.setAttribute(ERROR_STATUS_CODE,code);
                            if (icode==null)
                                _request.setAttribute(ERROR_STATUS_CODE,code);
                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();

                            try
                            {
                                _request.setDispatcherType(DispatcherType.ERROR);
                                getServer().handle(this);
                            }
                            finally
                            {
                                _request.setDispatcherType(null);
                            }
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_CALLBACK:
                    {
                        ContextHandler handler=_state.getContextHandler();
                        if (handler!=null)
                            handler.handle(_request,_request.getHttpInput());
                        else
                            _request.getHttpInput().run();
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        ContextHandler handler=_state.getContextHandler();
                        if (handler!=null)
                            handler.handle(_request,_response.getHttpOutput());
                        else
                            _response.getHttpOutput().run();
                        break;
                    }

                    case COMPLETE:
                    {
                        if (!_response.isCommitted() && !_request.isHandled())
                        {
                            _response.sendError(HttpStatus.NOT_FOUND_404);
                        }
                        else
                        {
                            // RFC 7230, section 3.3.
                            int status = _response.getStatus();
                            boolean hasContent = !(_request.isHead() ||
                                    HttpMethod.CONNECT.is(_request.getMethod()) && status == HttpStatus.OK_200 ||
                                    HttpStatus.isInformational(status) ||
                                    status == HttpStatus.NO_CONTENT_204 ||
                                    status == HttpStatus.NOT_MODIFIED_304);
                            if (hasContent && !_response.isContentComplete(_response.getHttpOutput().getWritten()))
                                _transport.abort(new IOException("insufficient content written"));
                        }
                        _response.closeOutput();
                        _request.setHandled(true);

                        _state.onComplete();

                        onCompleted();

                        break loop;
                    }

                    default:
                    {
                        throw new IllegalStateException("state="+_state);
                    }
                }
            }
            catch (Throwable failure)
            {
                if ("org.eclipse.jetty.continuation.ContinuationThrowable".equals(failure.getClass().getName()))
                    LOG.ignore(failure);
                else
                    handleException(failure);
            }

            action = _state.unhandle();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} handle exit, result {}", this, action);

        boolean suspended=action==Action.WAIT;
        return !suspended;
    }

    protected void sendError(int code, String reason)
    {
        try
        {
            _response.sendError(code, reason);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not send error " + code + " " + reason, x);
        }
        finally
        {
            _state.errorComplete();
        }
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param failure the Throwable that caused the problem
     */
    protected void handleException(Throwable failure)
    {
        // Unwrap wrapping Jetty exceptions.
        if (failure instanceof RuntimeIOException)
            failure = failure.getCause();

        if (failure instanceof QuietServletException || !getServer().isRunning())
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_request.getRequestURI(), failure);
        }
        else if (failure instanceof BadMessageException)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_request.getRequestURI(), failure);
            else
                LOG.warn("{} {}",_request.getRequestURI(), failure);
        }
        else
        {
            LOG.warn(_request.getRequestURI(), failure);
        }

        try
        {
            try
            {
                _state.onError(failure);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                // Error could not be handled, probably due to error thrown from error dispatch
                if (_response.isCommitted())
                {
                    LOG.warn("ERROR Dispatch failed: ",failure);
                    _transport.abort(failure);
                }
                else
                {
                    // Minimal response
                    Integer code=(Integer)_request.getAttribute(ERROR_STATUS_CODE);
                    _response.reset();
                    _response.setStatus(code == null ? 500 : code);
                    _response.flushBuffer();
                }
            }
        }
        catch(Exception e)
        {
            failure.addSuppressed(e);
            LOG.warn("ERROR Dispatch failed: ",failure);
            _transport.abort(failure);
        }
    }

    public boolean isExpecting100Continue()
    {
        return false;
    }

    public boolean isExpecting102Processing()
    {
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s,c=%b,a=%s,uri=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _committed.get(),
                _state.getState(),
                _request.getHttpURI());
    }

    public void onRequest(MetaData.Request request)
    {
        _requests.incrementAndGet();
        _request.setTimeStamp(System.currentTimeMillis());
        HttpFields fields = _response.getHttpFields();
        if (_configuration.getSendDateHeader() && !fields.contains(HttpHeader.DATE))
            fields.put(_connector.getServer().getDateField());

        long idleTO=_configuration.getIdleTimeout();
        _oldIdleTimeout=getIdleTimeout();
        if (idleTO>=0 && _oldIdleTimeout!=idleTO)
            setIdleTimeout(idleTO);

        _request.setMetaData(request);

        if (LOG.isDebugEnabled())
            LOG.debug("REQUEST for {} on {}{}{} {} {}{}{}",request.getURIString(),this,System.lineSeparator(),
                    request.getMethod(),request.getURIString(),request.getHttpVersion(),System.lineSeparator(),
                    request.getFields());
    }

    public boolean onContent(HttpInput.Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} content {}", this, content);

        return _request.getHttpInput().addContent(content);
    }

    public boolean onRequestComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onRequestComplete", this);
        return _request.getHttpInput().eof();
    }

    public void onCompleted()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("COMPLETE for {} written={}",getRequest().getRequestURI(),getBytesWritten());

        if (_requestLog!=null )
            _requestLog.log(_request, _response);

        long idleTO=_configuration.getIdleTimeout();
        if (idleTO>=0 && getIdleTimeout()!=_oldIdleTimeout)
            setIdleTimeout(_oldIdleTimeout);

        _transport.onCompleted();
    }

    public boolean onEarlyEOF()
    {
        return _request.getHttpInput().earlyEOF();
    }

    public void onBadMessage(int status, String reason)
    {
        if (status < 400 || status > 599)
            status = HttpStatus.BAD_REQUEST_400;

        Action action;
        try
        {
            action=_state.handling();
        }
        catch(IllegalStateException e)
        {
            // The bad message cannot be handled in the current state, so throw
            // to hopefull somebody that can handle
            abort(e);
            throw new BadMessageException(status,reason);
        }

        try
        {
            if (action==Action.DISPATCH)
            {
                ByteBuffer content=null;
                HttpFields fields=new HttpFields();

                ErrorHandler handler=getServer().getBean(ErrorHandler.class);
                if (handler!=null)
                    content=handler.badMessageError(status,reason,fields);

                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1,status,reason,fields,BufferUtil.length(content)),content ,true);
            }
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
        finally
        {
            // TODO: review whether it's the right state to check.
            if (_state.unhandle()==Action.COMPLETE)
                _state.onComplete();
            else
                throw new IllegalStateException(); // TODO: don't throw from finally blocks !
            onCompleted();
        }
    }

    protected boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete, final Callback callback)
    {
        boolean committing = _committed.compareAndSet(false, true);

        if (LOG.isDebugEnabled())
            LOG.debug("sendResponse info={} content={} complete={} committing={} callback={}",
                    info,
                    BufferUtil.toDetailString(content),
                    complete,
                    committing,
                    callback);

        if (committing)
        {
            // We need an info to commit
            if (info==null)
                info = _response.newResponseMetaData();
            commit(info);

            // wrap callback to process 100 responses
            final int status=info.getStatus();
            final Callback committed = (status<200&&status>=100)?new Commit100Callback(callback):new CommitCallback(callback);

            // committing write
            _transport.send(info, _request.isHead(), content, complete, committed);
        }
        else if (info==null)
        {
            // This is a normal write
            _transport.send(null,_request.isHead(), content, complete, callback);
        }
        else
        {
            callback.failed(new IllegalStateException("committed"));
        }
        return committing;
    }

    protected boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete) throws IOException
    {
        try(Blocker blocker = _response.getHttpOutput().acquireWriteBlockingCallback())
        {
            boolean committing = sendResponse(info,content,complete,blocker);
            blocker.block();
            return committing;
        }
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(failure);
            abort(failure);
            throw failure;
        }
    }

    protected void commit (MetaData.Response info)
    {
        _committedMetaData=info;
        if (LOG.isDebugEnabled())
            LOG.debug("COMMIT for {} on {}{}{} {} {}{}{}",getRequest().getRequestURI(),this,System.lineSeparator(),
                    info.getStatus(),info.getReason(),info.getHttpVersion(),System.lineSeparator(),
                    info.getFields());
    }

    public boolean isCommitted()
    {
        return _committed.get();
    }

    /**
     * <p>Non-Blocking write, committing the response if needed.</p>
     * Called as last link in HttpOutput.Filter chain
     * @param content  the content buffer to write
     * @param complete whether the content is complete for the response
     * @param callback Callback when complete or failed
     */
    @Override
    public void write(ByteBuffer content, boolean complete, Callback callback)
    {
        _written+=BufferUtil.length(content);
        sendResponse(null,content,complete,callback);
    }

    @Override
    public void resetBuffer()
    {
        if(isCommitted())
            throw new IllegalStateException("Committed");
    }

    public HttpOutput.Interceptor getNextInterceptor()
    {
        return null;
    }

    protected void execute(Runnable task)
    {
        _executor.execute(task);
    }

    public Scheduler getScheduler()
    {
        return _connector.getScheduler();
    }

    /**
     * @return true if the HttpChannel can efficiently use direct buffer (typically this means it is not over SSL or a multiplexed protocol)
     */
    public boolean useDirectBuffers()
    {
        return getEndPoint() instanceof ChannelEndPoint;
    }

    /**
     * If a write or similar operation to this channel fails,
     * then this method should be called.
     * <p>
     * The standard implementation calls {@link HttpTransport#abort(Throwable)}.
     *
     * @param failure the failure that caused the abort.
     */
    public void abort(Throwable failure)
    {
        _transport.abort(failure);
    }

    private class CommitCallback extends Callback.Nested
    {
        private CommitCallback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void failed(final Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Commit failed", x);

            if (x instanceof BadMessageException)
            {
                _transport.send(HttpGenerator.RESPONSE_500_INFO, false, null, true, new Callback.Nested(this)
                {
                    @Override
                    public void succeeded()
                    {
                        super.failed(x);
                        _response.getHttpOutput().closed();
                    }

                    @Override
                    public void failed(Throwable th)
                    {
                        _transport.abort(x);
                        super.failed(x);
                    }
                });
            }
            else
            {
                _transport.abort(x);
                super.failed(x);
            }
        }
    }

    private class Commit100Callback extends CommitCallback
    {
        private Commit100Callback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void succeeded()
        {
            if (_committed.compareAndSet(true, false))
                super.succeeded();
            else
                super.failed(new IllegalStateException());
        }

    }


}
