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

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodType.methodType;

/**
 * A {@link Handler.Wrapper} that may conditionally apply an action to a request.
 * The conditions are implemented by an {@link IncludeExclude} of:
 * <ul>
 *     <li>A method name</li>
 *     <li>A {@link PathSpec} or sting representation</li>
 *     <li>A combination of {@link #include(String, String, String, PathSpec) multiple attributes}</li>
 *     <li>An arbitrary {@link Predicate} taking the {@link Request}</li>
 * </ul>
 * <p>
 * If the conditions are met, the {@link #doHandle(Request, Response, Callback)} method will be invoked.
 * However, as the default implementation to to call the {@link #getHandler() next Handler}, an optimization is applied to
 * directly call the next {@code Handler} if {@code doHandler} has not been extended.
 * </p>
 * <p>
 * If the conditions are not met, then the behaviour will be determined by the {@link NotApplyAction} passed to the
 * constructor.
 * </p>
 *
 */
public class ConditionalHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ConditionalHandler.class);

    public enum NotApplyAction
    {
        /**
         * If the conditions are not met, then the {@link #doNotHandle(Request, Response, Callback)} method is invoked,
         * which by default will return {@code false}.  This action is used when the entire {@code Handler} branch is
         * to be bypassed.
         */
        DO_NOT_HANDLE,
        /**
         * If the conditions are not met, then bypass the {@link #doHandle(Request, Response, Callback)} method by
         * invoking the {@link Handler#handle(Request, Response, Callback)} method of the {@link #getHandler() next Handler}.
         * This action is used if this class has been extended to provided specific optional behavior in the {@code doHandle}
         * method.
         */
        SKIP_THIS,
        /**
         * If the conditions are not met, then bypass the {@link Handler#handle(Request, Response, Callback)} method
         * of the {@link #getHandler() next Handler} invoking the {@link Handler#handle(Request, Response, Callback)}
         * method of the {@link Handler} {@link Singleton#getHandler() after} the {@link #getHandler() nextHandler}.
         * This action is used to make the specific behavior of the {@link #getHandler() following}
         * {@link Handler.Wrapper Wrapper} class optional.
         */
        SKIP_NEXT,
    }

    private final NotApplyAction _notApplyAction;
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<Predicate<Request>, Request> _predicates = new IncludeExcludeSet<>(PredicateSet.class);
    private MethodHandle _doHandle;

    public ConditionalHandler()
    {
        this(NotApplyAction.DO_NOT_HANDLE);
    }

    public ConditionalHandler(NotApplyAction notApplyAction)
    {
        _notApplyAction = notApplyAction;
    }

    /**
     * Clear all inclusions and exclusions.
     */
    public void clear()
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.clear();
        _paths.clear();
        _predicates.clear();
    }

    /**
     * Include {@link Request#getMethod() method}s in the conditions to be met
     * @param methods The exact case-sensitive method name
     */
    public void includeMethod(String... methods)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.include(methods);
    }

    /**
     * Exclude {@link Request#getMethod() method}s in the conditions to be met
     * @param methods The exact case-sensitive method name
     */
    public void excludeMethod(String... methods)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.exclude(methods);
    }

    /**
     * Include {@link PathSpec}s in the conditions to be met
     * @param paths The {@link PathSpec}s that are tested against the {@link Request#getPathInContext(Request) pathInContext}.
     */
    public void include(PathSpec... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        for (PathSpec p : paths)
            ((PathSpecSet)_paths.getIncluded()).add(p);
    }

    /**
     * Exclude {@link PathSpec}s in the conditions to be met
     * @param paths The {@link PathSpec}s that are tested against the {@link Request#getPathInContext(Request) pathInContext}.
     */
    public void exclude(PathSpec... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        for (PathSpec p : paths)
            ((PathSpecSet)_paths.getExcluded()).add(p);
    }

    /**
     * Include {@link PathSpec}s in the conditions to be met
     * @param paths String representations of {@link PathSpec}s that are
     *              tested against the {@link Request#getPathInContext(Request) pathInContext}.
     */
    public void includePath(String... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _paths.include(paths);
    }

    /**
     * Exclude {@link PathSpec} in the conditions to be met
     * @param paths String representations of {@link PathSpec}s that are
     *              tested against the {@link Request#getPathInContext(Request) pathInContext}.
     */
    public void excludePath(String... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _paths.exclude(paths);
    }

    /**
     * Include {@link InetAddressPattern}s in the conditions to be met
     * @param patterns {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void include(InetAddressPattern... patterns)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        for (InetAddressPattern p : patterns)
            _predicates.include(new InetAddressPredicate(p));
    }

    /**
     * Include {@link InetAddressPattern}s in the conditions to be met
     * @param patterns String representations of {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void includeInetAddress(String... patterns)
    {
        for (String p : patterns)
            include(InetAddressPattern.from(p));
    }

    /**
     * Exclude {@link InetAddressPattern}s in the conditions to be met
     * @param patterns {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void exclude(InetAddressPattern... patterns)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        for (InetAddressPattern p : patterns)
            _predicates.exclude(new InetAddressPredicate(p));
    }

    /**
     * Exclude {@link InetAddressPattern} in the conditions to be met
     * @param patterns String representations of {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void excludeInetAddress(String... patterns)
    {
        for (String p : patterns)
            exclude(InetAddressPattern.from(p));
    }

    /**
     * {@link IncludeExclude#include(Object) Include} a {@link ConnectorAddrMethodPathPredicate}.
     * If only a method or pathSpec is passed, then this method optimizes by converting this
     * call to {@link #includeMethod(String...)} or {@link #include(PathSpec...)}.
     *
     * @param connectorName optional name of a connector or {@code null}.
     * @param addressPattern optional InetAddress pattern to test against the remote address or {@code null}.
     * @param method optional method or {@code null}.
     * @param pathSpec optional pathSpec to test against the {@link Request#getPathInContext(Request) pathInContext} or {@code null}.
     */
    public void include(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        if (connectorName == null && addressPattern == null)
        {
            if (StringUtil.isNotBlank(method) && pathSpec == null)
                includeMethod(method);
            else if (StringUtil.isBlank(method) && pathSpec != null)
                include(pathSpec);
            include(new ConnectorAddrMethodPathPredicate(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
        }
        else
        {
            include(new ConnectorAddrMethodPathPredicate(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
        }
    }

    /**
     * {@link IncludeExclude#exclude(Object) Exclude} a {@link ConnectorAddrMethodPathPredicate}.
     * If only a method or pathSpec is passed, then this method optimizes by converting this
     * call to {@link #excludeMethod(String...)} or {@link #exclude(PathSpec...)}.
     *
     * @param connectorName optional name of a connector or {@code null}.
     * @param addressPattern optional InetAddress pattern to test against the remote address or {@code null}.
     * @param method optional method or {@code null}.
     * @param pathSpec optional pathSpec to test against the {@link Request#getPathInContext(Request) pathInContext} or {@code null}.
     */
    public void exclude(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        exclude(new ConnectorAddrMethodPathPredicate(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
    }

    /**
     * {@link IncludeExclude#include(Object) Include} arbitrary {@link Predicate}s in the conditions.
     * @param predicates {@link Predicate}s that are tested against the {@link Request}.
     */
    @SafeVarargs
    public final void include(Predicate<Request>... predicates)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _predicates.include(predicates);
    }

    /**
     * {@link IncludeExclude#exclude(Object) Exclude} arbitrary {@link Predicate}s in the conditions.
     * @param predicates {@link Predicate}s that are tested against the {@link Request}.
     */
    @SafeVarargs
    public final void exclude(Predicate<Request>... predicates)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _predicates.exclude(predicates);
    }

    private boolean checkMethod(Request request)
    {
        return _methods.test(request.getMethod());
    }

    private boolean checkPath(Request request)
    {
        return _paths.test(Request.getPathInContext(request));
    }

    private boolean checkPredicates(Request request)
    {
        return _predicates.test(request);
    }

    @Override
    protected void doStart() throws Exception
    {
        Handler next = getHandler();

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType handleType = methodType(boolean.class, Request.class, Response.class, Callback.class);

        // Determine if this classes doHandle method has been extended
        boolean doHandleExtended;
        try
        {
            doHandleExtended = getClass().getDeclaredMethod("doHandle", Request.class, Response.class, Callback.class).getDeclaringClass() != ConditionalHandler.class;
        }
        catch (NoSuchMethodException e)
        {
            doHandleExtended = false;
        }

        // Determine the handling if the conditions are met.
        // If doHandle has been extended (or there is no next handler), then we invoke the method on this class,
        // otherwise we can directly invoke the handle method on the next class
        _doHandle = doHandleExtended || next == null
            ? lookup.findVirtual(ConditionalHandler.class, "doHandle", handleType).bindTo(this)
            : lookup.findVirtual(next.getClass(), "handle", handleType).bindTo(next);

        // Determine the handling if the conditions are not met
        MethodHandle doNotHandle = switch (_notApplyAction)
        {
            case DO_NOT_HANDLE ->
                // Invoke the doNotHandle method
                lookup.findVirtual(ConditionalHandler.class, "doNotHandle", handleType).bindTo(this);
            case SKIP_THIS ->
            {
                // Skip the doNotHandle method of this handler and call handle of the next handler
                if (next != null)
                {
                    MethodHandle nextHandle = lookup.findVirtual(next.getClass(), "handle", handleType);
                    yield nextHandle.bindTo(next);
                }
                throw new IllegalStateException("No next Handler");
            }
            case SKIP_NEXT ->
            {
                // Skip the handle method of the next handler and call its next handler.
                if (doHandleExtended)
                    throw new IllegalStateException("doHandle method is extended in SKIP_NEXT mode");
                if (getHandler() instanceof Handler.Singleton nextWrapper && nextWrapper.getHandler() != null)
                {
                    Handler nextNextHandler = nextWrapper.getHandler();
                    MethodHandle nextHandle = lookup.findVirtual(nextNextHandler.getClass(), "handle", handleType);
                    yield nextHandle.bindTo(nextNextHandler);
                }
                throw new IllegalStateException("No next Handler.Wrapper next");
            }
        };

        // If we have method conditions, guard the doHandle method with checkMethod
        if (!_methods.isEmpty())
        {
            MethodHandle checkMethod = lookup.findVirtual(ConditionalHandler.class, "checkMethod", methodType(boolean.class, Request.class)).bindTo(this);
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkMethod, 1, Response.class, Callback.class),
                _doHandle,
                doNotHandle);
        }

        // If we have path conditions, guard the doHandle method with checkPaths
        if (!_paths.isEmpty())
        {
            MethodHandle checkPath = lookup.findVirtual(ConditionalHandler.class, "checkPath", methodType(boolean.class, Request.class)).bindTo(this);
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkPath, 1, Response.class, Callback.class),
                _doHandle,
                doNotHandle);
        }

        // If we have predicate conditions, guard the doHandle method with checkPredicates
        if (!_predicates.isEmpty())
        {
            MethodHandle checkPredicates = lookup.findVirtual(ConditionalHandler.class, "checkPredicates", methodType(boolean.class, Request.class)).bindTo(this);
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkPredicates, 1, Response.class, Callback.class),
                _doHandle,
                doNotHandle);
        }

        super.doStart();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        try
        {
            // Invoke the _doHandle MethodHandle which will include checking any conditions in its guards.
            return (boolean)_doHandle.invokeExact(request, response, callback);
        }
        catch (Exception e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            Response.writeError(request, response, callback, t);
            return true;
        }
    }

    /**
     * Handle a request that has met the conditions.
     * @param request The request to handle
     * @param response The response to generate
     * @param callback The callback for completion
     * @return True if this handler will complete the callback
     * @throws Exception If there is a problem handling
     * @see Handler#handle(Request, Response, Callback)
     */
    protected boolean doHandle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        return next != null && next.handle(request, response, callback);
    }

    /**
     * Handle a request that has not met the conditions.
     * By default, this method simple returns {@code false}.
     * @param request The request to handle
     * @param response The response to generate
     * @param callback The callback for completion
     * @return True if this handler will complete the callback
     * @throws Exception If there is a problem handling
     * @see Handler#handle(Request, Response, Callback)
     */
    protected boolean doNotHandle(Request request, Response response, Callback callback) throws Exception
    {
        return false;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new DumpableCollection("included methods", _methods.getIncluded()),
            new DumpableCollection("excluded methods", _methods.getExcluded()),
            new DumpableCollection("included paths", _paths.getIncluded()),
            new DumpableCollection("excluded paths", _paths.getExcluded()),
            new DumpableCollection("included predicates", _predicates.getIncluded()),
            new DumpableCollection("excluded predicates", _predicates.getExcluded())
        );
    }

    public static class PredicateSet extends AbstractSet<Predicate<Request>> implements Set<Predicate<Request>>, Predicate<Request>
    {
        private final ArrayList<Predicate<Request>> _predicates = new ArrayList<>();

        @Override
        public boolean add(Predicate<Request> predicate)
        {
            return _predicates.add(predicate);
        }

        @Override
        public boolean remove(Object o)
        {
            return _predicates.remove(o);
        }

        @Override
        public Iterator<Predicate<Request>> iterator()
        {
            return _predicates.iterator();
        }

        @Override
        public int size()
        {
            return _predicates.size();
        }

        @Override
        public boolean test(Request request)
        {
            if (request == null)
                return false;

            for (Predicate<Request> predicate : _predicates)
            {
                if (predicate.test(request))
                    return true;
            }
            return false;
        }
    }

    /**
     * A {@link Predicate} over {@link Request} that tests an {@link InetAddressPattern}
     * against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
     */
    public static class InetAddressPredicate implements Predicate<Request>
    {
        public static InetAddress getInetAddress(SocketAddress socketAddress)
        {
            if (socketAddress instanceof InetSocketAddress inetSocketAddress)
            {
                if (inetSocketAddress.isUnresolved())
                {
                    try
                    {
                        return InetAddress.getByName(inetSocketAddress.getHostString());
                    }
                    catch (UnknownHostException e)
                    {
                        if (LOG.isTraceEnabled())
                            LOG.trace("ignored", e);
                        return null;
                    }
                }

                return inetSocketAddress.getAddress();
            }
            return null;
        }

        private final InetAddressPattern _pattern;

        public InetAddressPredicate(InetAddressPattern pattern)
        {
            _pattern = pattern;
        }

        @Override
        public boolean test(Request request)
        {
            return _pattern.test(getInetAddress(request.getConnectionMetaData().getRemoteSocketAddress()));
        }

        @Override
        public int hashCode()
        {
            return _pattern.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof InetAddressPredicate inetAddressPredicate && _pattern.equals(inetAddressPredicate._pattern);
        }

        @Override
        public String toString()
        {
            return super.toString();
        }
    }

    /**
     * A {@link Predicate} over {@link Request} that tests on or more of:
     * <ul>
     *     <li>The {@link Connector#getName() name} of the
     *     {@link ConnectionMetaData#getConnector() connector}
     *     obtained from {@link Request#getConnectionMetaData()}</li>
     *     <li>An {@link InetAddressPattern} tested against the
     *     {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *     {@link Request#getConnectionMetaData()}.
     *     </li>
     *     <li>A {@link Request#getMethod() method} name</li>
     *     <li>A {@link PathSpec} tested against the {@link Request#getPathInContext(Request) pathInContext}.</li>
     * </ul>
     */
    public static class ConnectorAddrMethodPathPredicate implements Predicate<Request>
    {
        private final String connector;
        private final InetAddressPattern address;
        private final String method;
        private final PathSpec pathSpec;

        public ConnectorAddrMethodPathPredicate(String connector, InetAddressPattern address, String method, PathSpec pathSpec)
        {
            this.connector = connector;
            this.address = address;
            this.method = method;
            this.pathSpec = pathSpec;
        }

        @Override
        public boolean test(Request request)
        {
            // Match for connector.
            if ((connector != null) && !connector.equals(request.getConnectionMetaData().getConnector().getName()))
                return false;

            // If we have a method we must match
            if ((method != null) && !method.equals(request.getMethod()))
                return false;

            // If we have a path we must be at this path to match for an address.
            if ((pathSpec != null) && !pathSpec.matches(Request.getPathInContext(request)))
                return false;

            // Match for InetAddress.
            return address == null || address.test(InetAddressPredicate.getInetAddress(request.getConnectionMetaData().getRemoteSocketAddress()));
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{connector=%s, addressPattern=%s, method=%s, pathSpec=%s}", getClass().getSimpleName(), hashCode(), connector, address, method, pathSpec);
        }
    }
}
