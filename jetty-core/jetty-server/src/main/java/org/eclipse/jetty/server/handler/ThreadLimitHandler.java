//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Handler to limit the threads per IP address for DOS protection</p>
 * <p>The ThreadLimitHandler applies a limit to the number of Threads
 * that can be used simultaneously per remote IP address.</p>
 * <p>The handler makes a determination of the remote IP separately to
 * any that may be made by the {@link ForwardedRequestCustomizer} or similar:</p>
 * <ul>
 * <li>This handler will use only a single style of forwarded header.
 * This is on the assumption that a trusted local proxy
 * will produce only a single forwarded header and that any additional
 * headers are likely from untrusted client side proxies.</li>
 * <li>If multiple instances of a forwarded header are provided, this
 * handler will use the right-most instance, which will have been set from
 * the trusted local proxy</li>
 * </ul>
 * <p>Requests in excess of the limit will be asynchronously suspended until
 * a thread is available.</p>
 */
public class ThreadLimitHandler extends ConditionalHandler.Abstract
{
    private static final Logger LOG = LoggerFactory.getLogger(ThreadLimitHandler.class);

    private final boolean _rfc7239;
    private final String _forwardedHeader;
    private final ConcurrentHashMap<String, Remote> _remotes = new ConcurrentHashMap<>();
    private volatile boolean _enabled;
    private int _threadLimit = 10;

    public ThreadLimitHandler()
    {
        this(null, null, true);
    }

    public ThreadLimitHandler(@Name("forwardedHeader") String forwardedHeader)
    {
        this(null, forwardedHeader, HttpHeader.FORWARDED.is(forwardedHeader));
    }

    public ThreadLimitHandler(@Name("forwardedHeader") String forwardedHeader, @Name("rfc7239") boolean rfc7239)
    {
        this(null, forwardedHeader, rfc7239);
    }

    public ThreadLimitHandler(@Name("handler") Handler handler, @Name("forwardedHeader") String forwardedHeader, @Name("rfc7239") boolean rfc7239)
    {
        super(handler);
        _rfc7239 = rfc7239;
        _forwardedHeader = forwardedHeader;
        _enabled = true;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        LOG.info(String.format("ThreadLimitHandler enable=%b limit=%d", _enabled, _threadLimit));
    }

    @ManagedAttribute("true if this handler is enabled")
    public boolean isEnabled()
    {
        return _enabled;
    }

    public void setEnabled(boolean enabled)
    {
        _enabled = enabled;
        LOG.info(String.format("ThreadLimitHandler enable=%b limit=%d", _enabled, _threadLimit));
    }

    @ManagedAttribute("The maximum threads that can be dispatched per remote IP")
    public int getThreadLimit()
    {
        return _threadLimit;
    }

    protected int getThreadLimit(String ip)
    {
        return _threadLimit;
    }

    public void setThreadLimit(int threadLimit)
    {
        if (threadLimit <= 0)
            throw new IllegalArgumentException("limit must be >0");
        _threadLimit = threadLimit;
    }

    @ManagedOperation("Include IP in thread limits")
    public void include(String inetAddressPattern)
    {
        includeInetAddressPattern(inetAddressPattern);
    }

    @ManagedOperation("Exclude IP from thread limits")
    public void exclude(String inetAddressPattern)
    {
        excludeInetAddressPattern(inetAddressPattern);
    }

    @Override
    public boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        if (!_enabled)
            return next.handle(request, response, callback);

        // Get the remote address of the request
        Remote remote = getRemote(request);
        if (remote == null)
        {
            // if remote is not known, handle normally
            return next.handle(request, response, callback);
        }

