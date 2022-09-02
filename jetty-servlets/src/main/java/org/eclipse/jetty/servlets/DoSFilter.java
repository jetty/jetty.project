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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Denial of Service filter
 * <p>
 * This filter is useful for limiting
 * exposure to abuse from request flooding, whether malicious, or as a result of
 * a misconfigured client.
 * <p>
 * The filter keeps track of the number of requests from a connection per
 * second. If a limit is exceeded, the request is either rejected, delayed, or
 * throttled.
 * <p>
 * When a request is throttled, it is placed in a priority queue. Priority is
 * given first to authenticated users and users with an HttpSession, then
 * connections which can be identified by their IP addresses. Connections with
 * no way to identify them are given lowest priority.
 * <p>
 * The {@link #extractUserId(ServletRequest request)} function should be
 * implemented, in order to uniquely identify authenticated users.
 * <p>
 * The following init parameters control the behavior of the filter:
 * <dl>
 * <dt>maxRequestsPerSec</dt>
 * <dd>the maximum number of requests from a connection per
 * second. Requests in excess of this are first delayed,
 * then throttled.</dd>
 * <dt>delayMs</dt>
 * <dd>is the delay given to all requests over the rate limit,
 * before they are considered at all. -1 means just reject request,
 * 0 means no delay, otherwise it is the delay.</dd>
 * <dt>maxWaitMs</dt>
 * <dd>how long to blocking wait for the throttle semaphore.</dd>
 * <dt>throttledRequests</dt>
 * <dd>is the number of requests over the rate limit able to be
 * considered at once.</dd>
 * <dt>throttleMs</dt>
 * <dd>how long to async wait for semaphore.</dd>
 * <dt>maxRequestMs</dt>
 * <dd>how long to allow this request to run.</dd>
 * <dt>maxIdleTrackerMs</dt>
 * <dd>how long to keep track of request rates for a connection,
 * before deciding that the user has gone away, and discarding it</dd>
 * <dt>insertHeaders</dt>
 * <dd>if true , insert the DoSFilter headers into the response. Defaults to true.</dd>
 * <dt>trackSessions</dt>
 * <dd>if true, usage rate is tracked by session if a session exists. Defaults to true.</dd>
 * <dt>remotePort</dt>
 * <dd>if true and session tracking is not used, then rate is tracked by IP+port (effectively connection). Defaults to false.</dd>
 * <dt>ipWhitelist</dt>
 * <dd>a comma-separated list of IP addresses that will not be rate limited</dd>
 * <dt>managedAttr</dt>
 * <dd>if set to true, then this servlet is set as a {@link ServletContext} attribute with the
 * filter name as the attribute name.  This allows context external mechanism (eg JMX via {@link ContextHandler#MANAGED_ATTRIBUTES}) to
 * manage the configuration of the filter.</dd>
 * <dt>tooManyCode</dt>
 * <dd>The status code to send if there are too many requests.  By default is 429 (too many requests), but 503 (Unavailable) is
 * another option</dd>
 * </dl>
 * <p>
 * This filter should be configured for {@link DispatcherType#REQUEST} and {@link DispatcherType#ASYNC} and with
 * {@code <async-supported>true</async-supported>}.
 * </p>
 */
@ManagedObject("limits exposure to abuse from request flooding, whether malicious, or as a result of a misconfigured client")
public class DoSFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(DoSFilter.class);

    private static final String IPv4_GROUP = "(\\d{1,3})";
    private static final Pattern IPv4_PATTERN = Pattern.compile(IPv4_GROUP + "\\." + IPv4_GROUP + "\\." + IPv4_GROUP + "\\." + IPv4_GROUP);
    private static final String IPv6_GROUP = "(\\p{XDigit}{1,4})";
    private static final Pattern IPv6_PATTERN = Pattern.compile(IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP + ":" + IPv6_GROUP);
    private static final Pattern CIDR_PATTERN = Pattern.compile("([^/]+)/(\\d+)");

    private static final String __TRACKER = "DoSFilter.Tracker";
    private static final String __THROTTLED = "DoSFilter.Throttled";

    private static final int __DEFAULT_MAX_REQUESTS_PER_SEC = 25;
    private static final int __DEFAULT_DELAY_MS = 100;
    private static final int __DEFAULT_THROTTLE = 5;
    private static final int __DEFAULT_MAX_WAIT_MS = 50;
    private static final long __DEFAULT_THROTTLE_MS = 30000L;
    private static final long __DEFAULT_MAX_REQUEST_MS_INIT_PARAM = 30000L;
    private static final long __DEFAULT_MAX_IDLE_TRACKER_MS_INIT_PARAM = 30000L;

    static final String MANAGED_ATTR_INIT_PARAM = "managedAttr";
    static final String MAX_REQUESTS_PER_S_INIT_PARAM = "maxRequestsPerSec";
    static final String DELAY_MS_INIT_PARAM = "delayMs";
    static final String THROTTLED_REQUESTS_INIT_PARAM = "throttledRequests";
    static final String MAX_WAIT_INIT_PARAM = "maxWaitMs";
    static final String THROTTLE_MS_INIT_PARAM = "throttleMs";
    static final String MAX_REQUEST_MS_INIT_PARAM = "maxRequestMs";
    static final String MAX_IDLE_TRACKER_MS_INIT_PARAM = "maxIdleTrackerMs";
    static final String INSERT_HEADERS_INIT_PARAM = "insertHeaders";
    static final String TRACK_SESSIONS_INIT_PARAM = "trackSessions";
    static final String REMOTE_PORT_INIT_PARAM = "remotePort";
    static final String IP_WHITELIST_INIT_PARAM = "ipWhitelist";
    static final String ENABLED_INIT_PARAM = "enabled";
    static final String TOO_MANY_CODE = "tooManyCode";

    public enum RateType
    {
        AUTH,
        SESSION,
        IP,
        UNKNOWN
    }

    private final String _suspended = "DoSFilter@" + Integer.toHexString(hashCode()) + ".SUSPENDED";
    private final String _resumed = "DoSFilter@" + Integer.toHexString(hashCode()) + ".RESUMED";
    private final ConcurrentHashMap<String, RateTracker> _rateTrackers = new ConcurrentHashMap<>();
    private final List<String> _whitelist = new CopyOnWriteArrayList<>();
    private int _tooManyCode;
    private volatile long _delayMs;
    private volatile long _throttleMs;
    private volatile long _maxWaitMs;
    private volatile long _maxRequestMs;
    private volatile long _maxIdleTrackerMs;
    private volatile boolean _insertHeaders;
    private volatile boolean _trackSessions;
    private volatile boolean _remotePort;
    private volatile boolean _enabled;
    private volatile String _name;
    private DoSFilter.Listener _listener = new Listener();
    private Semaphore _passes;
    private volatile int _throttledRequests;
    private volatile int _maxRequestsPerSec;
    private Map<RateType, Queue<AsyncContext>> _queues = new HashMap<>();
    private Map<RateType, AsyncListener> _listeners = new HashMap<>();
    private Scheduler _scheduler;
    private ServletContext _context;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        for (RateType rateType : RateType.values())
        {
            _queues.put(rateType, new ConcurrentLinkedQueue<>());
            _listeners.put(rateType, new DoSAsyncListener(rateType));
        }

        _rateTrackers.clear();

        int maxRequests = __DEFAULT_MAX_REQUESTS_PER_SEC;
        String parameter = filterConfig.getInitParameter(MAX_REQUESTS_PER_S_INIT_PARAM);
        if (parameter != null)
            maxRequests = Integer.parseInt(parameter);
        setMaxRequestsPerSec(maxRequests);

        long delay = __DEFAULT_DELAY_MS;
        parameter = filterConfig.getInitParameter(DELAY_MS_INIT_PARAM);
        if (parameter != null)
            delay = Long.parseLong(parameter);
        setDelayMs(delay);

        int throttledRequests = __DEFAULT_THROTTLE;
        parameter = filterConfig.getInitParameter(THROTTLED_REQUESTS_INIT_PARAM);
        if (parameter != null)
            throttledRequests = Integer.parseInt(parameter);
        setThrottledRequests(throttledRequests);

        long maxWait = __DEFAULT_MAX_WAIT_MS;
        parameter = filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM);
        if (parameter != null)
            maxWait = Long.parseLong(parameter);
        setMaxWaitMs(maxWait);

        long throttle = __DEFAULT_THROTTLE_MS;
        parameter = filterConfig.getInitParameter(THROTTLE_MS_INIT_PARAM);
        if (parameter != null)
            throttle = Long.parseLong(parameter);
        setThrottleMs(throttle);

        long maxRequestMs = __DEFAULT_MAX_REQUEST_MS_INIT_PARAM;
        parameter = filterConfig.getInitParameter(MAX_REQUEST_MS_INIT_PARAM);
        if (parameter != null)
            maxRequestMs = Long.parseLong(parameter);
        setMaxRequestMs(maxRequestMs);

        long maxIdleTrackerMs = __DEFAULT_MAX_IDLE_TRACKER_MS_INIT_PARAM;
        parameter = filterConfig.getInitParameter(MAX_IDLE_TRACKER_MS_INIT_PARAM);
        if (parameter != null)
            maxIdleTrackerMs = Long.parseLong(parameter);
        setMaxIdleTrackerMs(maxIdleTrackerMs);

        String whiteList = "";
        parameter = filterConfig.getInitParameter(IP_WHITELIST_INIT_PARAM);
        if (parameter != null)
            whiteList = parameter;
        setWhitelist(whiteList);

        parameter = filterConfig.getInitParameter(INSERT_HEADERS_INIT_PARAM);
        setInsertHeaders(parameter == null || Boolean.parseBoolean(parameter));

        parameter = filterConfig.getInitParameter(TRACK_SESSIONS_INIT_PARAM);
        setTrackSessions(parameter == null || Boolean.parseBoolean(parameter));

        parameter = filterConfig.getInitParameter(REMOTE_PORT_INIT_PARAM);
        setRemotePort(parameter != null && Boolean.parseBoolean(parameter));

        parameter = filterConfig.getInitParameter(ENABLED_INIT_PARAM);
        setEnabled(parameter == null || Boolean.parseBoolean(parameter));

        parameter = filterConfig.getInitParameter(TOO_MANY_CODE);
        setTooManyCode(parameter == null ? 429 : Integer.parseInt(parameter));

        setName(filterConfig.getFilterName());
        _context = filterConfig.getServletContext();
        if (_context != null)
        {
            _context.setAttribute(filterConfig.getFilterName(), this);
        }

        _scheduler = startScheduler();
    }

    protected Scheduler startScheduler() throws ServletException
    {
        try
        {
            Scheduler result = new ScheduledExecutorScheduler(String.format("DoS-Scheduler-%x", hashCode()), false);
            result.start();
            return result;
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException
    {
        doFilter((HttpServletRequest)request, (HttpServletResponse)response, filterChain);
    }

    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException
    {
        if (!isEnabled())
        {
            filterChain.doFilter(request, response);
            return;
        }

        // Look for the rate tracker for this request.
        RateTracker tracker = (RateTracker)request.getAttribute(__TRACKER);
        if (tracker != null)
        {
            // Redispatched, RateTracker present in request attributes.
            throttleRequest(request, response, filterChain, tracker);
            return;
        }

        // This is the first time we have seen this request.
        if (LOG.isDebugEnabled())
            LOG.debug("Filtering {}", request);

        // Get a rate tracker associated with this request, and record one hit.
        tracker = getRateTracker(request);

        // Calculate the rate and check if it is over the allowed limit
        final OverLimit overLimit = tracker.isRateExceeded(NanoTime.now());

        // Pass it through if we are not currently over the rate limit.
        if (overLimit == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Allowing {}", request);
            doFilterChain(filterChain, request, response);
            return;
        }

        // We are over the limit.

        // Ask listener what to perform.
        Action action = _listener.onRequestOverLimit(request, overLimit, this);

        // Perform action
        long delayMs = getDelayMs();
        boolean insertHeaders = isInsertHeaders();
        switch (action)
        {
            case NO_ACTION:
                if (LOG.isDebugEnabled())
                    LOG.debug("Allowing over-limit request {}", request);
                doFilterChain(filterChain, request, response);
                break;
            case ABORT:
                if (LOG.isDebugEnabled())
                    LOG.debug("Aborting over-limit request {}", request);
                response.sendError(-1);
                return;
            case REJECT:
                if (insertHeaders)
                    response.addHeader("DoSFilter", "unavailable");
                response.sendError(getTooManyCode());
                return;
            case DELAY:
                // Insert a delay before throttling the request,
                // using the suspend+timeout mechanism of AsyncContext.
                if (insertHeaders)
                    response.addHeader("DoSFilter", "delayed");
                request.setAttribute(__TRACKER, tracker);
                AsyncContext asyncContext = request.startAsync();
                if (delayMs > 0)
                    asyncContext.setTimeout(delayMs);
                asyncContext.addListener(new DoSTimeoutAsyncListener());
                break;
            case THROTTLE:
                throttleRequest(request, response, filterChain, tracker);
                break;
        }
    }

    private void throttleRequest(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain, RateTracker tracker) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Throttling {}", request);

        // Throttle the request.
        boolean accepted = false;
        try
        {
            // Check if we can afford to accept another request at this time.
            accepted = _passes.tryAcquire(getMaxWaitMs(), TimeUnit.MILLISECONDS);
            if (!accepted)
            {
                // We were not accepted, so either we suspend to wait,
                // or if we were woken up we insist or we fail.
                Boolean throttled = (Boolean)request.getAttribute(__THROTTLED);
                long throttleMs = getThrottleMs();
                if (!Boolean.TRUE.equals(throttled) && throttleMs > 0)
                {
                    RateType priority = getPriority(request, tracker);
                    request.setAttribute(__THROTTLED, Boolean.TRUE);
                    if (isInsertHeaders())
                        response.addHeader("DoSFilter", "throttled");
                    AsyncContext asyncContext = request.startAsync();
                    request.setAttribute(_suspended, Boolean.TRUE);
                    asyncContext.setTimeout(throttleMs);
                    asyncContext.addListener(_listeners.get(priority));
                    _queues.get(priority).add(asyncContext);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Throttled {}, {}ms", request, throttleMs);
                    return;
                }

                Boolean resumed = (Boolean)request.getAttribute(_resumed);
                if (Boolean.TRUE.equals(resumed))
                {
                    // We were resumed, we wait for the next pass.
                    _passes.acquire();
                    accepted = true;
                }
            }

            // If we were accepted (either immediately or after throttle)...
            if (accepted)
            {
                // ...call the chain.
                if (LOG.isDebugEnabled())
                    LOG.debug("Allowing {}", request);
                doFilterChain(filterChain, request, response);
            }
            else
            {
                // ...otherwise fail the request.
                if (LOG.isDebugEnabled())
                    LOG.debug("Rejecting {}", request);
                if (isInsertHeaders())
                    response.addHeader("DoSFilter", "unavailable");
                response.sendError(getTooManyCode());
            }
        }
        catch (InterruptedException e)
        {
            LOG.trace("IGNORED", e);
            response.sendError(getTooManyCode());
        }
        finally
        {
            if (accepted)
            {
                try
                {
                    // Wake up the next highest priority request.
                    for (RateType rateType : RateType.values())
                    {
                        AsyncContext asyncContext = _queues.get(rateType).poll();
                        if (asyncContext != null)
                        {
                            ServletRequest candidate = asyncContext.getRequest();
                            Boolean suspended = (Boolean)candidate.getAttribute(_suspended);
                            if (Boolean.TRUE.equals(suspended))
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Resuming {}", request);
                                candidate.setAttribute(_resumed, Boolean.TRUE);
                                asyncContext.dispatch();
                                break;
                            }
                        }
                    }
                }
                finally
                {
                    _passes.release();
                }
            }
        }
    }

    protected void doFilterChain(FilterChain chain, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
    {
        final Thread thread = Thread.currentThread();
        Runnable requestTimeout = () -> onRequestTimeout(request, response, thread);
        Scheduler.Task task = _scheduler.schedule(requestTimeout, getMaxRequestMs(), TimeUnit.MILLISECONDS);
        try
        {
            chain.doFilter(request, response);
        }
        finally
        {
            task.cancel();
        }
    }

    /**
     * Invoked when the request handling exceeds {@link #getMaxRequestMs()}.
     * <p>
     * By default, an HTTP 503 response is returned and the handling thread is interrupted.
     *
     * @param request the current request
     * @param response the current response
     * @param handlingThread the handling thread
     */
    protected void onRequestTimeout(HttpServletRequest request, HttpServletResponse response, Thread handlingThread)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timing out {}", request);
            try
            {
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE_503);
            }
            catch (IllegalStateException ise)
            {
                LOG.trace("IGNORED", ise);
                // abort instead
                response.sendError(-1);
            }
        }
        catch (Throwable x)
        {
            LOG.info("Failed to sendError", x);
        }

        handlingThread.interrupt();
    }

    /**
     * Get priority for this request, based on user type
     *
     * @param request the current request
     * @param tracker the rate tracker for this request
     * @return the priority for this request
     */
    private RateType getPriority(HttpServletRequest request, RateTracker tracker)
    {
        if (extractUserId(request) != null)
            return RateType.AUTH;
        if (tracker != null)
            return tracker.getType();
        return RateType.UNKNOWN;
    }

    /**
     * @return the maximum priority that we can assign to a request
     */
    protected RateType getMaxPriority()
    {
        return RateType.AUTH;
    }

    public void setListener(DoSFilter.Listener listener)
    {
        _listener = Objects.requireNonNull(listener, "Listener may not be null");
    }

    public DoSFilter.Listener getListener()
    {
        return _listener;
    }

    private void schedule(RateTracker tracker)
    {
        _scheduler.schedule(tracker, getMaxIdleTrackerMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Return a request rate tracker associated with this connection; keeps
     * track of this connection's request rate. If this is not the first request
     * from this connection, return the existing object with the stored stats.
     * If it is the first request, then create a new request tracker.
     * <p>
     * Assumes that each connection has an identifying characteristic, and goes
     * through them in order, taking the first that matches: user id (logged
     * in), session id, client IP address. Unidentifiable connections are lumped
     * into one.
     * <p>
     * When a session expires, its rate tracker is automatically deleted.
     *
     * @param request the current request
     * @return the request rate tracker for the current connection
     */
    RateTracker getRateTracker(ServletRequest request)
    {
        HttpSession session = ((HttpServletRequest)request).getSession(false);

        String loadId = extractUserId(request);
        final RateType type;
        if (loadId != null)
        {
            type = RateType.AUTH;
        }
        else
        {
            if (isTrackSessions() && session != null && !session.isNew())
            {
                loadId = session.getId();
                type = RateType.SESSION;
            }
            else
            {
                loadId = isRemotePort() ? createRemotePortId(request) : request.getRemoteAddr();
                type = RateType.IP;
            }
        }

        RateTracker tracker = _rateTrackers.get(loadId);

        if (tracker == null)
        {
            boolean allowed = checkWhitelist(request.getRemoteAddr());
            int maxRequestsPerSec = getMaxRequestsPerSec();
            tracker = allowed ? new FixedRateTracker(_context, _name, loadId, type, maxRequestsPerSec)
                : new RateTracker(_context, _name, loadId, type, maxRequestsPerSec);
            tracker.setContext(_context);
            RateTracker existing = _rateTrackers.putIfAbsent(loadId, tracker);
            if (existing != null)
                tracker = existing;

            if (type == RateType.IP)
            {
                // USER_IP expiration from _rateTrackers is handled by the _scheduler
                _scheduler.schedule(tracker, getMaxIdleTrackerMs(), TimeUnit.MILLISECONDS);
            }
            else if (session != null)
            {
                // USER_SESSION expiration from _rateTrackers are handled by the HttpSessionBindingListener
                session.setAttribute(__TRACKER, tracker);
            }
        }

        return tracker;
    }

    private void addToRateTracker(RateTracker tracker)
    {
        _rateTrackers.put(tracker.getId(), tracker);
    }

    public void removeFromRateTracker(String id)
    {
        _rateTrackers.remove(id);
    }

    protected boolean checkWhitelist(String candidate)
    {
        for (String address : _whitelist)
        {
            if (address.contains("/"))
            {
                if (subnetMatch(address, candidate))
                    return true;
            }
            else
            {
                if (address.equals(candidate))
                    return true;
            }
        }
        return false;
    }

    protected boolean subnetMatch(String subnetAddress, String address)
    {
        Matcher cidrMatcher = CIDR_PATTERN.matcher(subnetAddress);
        if (!cidrMatcher.matches())
            return false;

        String subnet = cidrMatcher.group(1);
        int prefix;
        try
        {
            prefix = Integer.parseInt(cidrMatcher.group(2));
        }
        catch (NumberFormatException x)
        {
            LOG.info("Ignoring malformed CIDR address {}", subnetAddress);
            return false;
        }

        byte[] subnetBytes = addressToBytes(subnet);
        if (subnetBytes == null)
        {
            LOG.info("Ignoring malformed CIDR address {}", subnetAddress);
            return false;
        }
        byte[] addressBytes = addressToBytes(address);
        if (addressBytes == null)
        {
            LOG.info("Ignoring malformed remote address {}", address);
            return false;
        }

        // Comparing IPv4 with IPv6 ?
        int length = subnetBytes.length;
        if (length != addressBytes.length)
            return false;

        byte[] mask = prefixToBytes(prefix, length);

        for (int i = 0; i < length; ++i)
        {
            if ((subnetBytes[i] & mask[i]) != (addressBytes[i] & mask[i]))
                return false;
        }

        return true;
    }

    private byte[] addressToBytes(String address)
    {
        Matcher ipv4Matcher = IPv4_PATTERN.matcher(address);
        if (ipv4Matcher.matches())
        {
            byte[] result = new byte[4];
            for (int i = 0; i < result.length; ++i)
            {
                result[i] = Integer.valueOf(ipv4Matcher.group(i + 1)).byteValue();
            }
            return result;
        }
        else
        {
            Matcher ipv6Matcher = IPv6_PATTERN.matcher(address);
            if (ipv6Matcher.matches())
            {
                byte[] result = new byte[16];
                for (int i = 0; i < result.length; i += 2)
                {
                    int word = Integer.parseInt(ipv6Matcher.group(i / 2 + 1), 16);
                    result[i] = (byte)((word & 0xFF00) >>> 8);
                    result[i + 1] = (byte)(word & 0xFF);
                }
                return result;
            }
        }
        return null;
    }

    private byte[] prefixToBytes(int prefix, int length)
    {
        byte[] result = new byte[length];
        int index = 0;
        while (prefix / 8 > 0)
        {
            result[index] = -1;
            prefix -= 8;
            ++index;
        }

        if (index == result.length)
            return result;

        // Sets the _prefix_ most significant bits to 1
        result[index] = (byte)~((1 << (8 - prefix)) - 1);
        return result;
    }

    @Override
    public void destroy()
    {
        LOG.debug("Destroy {}", this);
        stopScheduler();
        _rateTrackers.clear();
        _whitelist.clear();
    }

    protected void stopScheduler()
    {
        try
        {
            _scheduler.stop();
        }
        catch (Exception x)
        {
            LOG.trace("IGNORED", x);
        }
    }

    /**
     * Returns the user id, used to track this connection.
     * This SHOULD be overridden by subclasses.
     *
     * @param request the current request
     * @return a unique user id, if logged in; otherwise null.
     */
    protected String extractUserId(ServletRequest request)
    {
        return null;
    }

    /**
     * Get maximum number of requests from a connection per
     * second. Requests in excess of this are first delayed,
     * then throttled.
     *
     * @return maximum number of requests
     */
    @ManagedAttribute("maximum number of requests allowed from a connection per second")
    public int getMaxRequestsPerSec()
    {
        return _maxRequestsPerSec;
    }

    /**
     * Get maximum number of requests from a connection per
     * second. Requests in excess of this are first delayed,
     * then throttled.
     *
     * @param value maximum number of requests
     */
    public void setMaxRequestsPerSec(int value)
    {
        _maxRequestsPerSec = value;
    }

    /**
     * Get delay (in milliseconds) that is applied to all requests
     * over the rate limit, before they are considered at all.
     *
     * @return the delay in milliseconds
     */
    @ManagedAttribute("delay applied to all requests over the rate limit (in ms)")
    public long getDelayMs()
    {
        return _delayMs;
    }

    /**
     * Set delay (in milliseconds) that is applied to all requests
     * over the rate limit, before they are considered at all.
     *
     * @param value delay (in milliseconds), 0 - no delay, -1 - reject request
     */
    public void setDelayMs(long value)
    {
        _delayMs = value;
    }

    /**
     * Get maximum amount of time (in milliseconds) the filter will
     * blocking wait for the throttle semaphore.
     *
     * @return maximum wait time
     */
    @ManagedAttribute("maximum time the filter will block waiting throttled connections, (0 for no delay, -1 to reject requests)")
    public long getMaxWaitMs()
    {
        return _maxWaitMs;
    }

    /**
     * Set maximum amount of time (in milliseconds) the filter will
     * blocking wait for the throttle semaphore.
     *
     * @param value maximum wait time
     */
    public void setMaxWaitMs(long value)
    {
        _maxWaitMs = value;
    }

    /**
     * Get number of requests over the rate limit able to be
     * considered at once.
     *
     * @return number of requests
     */
    @ManagedAttribute("number of requests over rate limit")
    public int getThrottledRequests()
    {
        return _throttledRequests;
    }

    /**
     * Set number of requests over the rate limit able to be
     * considered at once.
     *
     * @param value number of requests
     */
    public void setThrottledRequests(int value)
    {
        int permits = _passes == null ? 0 : _passes.availablePermits();
        _passes = new Semaphore((value - _throttledRequests + permits), true);
        _throttledRequests = value;
    }

    /**
     * Get amount of time (in milliseconds) to async wait for semaphore.
     *
     * @return wait time
     */
    @ManagedAttribute("amount of time to async wait for semaphore")
    public long getThrottleMs()
    {
        return _throttleMs;
    }

    /**
     * Set amount of time (in milliseconds) to async wait for semaphore.
     *
     * @param value wait time
     */
    public void setThrottleMs(long value)
    {
        _throttleMs = value;
    }

    /**
     * Get maximum amount of time (in milliseconds) to allow
     * the request to process.
     *
     * @return maximum processing time
     */
    @ManagedAttribute("maximum time to allow requests to process (in ms)")
    public long getMaxRequestMs()
    {
        return _maxRequestMs;
    }

    /**
     * Set maximum amount of time (in milliseconds) to allow
     * the request to process.
     *
     * @param value maximum processing time
     */
    public void setMaxRequestMs(long value)
    {
        _maxRequestMs = value;
    }

    /**
     * Get maximum amount of time (in milliseconds) to keep track
     * of request rates for a connection, before deciding that
     * the user has gone away, and discarding it.
     *
     * @return maximum tracking time
     */
    @ManagedAttribute("maximum time to track of request rates for connection before discarding")
    public long getMaxIdleTrackerMs()
    {
        return _maxIdleTrackerMs;
    }

    /**
     * Set maximum amount of time (in milliseconds) to keep track
     * of request rates for a connection, before deciding that
     * the user has gone away, and discarding it.
     *
     * @param value maximum tracking time
     */
    public void setMaxIdleTrackerMs(long value)
    {
        _maxIdleTrackerMs = value;
    }

    /**
     * The unique name of the filter when there is more than
     * one DosFilter instance.
     *
     * @return the name
     */
    public String getName()
    {
        return _name;
    }

    /**
     * @param name the name to set
     */
    public void setName(String name)
    {
        _name = name;
    }

    /**
     * Check flag to insert the DoSFilter headers into the response.
     *
     * @return value of the flag
     */
    @ManagedAttribute("inser DoSFilter headers in response")
    public boolean isInsertHeaders()
    {
        return _insertHeaders;
    }

    /**
     * Set flag to insert the DoSFilter headers into the response.
     *
     * @param value value of the flag
     */
    public void setInsertHeaders(boolean value)
    {
        _insertHeaders = value;
    }

    /**
     * Get flag to have usage rate tracked by session if a session exists.
     *
     * @return value of the flag
     */
    @ManagedAttribute("usage rate is tracked by session if one exists")
    public boolean isTrackSessions()
    {
        return _trackSessions;
    }

    /**
     * Set flag to have usage rate tracked by session if a session exists.
     *
     * @param value value of the flag
     */
    public void setTrackSessions(boolean value)
    {
        _trackSessions = value;
    }

    /**
     * Get flag to have usage rate tracked by IP+port (effectively connection)
     * if session tracking is not used.
     *
     * @return value of the flag
     */
    @ManagedAttribute("usage rate is tracked by IP+port is session tracking not used")
    public boolean isRemotePort()
    {
        return _remotePort;
    }

    /**
     * Set flag to have usage rate tracked by IP+port (effectively connection)
     * if session tracking is not used.
     *
     * @param value value of the flag
     */
    public void setRemotePort(boolean value)
    {
        _remotePort = value;
    }

    /**
     * @return whether this filter is enabled
     */
    @ManagedAttribute("whether this filter is enabled")
    public boolean isEnabled()
    {
        return _enabled;
    }

    /**
     * @param enabled whether this filter is enabled
     */
    public void setEnabled(boolean enabled)
    {
        _enabled = enabled;
    }

    /**
     * Status code for Rejected for too many requests.
     *
     * @return the configured status code (default: 429 - Too Many Requests)
     */
    public int getTooManyCode()
    {
        return _tooManyCode;
    }

    public void setTooManyCode(int tooManyCode)
    {
        _tooManyCode = tooManyCode;
    }

    /**
     * Get a list of IP addresses that will not be rate limited.
     *
     * @return comma-separated whitelist
     */
    @ManagedAttribute("list of IPs that will not be rate limited")
    public String getWhitelist()
    {
        StringBuilder result = new StringBuilder();
        for (Iterator<String> iterator = _whitelist.iterator(); iterator.hasNext(); )
        {
            String address = iterator.next();
            result.append(address);
            if (iterator.hasNext())
                result.append(",");
        }
        return result.toString();
    }

    /**
     * Set a list of IP addresses that will not be rate limited.
     *
     * @param commaSeparatedList comma-separated whitelist
     */
    public void setWhitelist(String commaSeparatedList)
    {
        List<String> result = new ArrayList<>();
        for (String address : StringUtil.csvSplit(commaSeparatedList))
        {
            addWhitelistAddress(result, address);
        }
        clearWhitelist();
        _whitelist.addAll(result);
        LOG.debug("Whitelisted IP addresses: {}", result);
    }

    /**
     * Clears the list of whitelisted IP addresses
     */
    @ManagedOperation("clears the list of IP addresses that will not be rate limited")
    public void clearWhitelist()
    {
        _whitelist.clear();
    }

    /**
     * Adds the given IP address, either in the form of a dotted decimal notation A.B.C.D
     * or in the CIDR notation A.B.C.D/M, to the list of whitelisted IP addresses.
     *
     * @param address the address to add
     * @return whether the address was added to the list
     * @see #removeWhitelistAddress(String)
     */
    @ManagedOperation("adds an IP address that will not be rate limited")
    public boolean addWhitelistAddress(@Name("address") String address)
    {
        return addWhitelistAddress(_whitelist, address);
    }

    private boolean addWhitelistAddress(List<String> list, String address)
    {
        address = address.trim();
        return address.length() > 0 && list.add(address);
    }

    /**
     * Removes the given address from the list of whitelisted IP addresses.
     *
     * @param address the address to remove
     * @return whether the address was removed from the list
     * @see #addWhitelistAddress(String)
     */
    @ManagedOperation("removes an IP address that will not be rate limited")
    public boolean removeWhitelistAddress(@Name("address") String address)
    {
        return _whitelist.remove(address);
    }

    private String createRemotePortId(ServletRequest request)
    {
        String addr = request.getRemoteAddr();
        int port = request.getRemotePort();
        return addr + ":" + port;
    }

    /**
     * A RateTracker is associated with a connection, and stores request rate
     * data.
     */
    static class RateTracker implements Runnable, HttpSessionBindingListener, HttpSessionActivationListener, Serializable
    {
        private static final long serialVersionUID = 3534663738034577872L;

        final AutoLock _lock = new AutoLock();
        protected final String _filterName;
        protected transient ServletContext _context;
        protected final String _id;
        protected final RateType _type;
        protected final int _maxRequestsPerSecond;
        protected final long[] _timestamps;

        protected int _next;

        public RateTracker(ServletContext context, String filterName, String id, RateType type, int maxRequestsPerSecond)
        {
            _context = context;
            _filterName = filterName;
            _id = id;
            _type = type;
            _maxRequestsPerSecond = maxRequestsPerSecond;
            _timestamps = new long[maxRequestsPerSecond];
            _next = 0;
        }

        /**
         * @param now the time now (in nanoseconds) used to calculate elapsed time since previous requests.
         * @return the current calculated request rate over the last second if rate exceeded, else null.
         */
        public OverLimit isRateExceeded(long now)
        {
            final long last;
            try (AutoLock l = _lock.lock())
            {
                last = _timestamps[_next];
                _timestamps[_next] = now;
                _next = (_next + 1) % _timestamps.length;
            }

            if (last == 0)
                return null;

            long rate = NanoTime.elapsed(last, now);
            if (TimeUnit.NANOSECONDS.toSeconds(rate) < 1L)
            {
                return new Overage(Duration.ofNanos(rate), _maxRequestsPerSecond);
            }
            return null;
        }

        public String getId()
        {
            return _id;
        }

        public RateType getType()
        {
            return _type;
        }

        @Override
        public void valueBound(HttpSessionBindingEvent event)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Value bound: {}", getId());
            _context = event.getSession().getServletContext();
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            //take the tracker out of the list of trackers
            DoSFilter filter = (DoSFilter)event.getSession().getServletContext().getAttribute(_filterName);
            removeFromRateTrackers(filter, _id);
            _context = null;
        }

        @Override
        public void sessionWillPassivate(HttpSessionEvent se)
        {
            //take the tracker of the list of trackers (if its still there)
            DoSFilter filter = (DoSFilter)se.getSession().getServletContext().getAttribute(_filterName);
            removeFromRateTrackers(filter, _id);
            _context = null;
        }

        @Override
        public void sessionDidActivate(HttpSessionEvent se)
        {
            RateTracker tracker = (RateTracker)se.getSession().getAttribute(__TRACKER);
            ServletContext context = se.getSession().getServletContext();
            tracker.setContext(context);
            DoSFilter filter = (DoSFilter)context.getAttribute(_filterName);
            if (filter == null)
            {
                LOG.info("No filter {} for rate tracker {}", _filterName, tracker);
                return;
            }
            addToRateTrackers(filter, tracker);
        }

        public void setContext(ServletContext context)
        {
            _context = context;
        }

        protected void removeFromRateTrackers(DoSFilter filter, String id)
        {
            if (filter == null)
                return;

            filter.removeFromRateTracker(id);
            if (LOG.isDebugEnabled())
                LOG.debug("Tracker removed: {}", getId());
        }

        private void addToRateTrackers(DoSFilter filter, RateTracker tracker)
        {
            if (filter == null)
                return;
            filter.addToRateTracker(tracker);
        }

        @Override
        public void run()
        {
            if (_context == null)
            {
                LOG.warn("Unknown context for rate tracker {}", this);
                return;
            }

            int latestIndex = _next == 0 ? (_timestamps.length - 1) : (_next - 1);
            long last = _timestamps[latestIndex];
            boolean hasRecentRequest = last != 0 && NanoTime.secondsElapsedFrom(last) < 1L;

            DoSFilter filter = (DoSFilter)_context.getAttribute(_filterName);

            if (hasRecentRequest)
            {
                if (filter != null)
                    filter.schedule(this);
                else
                    LOG.warn("No filter {}", _filterName);
            }
            else
                removeFromRateTrackers(filter, _id);
        }

        @Override
        public String toString()
        {
            return "RateTracker/" + _id + "/" + _type;
        }

        public class Overage implements OverLimit
        {
            private final Duration duration;
            private final long count;

            public Overage(Duration dur, long count)
            {
                this.duration = dur;
                this.count = count;
            }

            @Override
            public RateType getRateType()
            {
                return _type;
            }

            @Override
            public String getRateId()
            {
                return _id;
            }

            @Override
            public Duration getDuration()
            {
                return duration;
            }

            @Override
            public long getCount()
            {
                return count;
            }

            @Override
            public String toString()
            {
                final StringBuilder sb = new StringBuilder(OverLimit.class.getSimpleName());
                sb.append('@').append(Integer.toHexString(hashCode()));
                sb.append("[type=").append(getRateType());
                sb.append(", id=").append(getRateId());
                sb.append(", duration=").append(duration);
                sb.append(", count=").append(count);
                sb.append(']');
                return sb.toString();
            }
        }
    }

    private static class FixedRateTracker extends RateTracker
    {
        public FixedRateTracker(ServletContext context, String filterName, String id, RateType type, int numRecentRequestsTracked)
        {
            super(context, filterName, id, type, numRecentRequestsTracked);
        }

        @Override
        public OverLimit isRateExceeded(long now)
        {
            // rate limit is never exceeded, but we keep track of the request timestamps
            // so that we know whether there was recent activity on this tracker
            // and whether it should be expired
            try (AutoLock l = _lock.lock())
            {
                _timestamps[_next] = now;
                _next = (_next + 1) % _timestamps.length;
            }

            return null;
        }

        @Override
        public String toString()
        {
            return "Fixed" + super.toString();
        }
    }

    private static class DoSTimeoutAsyncListener implements AsyncListener
    {
        @Override
        public void onStartAsync(AsyncEvent event)
        {
        }

        @Override
        public void onComplete(AsyncEvent event)
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().dispatch();
        }

        @Override
        public void onError(AsyncEvent event)
        {
        }
    }

    private class DoSAsyncListener extends DoSTimeoutAsyncListener
    {
        private final RateType priority;

        public DoSAsyncListener(RateType priority)
        {
            this.priority = priority;
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            _queues.get(priority).remove(event.getAsyncContext());
            super.onTimeout(event);
        }
    }

    public enum Action
    {
        /**
         * No action is taken against the Request, it is allowed to be processed normally.
         */
        NO_ACTION,
        /**
         * The request and response is aborted, no response is sent.
         */
        ABORT,
        /**
         * The request is rejected by sending an error based on {@link DoSFilter#getTooManyCode()}
         */
        REJECT,
        /**
         * The request is delayed based on {@link DoSFilter#getDelayMs()}
         */
        DELAY,
        /**
         * The request is throttled.
         */
        THROTTLE;

        /**
         * Obtain the Action based on configured {@link DoSFilter#getDelayMs()}
         *
         * @param delayMs the delay in milliseconds.
         * @return the Action proposed.
         */
        public static Action fromDelay(long delayMs)
        {
            if (delayMs < 0)
                return Action.REJECT;

            if (delayMs == 0)
                return Action.THROTTLE;

            return Action.DELAY;
        }
    }

    public interface OverLimit
    {
        RateType getRateType();

        String getRateId();

        Duration getDuration();

        long getCount();
    }

    /**
     * Listener for actions taken against specific requests.
     */
    public static class Listener
    {
        /**
         * Process the onRequestOverLimit() behavior.
         *
         * @param request the request that is over the limit
         * @param dosFilter the {@link DoSFilter} that this event occurred on
         * @return the action to actually perform.
         */
        public Action onRequestOverLimit(HttpServletRequest request, OverLimit overlimit, DoSFilter dosFilter)
        {
            Action action = Action.fromDelay(dosFilter.getDelayMs());

            switch (action)
            {
                case REJECT:
                    LOG.warn("DOS ALERT: Request rejected ip={}, overlimit={}, session={}, user={}", request.getRemoteAddr(), overlimit, request.getRequestedSessionId(), request.getUserPrincipal());
                    break;
                case DELAY:
                    LOG.warn("DOS ALERT: Request delayed={}ms, ip={}, overlimit={}, session={}, user={}", dosFilter.getDelayMs(), request.getRemoteAddr(), overlimit, request.getRequestedSessionId(), request.getUserPrincipal());
                    break;
                case THROTTLE:
                    LOG.warn("DOS ALERT: Request throttled ip={}, overlimit={}, session={}, user={}", request.getRemoteAddr(), overlimit, request.getRequestedSessionId(), request.getUserPrincipal());
                    break;
            }

            return action;
        }
    }
}
