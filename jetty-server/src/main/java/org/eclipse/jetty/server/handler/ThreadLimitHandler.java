//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;

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
public class ThreadLimitHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(ThreadLimitHandler.class);

    private static final String REMOTE = "o.e.j.s.h.TLH.REMOTE";
    private static final String PERMIT = "o.e.j.s.h.TLH.PASS";
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
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Allow ThreadLimit to be enabled dynamically without restarting server
        if (!_enabled)
        {
            // if disabled, handle normally
            super.handle(target, baseRequest, request, response);
        }
        else
        {
            // Get the remote address of the request
            Remote remote = getRemote(baseRequest);
            if (remote == null)
            {
                // if remote is not known, handle normally
                super.handle(target, baseRequest, request, response);
            }
            else
            {
                // Do we already have a future permit from a previous invocation?
                Closeable permit = (Closeable)baseRequest.getAttribute(PERMIT);
                try
                {
                    if (permit != null)
                    {
                        // Yes, remove it from any future async cycles.
                        baseRequest.removeAttribute(PERMIT);
                    }
                    else
                    {
                        // No, then lets try to acquire one
                        CompletableFuture<Closeable> futurePermit = remote.acquire();

                        // Did we get a permit?
                        if (futurePermit.isDone())
                        {
                            // yes
                            permit = futurePermit.get();
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Threadlimited {} {}", remote, target);
                            // No, lets asynchronously suspend the request
                            AsyncContext async = baseRequest.startAsync();
                            // let's never timeout the async.  If this is a DOS, then good to make them wait, if this is not
                            // then give them maximum time to get a thread.
                            async.setTimeout(0);

                            // dispatch the request when we do eventually get a pass
                            futurePermit.thenAccept(c ->
                            {
                                baseRequest.setAttribute(PERMIT, c);
                                async.dispatch();
                            });
                            return;
                        }
                    }

                    // Use the permit
                    super.handle(target, baseRequest, request, response);
                }
                catch (InterruptedException | ExecutionException e)
                {
                    throw new ServletException(e);
                }
                finally
                {
                    if (permit != null)
                        permit.close();
                }
            }
        }
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
                LOG.ignore(e);
            }
        }
        return _threadLimit;
    }

    protected Remote getRemote(Request baseRequest)
    {
        Remote remote = (Remote)baseRequest.getAttribute(REMOTE);
        if (remote != null)
            return remote;

        String ip = getRemoteIP(baseRequest);
        LOG.debug("ip={}", ip);
        if (ip == null)
            return null;

        int limit = getThreadLimit(ip);
        if (limit <= 0)
            return null;

        remote = _remotes.get(ip);
        if (remote == null)
        {
            Remote r = new Remote(ip, limit);
            remote = _remotes.putIfAbsent(ip, r);
            if (remote == null)
                remote = r;
        }

        baseRequest.setAttribute(REMOTE, remote);

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
        InetSocketAddress inetAddr = baseRequest.getHttpChannel().getRemoteAddress();
        if (inetAddr != null && inetAddr.getAddress() != null)
            return inetAddr.getAddress().getHostAddress();
        return null;
    }

    private String getForwarded(Request request)
    {
        // Get the right most Forwarded for value.
        // This is the value from the closest proxy and the only one that
        // can be trusted.
        RFC7239 rfc7239 = new RFC7239();
        HttpFields httpFields = request.getHttpFields();
        for (HttpField field : httpFields)
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
        HttpFields httpFields = request.getHttpFields();
        for (HttpField field : httpFields)
        {
            if (_forwardedHeader.equalsIgnoreCase(field.getName()))
                forwardedFor = field.getValue();
        }

        if (forwardedFor == null || forwardedFor.isEmpty())
            return null;

        int comma = forwardedFor.lastIndexOf(',');
        return (comma >= 0) ? forwardedFor.substring(comma + 1).trim() : forwardedFor;
    }

    private final class Remote implements Closeable
    {
        private final String _ip;
        private final int _limit;
        private final Locker _locker = new Locker();
        private int _permits;
        private Deque<CompletableFuture<Closeable>> _queue = new ArrayDeque<>();
        private final CompletableFuture<Closeable> _permitted = CompletableFuture.completedFuture(this);

        public Remote(String ip, int limit)
        {
            _ip = ip;
            _limit = limit;
        }

        public CompletableFuture<Closeable> acquire()
        {
            try (Locker.Lock lock = _locker.lock())
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
                CompletableFuture<Closeable> pass = new CompletableFuture<Closeable>();
                _queue.addLast(pass);
                return pass;
            }
        }

        @Override
        public void close() throws IOException
        {
            try (Locker.Lock lock = _locker.lock())
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
            try (Locker.Lock lock = _locker.lock())
            {
                return String.format("R[ip=%s,p=%d,l=%d,q=%d]", _ip, _permits, _limit, _queue.size());
            }
        }
    }

    private final class RFC7239 extends QuotedCSV
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
