//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
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

/**
 * HttpChannel represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both a HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
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
    private HttpFields _trailers;
    private final Supplier<HttpFields> _trailerSupplier = () -> _trailers;
    private final List<Listener> _listeners;
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

        List<Listener> listeners = new ArrayList<>();
        if (connector != null)
            listeners.addAll(connector.getBeans(Listener.class));
        _listeners = listeners;

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{},{}",
                    this,
                    _endPoint,
                    _endPoint==null?null:_endPoint.getConnection(),
                    _state);
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
        _trailers=null;
        _oldIdleTimeout=0;
    }

    public void onAsyncWaitForContent()
    {
    }

    public void onBlockWaitForContent()
    {
    }

    public void onBlockWaitForContentFailure(Throwable failure)
    {
        getRequest().getHttpInput().failed(failure);
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
                            notifyBeforeDispatch(_request);

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
                        catch (Throwable x)
                        {
                            notifyDispatchFailure(_request, x);
                            throw x;
                        }
                        finally
                        {
                            notifyAfterDispatch(_request);
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
                            notifyBeforeDispatch(_request);
                            getServer().handleAsync(this);
                        }
                        catch (Throwable x)
                        {
                            notifyDispatchFailure(_request, x);
                            throw x;
                        }
                        finally
                        {
                            notifyAfterDispatch(_request);
                            _request.setDispatcherType(null);
                        }
                        break;
                    }

                    case ERROR_DISPATCH:
                    {
                        try
                        {
                            _response.reset(true);
                            Integer icode = (Integer)_request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                            int code = icode != null ? icode : HttpStatus.INTERNAL_SERVER_ERROR_500;
                            _response.setStatus(code);
                            _request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,code);
                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();

                            try
                            {
                                _request.setDispatcherType(DispatcherType.ERROR);
                                notifyBeforeDispatch(_request);
                                getServer().handle(this);
                            }
                            catch (Throwable x)
                            {
                                notifyDispatchFailure(_request, x);
                                throw x;
                            }
                            finally
                            {
                                notifyAfterDispatch(_request);
                                _request.setDispatcherType(null);
                            }
                        }
                        catch (Throwable x)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform ERROR dispatch, aborting", x);
                            Throwable failure = (Throwable)_request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                            if (failure==null)
                            {
                                minimalErrorResponse(x);
                            }
                            else
                            {
                                if (failure != x)
                                    failure.addSuppressed(x);
                                minimalErrorResponse(failure);
                            }
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_PRODUCE:
                    {
                        _request.getHttpInput().asyncReadProduce();
                        break;
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
                            {
                                if (isCommitted())
                                    abort(new IOException("insufficient content written"));
                                else
                                    _response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500,"insufficient content written");
                            }
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

        if (failure instanceof QuietException || !getServer().isRunning())
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_request.getRequestURI(), failure);
        }
        else if (failure instanceof BadMessageException | failure instanceof IOException | failure instanceof TimeoutException)
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
            _state.onError(failure);
        }
        catch (Throwable e)
        {
            failure.addSuppressed(e);
            LOG.warn("ERROR dispatch failed", failure);
            // Try to send a minimal response.
            minimalErrorResponse(failure);
        }
    }

    private void minimalErrorResponse(Throwable failure)
    {
        try
        {
            Integer code=(Integer)_request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            _response.reset(true);
            _response.setStatus(code == null ? 500 : code);
            _response.flushBuffer();
        }
        catch (Throwable x)
        {
            failure.addSuppressed(x);
            abort(failure);
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

        request.setTrailerSupplier(_trailerSupplier);
        _request.setMetaData(request);

        _request.setSecure(HttpScheme.HTTPS.is(request.getURI().getScheme()));

        notifyRequestBegin(_request);

        if (LOG.isDebugEnabled())
            LOG.debug("REQUEST for {} on {}{}{} {} {}{}{}",request.getURIString(),this,System.lineSeparator(),
                    request.getMethod(),request.getURIString(),request.getHttpVersion(),System.lineSeparator(),
                    request.getFields());
    }

    public boolean onContent(HttpInput.Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onContent {}", this, content);
        notifyRequestContent(_request, content.getByteBuffer());
        return _request.getHttpInput().addContent(content);
    }

    public boolean onContentComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onContentComplete", this);
        notifyRequestContentEnd(_request);
        return false;
    }

    public void onTrailers(HttpFields trailers)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onTrailers {}", this, trailers);
        _trailers = trailers;
        notifyRequestTrailers(_request);
    }

    public boolean onRequestComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onRequestComplete", this);
        boolean result = _request.getHttpInput().eof();
        notifyRequestEnd(_request);
        return result;
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

        notifyComplete(_request);

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

        notifyRequestFailure(_request, new BadMessageException(status, reason));

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
            final Callback committed = (status<200&&status>=100)?new Commit100Callback(callback):new CommitCallback(callback, content, complete);

            notifyResponseBegin(_request);

            // committing write
            _transport.send(info, _request.isHead(), content, complete, committed);
        }
        else if (info==null)
        {
            // This is a normal write
            _transport.send(null,_request.isHead(), content, complete, new ContentCallback(callback, content, complete));
        }
        else
        {
            callback.failed(new IllegalStateException("committed"));
        }
        return committing;
    }

    public boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete) throws IOException
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
        notifyResponseFailure(_request, failure);
        _transport.abort(failure);
    }

    private void notifyRequestBegin(Request request)
    {
        notifyEvent1(listener -> listener::onRequestBegin, request);
    }

    private void notifyBeforeDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onBeforeDispatch, request);
    }

    private void notifyDispatchFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onDispatchFailure, request, failure);
    }

    private void notifyAfterDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onAfterDispatch, request);
    }

    private void notifyRequestContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onRequestContent, request, content);
    }

    private void notifyRequestContentEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestContentEnd, request);
    }

    private void notifyRequestTrailers(Request request)
    {
        notifyEvent1(listener -> listener::onRequestTrailers, request);
    }

    private void notifyRequestEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestEnd, request);
    }

    private void notifyRequestFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onRequestFailure, request, failure);
    }

    private void notifyResponseBegin(Request request)
    {
        notifyEvent1(listener -> listener::onResponseBegin, request);
    }

    private void notifyResponseCommit(Request request)
    {
        notifyEvent1(listener -> listener::onResponseCommit, request);
    }

    private void notifyResponseContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onResponseContent, request, content);
    }

    private void notifyResponseEnd(Request request)
    {
        notifyEvent1(listener -> listener::onResponseEnd, request);
    }

    private void notifyResponseFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onResponseFailure, request, failure);
    }

    private void notifyComplete(Request request)
    {
        notifyEvent1(listener -> listener::onComplete, request);
    }

    private void notifyEvent1(Function<Listener, Consumer<Request>> function, Request request)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, ByteBuffer>> function, Request request, ByteBuffer content)
    {
        for (Listener listener : _listeners)
        {
            ByteBuffer view = content.slice();
            try
            {
                function.apply(listener).accept(request, view);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, Throwable>> function, Request request, Throwable failure)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request, failure);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    /**
     * <p>Listener for {@link HttpChannel} events.</p>
     * <p>HttpChannel will emit events for the various phases it goes through while
     * processing a HTTP request and response.</p>
     * <p>Implementations of this interface may listen to those events to track
     * timing and/or other values such as request URI, etc.</p>
     * <p>The events parameters, especially the {@link Request} object, may be
     * in a transient state depending on the event, and not all properties/features
     * of the parameters may be available inside a listener method.</p>
     * <p>It is recommended that the event parameters are <em>not</em> acted upon
     * in the listener methods, or undefined behavior may result. For example, it
     * would be a bad idea to try to read some content from the
     * {@link javax.servlet.ServletInputStream} in listener methods. On the other
     * hand, it is legit to store request attributes in one listener method that
     * may be possibly retrieved in another listener method in a later event.</p>
     * <p>Listener methods are invoked synchronously from the thread that is
     * performing the request processing, and they should not call blocking code
     * (otherwise the request processing will be blocked as well).</p>
     */
    public interface Listener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed.
         *
         * @param request the request object
         */
        public default void onRequestBegin(Request request)
        {
        }

        /**
         * Invoked just before calling the application.
         *
         * @param request the request object
         */
        public default void onBeforeDispatch(Request request)
        {
        }

        /**
         * Invoked when the application threw an exception.
         *
         * @param request the request object
         * @param failure the exception thrown by the application
         */
        public default void onDispatchFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just after the application returns from the first invocation.
         *
         * @param request the request object
         */
        public default void onAfterDispatch(Request request)
        {
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the request content chunk
         */
        public default void onRequestContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the end of the request content is detected.
         *
         * @param request the request object
         */
        public default void onRequestContentEnd(Request request)
        {
        }

        /**
         * Invoked when the request trailers have been parsed.
         *
         * @param request the request object
         */
        public default void onRequestTrailers(Request request)
        {
        }

        /**
         * Invoked when the request has been fully parsed.
         *
         * @param request the request object
         */
        public default void onRequestEnd(Request request)
        {
        }

        /**
         * Invoked when the request processing failed.
         *
         * @param request the request object
         * @param failure the request failure
         */
        public default void onRequestFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just before the response line is written to the network.
         *
         * @param request the request object
         */
        public default void onResponseBegin(Request request)
        {
        }

        /**
         * Invoked just after the response is committed (that is, the response
         * line, headers and possibly some content have been written to the
         * network).
         *
         * @param request the request object
         */
        public default void onResponseCommit(Request request)
        {
        }

        /**
         * Invoked after a response content chunk has been written to the network.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the response content chunk
         */
        public default void onResponseContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the response has been fully written.
         *
         * @param request the request object
         */
        public default void onResponseEnd(Request request)
        {
        }

        /**
         * Invoked when the response processing failed.
         *
         * @param request the request object
         * @param failure the response failure
         */
        public default void onResponseFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete.
         *
         * @param request the request object
         */
        public default void onComplete(Request request)
        {
        }
    }

    private class CommitCallback extends Callback.Nested
    {
        private final ByteBuffer _content;
        private final boolean _complete;

        private CommitCallback(Callback callback, ByteBuffer content, boolean complete)
        {
            super(callback);
            this._content = content == null ? BufferUtil.EMPTY_BUFFER : content.slice();
            this._complete = complete;
        }

        @Override
        public void succeeded()
        {
            super.succeeded();
            notifyResponseCommit(_request);
            if (_content.hasRemaining())
                notifyResponseContent(_request, _content);
            if (_complete)
                notifyResponseEnd(_request);
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
                        abort(x);
                        super.failed(x);
                    }
                });
            }
            else
            {
                abort(x);
                super.failed(x);
            }
        }
    }

    private class Commit100Callback extends CommitCallback
    {
        private Commit100Callback(Callback callback)
        {
            super(callback, null, false);
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

    private class ContentCallback extends Callback.Nested
    {
        private final ByteBuffer _content;
        private final boolean _complete;

        private ContentCallback(Callback callback, ByteBuffer content, boolean complete)
        {
            super(callback);
            this._content = content == null ? BufferUtil.EMPTY_BUFFER : content.slice();
            this._complete = complete;
        }

        @Override
        public void succeeded()
        {
            super.succeeded();
            if (_content.hasRemaining())
                notifyResponseContent(_request, _content);
            if (_complete)
                notifyResponseEnd(_request);
        }
    }
}