        // We accept the request and will always handle it.
        // Use a compute method to remove the Remote instance as it is necessary for
        // the ref counter release and the removal to be atomic.
        LimitedRequest limitedRequest = new LimitedRequest(remote, next, request, response, Callback.from(callback, () ->
            _remotes.computeIfPresent(remote._ip, (k, v) -> v._referenceCounter.release() ? null : v)));
        limitedRequest.handle();
        return true;
    }

    @Override
    protected boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception
    {
        return nextHandler(request, response, callback);
    }

    private Remote getRemote(Request baseRequest)
    {
        String ip = getRemoteIP(baseRequest);
        if (LOG.isDebugEnabled())
            LOG.debug("ip={}", ip);
        if (ip == null)
            return null;

        int limit = getThreadLimit(ip);
        if (limit <= 0)
            return null;

        // Use a compute method to create or retain the Remote instance as it is necessary for
        // the ref counter increment or the instance creation to be mutually exclusive.
        // The map MUST be a CHM as it guarantees the remapping function is only called once.
        return _remotes.compute(ip, (k, v) ->
        {
            if (v != null)
            {
                v._referenceCounter.retain();
                return v;
            }
            return new Remote(baseRequest.getContext(), k, limit);
        });
    }

    protected String getRemoteIP(Request baseRequest)
    {
        // Do we have a forwarded header set?
        if (_forwardedHeader != null && !_forwardedHeader.isEmpty())
        {
            // Yes, then try to get the remote IP from the header
            String remote = _rfc7239 ? getForwarded(baseRequest) : getXForwardedFor(baseRequest);
            if (remote != null && !remote.isEmpty())
                return remote;
        }

        // If no remote IP from a header, determine it directly from the channel
        // Do not use the request methods, as they may have been lied to by the
        // RequestCustomizer!
        if (baseRequest.getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress inetAddr)
        {
            // TODO ????
            if (inetAddr.getAddress() != null)
                return inetAddr.getAddress().getHostAddress();
        }
        return null;
    }

    private String getForwarded(Request request)
    {
        // Get the right most Forwarded for value.
        // This is the value from the closest proxy and the only one that
        // can be trusted.
        RFC7239 rfc7239 = new RFC7239();
        for (HttpField field : request.getHeaders())
        {
            if (_forwardedHeader.equalsIgnoreCase(field.getName()))
                rfc7239.addValue(field.getValue());
        }

        if (rfc7239.getFor() != null)
            return new HostPortHttpField(rfc7239.getFor()).getHost();

        return null;
    }

    private String getXForwardedFor(Request request)
    {
        // Get the right most XForwarded-For for value.
        // This is the value from the closest proxy and the only one that
        // can be trusted.
        String forwardedFor = null;
        for (HttpField field : request.getHeaders())
        {
            if (_forwardedHeader.equalsIgnoreCase(field.getName()))
                forwardedFor = field.getValue();
        }

        if (forwardedFor == null || forwardedFor.isEmpty())
            return null;

        int comma = forwardedFor.lastIndexOf(',');
        return (comma >= 0) ? forwardedFor.substring(comma + 1).trim() : forwardedFor;
    }

    int getRemoteCount()
    {
        return _remotes.size();
    }

    private static class LimitedRequest extends Request.Wrapper
    {
        private final Remote _remote;
        private final Handler _handler;
        private final LimitedResponse _response;
        private final Callback _callback;
        private final AtomicReference<Runnable> _onContent = new AtomicReference<>();

        public LimitedRequest(Remote remote, Handler handler, Request request, Response response, Callback callback)
        {
            super(request);
            _remote = remote;
            _handler = Objects.requireNonNull(handler);
            _response = new LimitedResponse(this, response);
            _callback = Objects.requireNonNull(callback);
        }

        protected Handler getHandler()
        {
            return _handler;
        }

        protected Response getResponse()
        {
            return _response;
        }

        protected Callback getCallback()
        {
            return _callback;
        }

        protected void handle() throws Exception
        {
            Permit permit = _remote.acquire();

            // Did we get a permit?
            if (permit.isAllocated())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Thread permitted {} {} {}", _remote, getWrapped(), _handler);
                handle(permit);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Thread limited {} {} {}", _remote, getWrapped(), _handler);
                permit.whenAllocated(this::handle);
            }
        }

        protected void handle(Permit permit)
        {
            try
            {
                if (!_handler.handle(this, _response, _callback))
                    Response.writeError(this, _response, _callback, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable x)
            {
                _callback.failed(x);
            }
            finally
            {
                permit.release();
            }
        }

        @Override
        public void demand(Runnable onContent)
        {
            if (!_onContent.compareAndSet(null, Objects.requireNonNull(onContent)))
                throw new IllegalStateException("Pending demand");
            super.demand(new DemandCallback(onContent));
        }

        private void onContent()
        {
            Permit permit = _remote.acquire();
            if (permit.isAllocated())
                onPermittedContent(permit);
            else
                permit.whenAllocated(this::onPermittedContent);
        }

        private void onPermittedContent(Permit permit)
        {
            try
            {
                Runnable onContent = _onContent.getAndSet(null);
                onContent.run();
            }
            finally
            {
                permit.release();
            }
        }

        private class DemandCallback implements Invocable.Task
        {
            private final Runnable runnable;

            private DemandCallback(Runnable runnable)
            {
                this.runnable = runnable;
            }

            @Override
            public void run()
            {
                onContent();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.getInvocationType(runnable, false);
            }
        }
    }

    private static class LimitedResponse extends Response.Wrapper implements Callback
    {
        private final Remote _remote;
        private final AtomicReference<Callback> _writeCallback = new AtomicReference<>();

        public LimitedResponse(LimitedRequest limitedRequest, Response response)
        {
            super(limitedRequest, response);
            _remote = limitedRequest._remote;
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (!_writeCallback.compareAndSet(null, Objects.requireNonNull(callback)))
                throw new WritePendingException();
            super.write(last, byteBuffer, this);
        }

        @Override
        public void succeeded()
        {
            Permit permit = _remote.acquire();
            if (permit.isAllocated())
                permittedSuccess(permit);
            else
                permit.whenAllocated(this::permittedSuccess);
        }

        private void permittedSuccess(Permit permit)
        {
            try
            {
                _writeCallback.getAndSet(null).succeeded();
            }
            finally
            {
                permit.release();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            Permit permit = _remote.acquire();
            if (permit.isAllocated())
                permittedFailure(permit, x);
            else
                permit.whenAllocated(p -> permittedFailure(p, x));
        }

        private void permittedFailure(Permit permit, Throwable x)
        {
            try
            {
                _writeCallback.getAndSet(null).failed(x);
            }
            finally
            {
                permit.release();
            }
        }
    }

    private interface Permit
    {
        boolean isAllocated();

        void whenAllocated(Consumer<Permit> permitConsumer);

        void release();
    }

    private static class NoopPermit implements Permit
    {
        @Override
        public boolean isAllocated()
        {
            return true;
        }

        @Override
        public void whenAllocated(Consumer<Permit> permitConsumer)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release()
        {
        }
    }

    private static class AllocatedPermit implements Permit
    {
        private final Remote _remote;

        private AllocatedPermit(Remote remote)
        {
            _remote = remote;
        }

        @Override
        public boolean isAllocated()
        {
            return true;
        }

        @Override
        public void whenAllocated(Consumer<Permit> permitConsumer)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release()
        {
            _remote.release();
        }

        @Override
        public String toString()
        {
            return "AllocatedPermit:" + _remote;
        }
    }

    private static class FuturePermit implements Permit
    {
        private final CompletableFuture<Permit> _future = new CompletableFuture<>();
        private final Remote _remote;

        private FuturePermit(Remote remote)
        {
            _remote = remote;
        }

        public boolean isAllocated()
        {
            return _future.isDone();
        }

        public void whenAllocated(Consumer<Permit> permitConsumer)
        {
            _future.thenAccept(permitConsumer);
        }

        void complete()
        {
            if (!_future.complete(this))
                throw new IllegalStateException();
        }

        public void release()
        {
            _remote.release();
        }
    }

    private static final class Remote
    {
        private final Executor _executor;
        private final Retainable.ReferenceCounter _referenceCounter = new Retainable.ReferenceCounter();
        private final String _ip;
        private final int _limit;
        private final AutoLock _lock = new AutoLock();
        private int _permits;
        private final Deque<FuturePermit> _queue = new ArrayDeque<>();
        private final Permit _permitted = new AllocatedPermit(this);
        private final ThreadLocal<Boolean> _threadPermit = new ThreadLocal<>();
        private static final Permit NOOP = new NoopPermit();

        public Remote(Executor executor, String ip, int limit)
        {
            _executor = executor;
            _ip = ip;
            _limit = limit;
        }

        Permit acquire()
        {
            try (AutoLock lock = _lock.lock())
            {
                // Does this thread already have an available pass
                if (_threadPermit.get() == Boolean.TRUE)
                    return NOOP;

                // Do we have available passes?
                if (_permits < _limit)
                {
                    // Yes - increment the allocated passes
                    _permits++;
                    _threadPermit.set(Boolean.TRUE);
                    // return the already completed future
                    return _permitted;
                }

                // No pass available, so queue a new future

                FuturePermit futurePermit = new FuturePermit(this);
                _queue.addLast(futurePermit);
                return futurePermit;
            }
        }

        public void release()
        {
            FuturePermit pending;

            try (AutoLock lock = _lock.lock())
            {
                // reduce the allocated passes
                _permits--;
                _threadPermit.set(Boolean.FALSE);
                // Are there any future passes pending?
                pending = _queue.pollFirst();

                // yes, allocate them a permit
                if (pending != null)
                    _permits++;
            }

            if (pending != null)
            {
                // We cannot complete the pending in this thread, as we may be in handle(), demand() or write
                // callback that is serialized and other actions are waiting for the return. Thus, we must execute.
                _executor.execute(pending::complete);
            }
        }

        @Override
        public String toString()
        {
            try (AutoLock lock = _lock.lock())
            {
                return String.format("R[ip=%s,p=%d,l=%d,q=%d]", _ip, _permits, _limit, _queue.size());
            }
        }
    }

    private static final class RFC7239 extends QuotedCSV
    {
        String _for;

        private RFC7239()
        {
            super(false);
        }

        String getFor()
        {
            return _for;
        }

        @Override
        protected void parsedParam(StringBuilder buffer, int valueLength, int paramName, int paramValue)
        {
            if (valueLength == 0 && paramValue > paramName)
            {
                String name = StringUtil.asciiToLowerCase(buffer.substring(paramName, paramValue - 1));
                if ("for".equalsIgnoreCase(name))
                {
                    String value = buffer.substring(paramValue);

                    // if unknown, clear any leftward values
                    if ("unknown".equalsIgnoreCase(value))
                        _for = null;
                        // Otherwise accept IP or token(starting with '_') as remote keys
                    else
                        _for = value;
                }
            }
        }
    }
}
