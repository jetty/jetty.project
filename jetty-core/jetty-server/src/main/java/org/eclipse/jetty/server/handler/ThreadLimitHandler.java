//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Handler to limit the threads per IP address for DOS protection</p>
 * <p>The ThreadLimitHandler applies a limit to the number of Threads
 * that can be used simultaneously per remote IP address.
 * </p>
 * <p>The handler makes a determination of the remote IP separately to
 * any that may be made by the {@link ForwardedRequestCustomizer} or similar:
 * <ul>
 * <li>This handler will use either only a single style
 * of forwarded header.   This is on the assumption that a trusted local proxy
 * will produce only a single forwarded header and that any additional
 * headers are likely from untrusted client side proxies.</li>
 * <li>If multiple instances of a forwarded header are provided, this
 * handler will use the right-most instance, which will have been set from
 * the trusted local proxy</li>
 * </ul>
 * Requests in excess of the limit will be asynchronously suspended until
 * a thread is available.
 * <p>This is a simpler alternative to DosFilter</p>
 */
public class ThreadLimitHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ThreadLimitHandler.class);

    private final boolean _rfc7239;
    private final String _forwardedHeader;
    private final IncludeExcludeSet<String, InetAddress> _includeExcludeSet = new IncludeExcludeSet<>(InetAddressSet.class);
    private final ConcurrentMap<String, Remote> _remotes = new ConcurrentHashMap<>();
    private volatile boolean _enabled;
    private int _threadLimit = 10;

    public ThreadLimitHandler()
    {
        this(null, false);
    }

    public ThreadLimitHandler(@Name("forwardedHeader") String forwardedHeader)
    {
        this(forwardedHeader, HttpHeader.FORWARDED.is(forwardedHeader));
    }

    public ThreadLimitHandler(@Name("forwardedHeader") String forwardedHeader, @Name("rfc7239") boolean rfc7239)
    {
        super();
        _rfc7239 = rfc7239;
        _forwardedHeader = forwardedHeader;
        _enabled = true;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        LOG.info(String.format("ThreadLimitHandler enable=%b limit=%d include=%s", _enabled, _threadLimit, _includeExcludeSet));
    }

    @ManagedAttribute("true if this handler is enabled")
    public boolean isEnabled()
    {
        return _enabled;
    }

    public void setEnabled(boolean enabled)
    {
        _enabled = enabled;
        LOG.info(String.format("ThreadLimitHandler enable=%b limit=%d include=%s", _enabled, _threadLimit, _includeExcludeSet));
    }

    @ManagedAttribute("The maximum threads that can be dispatched per remote IP")
    public int getThreadLimit()
    {
        return _threadLimit;
    }

    protected int getThreadLimit(String ip)
    {
        if (!_includeExcludeSet.isEmpty())
        {
            try
            {
                if (!_includeExcludeSet.test(InetAddress.getByName(ip)))
                {
                    LOG.debug("excluded {}", ip);
                    return 0;
                }
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }
        }
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
        _includeExcludeSet.include(inetAddressPattern);
    }

    @ManagedOperation("Exclude IP from thread limits")
    public void exclude(String inetAddressPattern)
    {
        _includeExcludeSet.exclude(inetAddressPattern);
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return;

        if (!_enabled)
        {
            next.process(request, response, callback);
            return;
        }

        // Get the remote address of the request
        Remote remote = getRemote(request);
        if (remote == null)
        {
            // if remote is not known, handle normally
            next.process(request, response, callback);
            return;
        }

        // We accept the request and will always process it.
        request.accept();
        LimitedRequest limitedRequest = new LimitedRequest(remote, next, request, response, callback);
        limitedRequest.process();
    }

    private static void getAndClose(CompletableFuture<Closeable> cf)
    {
        try
        {
            LOG.debug("getting {}", cf);
            Closeable closeable = cf.get();
            LOG.debug("closing {}", closeable);
            closeable.close();
        }
        catch (IOException | InterruptedException | ExecutionException e)
        {
            LOG.warn("Error closing permit enclosed in {}", cf, e);
        }
    }

    private Remote getRemote(Request baseRequest)
    {
        String ip = getRemoteIP(baseRequest);
        LOG.debug("ip={}", ip);
        if (ip == null)
            return null;

        int limit = getThreadLimit(ip);
        if (limit <= 0)
            return null;

        Remote remote = _remotes.get(ip);
        if (remote == null)
        {
            Remote r = new Remote(ip, limit);
            remote = _remotes.putIfAbsent(ip, r);
            if (remote == null)
                remote = r;
        }
        return remote;
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

    private static class LimitedRequest extends Request.AcceptingWrapper implements Runnable
    {
        // TODO apply the thread limit to demand and write callbacks

        private final Remote _remote;
        private final Handler _handler;
        private final LimitedResponse _response;
        private final Callback _callback;

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

        protected void process() throws Exception
        {
            CompletableFuture<Closeable> futurePermit = _remote.acquire();
            Callback callback = Callback.from(_callback, () -> getAndClose(futurePermit));

            // Did we get a permit?
            if (futurePermit.isDone())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Threadpermitted {}", _remote);
                process(callback);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Threadlimited {}", _remote);
                futurePermit.thenAccept(c ->
                {
                    try
                    {
                        process(callback);
                    }
                    catch (Throwable x)
                    {
                        _callback.failed(x);
                    }
                });
            }
        }

        protected void process(Callback callback) throws Exception
        {
            _handler.process(this, _response, callback);
            if (!isAccepted())
            {
                accept();
                Response.writeError(this, _response, callback, 404);
            }
        }

        @Override
        public Content.Chunk read()
        {
            if (!isAccepted())
                throw new IllegalStateException("!accepted");
            return super.read();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (!isAccepted())
                throw new IllegalStateException("!accepted");
            // TODO the demand callback needs to re acquire a permit ( IFF in is not called back in scope )
            super.demand(demandCallback);
        }

        @Override
        public void run()
        {
            try
            {
                process();
                if (!isAccepted())
                    Response.writeError(getWrapped(), getResponse(), getCallback(), 404);
            }
            catch (Throwable t)
            {
                Response.writeError(getWrapped(), getResponse(), getCallback(), t);
            }
        }
    }

    private static class LimitedResponse extends Response.Wrapper
    {
        public LimitedResponse(LimitedRequest limitedRequest, Response response)
        {
            super(limitedRequest, response);
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            // TODO the callback needs to re-acquire a permit (IFF not callback in scope).
            super.write(last, byteBuffer, callback);
        }
    }

    private static final class Remote implements Closeable
    {
        private final String _ip;
        private final int _limit;
        private final AutoLock _lock = new AutoLock();
        private int _permits;
        private final Deque<CompletableFuture<Closeable>> _queue = new ArrayDeque<>();
        private final CompletableFuture<Closeable> _permitted = CompletableFuture.completedFuture(this);

        public Remote(String ip, int limit)
        {
            _ip = ip;
            _limit = limit;
        }

        public CompletableFuture<Closeable> acquire()
        {
            try (AutoLock lock = _lock.lock())
            {
                // Do we have available passes?
                if (_permits < _limit)
                {
                    // Yes - increment the allocated passes
                    _permits++;
                    // return the already completed future
                    return _permitted; // TODO is it OK to share/reuse this?
                }

                // No pass available, so queue a new future 
                CompletableFuture<Closeable> pass = new CompletableFuture<>();
                _queue.addLast(pass);
                return pass;
            }
        }

        @Override
        public void close()
        {
            try (AutoLock lock = _lock.lock())
            {
                // reduce the allocated passes
                _permits--;
                while (true)
                {
                    // Are there any future passes waiting?
                    CompletableFuture<Closeable> permit = _queue.pollFirst();

                    // No - we are done
                    if (permit == null)
                        break;

                    // Yes - if we can complete them, we are done
                    if (permit.complete(this))
                    {
                        _permits++;
                        break;
                    }

                    // Somebody else must have completed/failed that future pass,
                    // so let's try for another.
                }
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
        protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
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
