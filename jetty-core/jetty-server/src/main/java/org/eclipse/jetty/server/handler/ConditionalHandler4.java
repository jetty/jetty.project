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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodType.methodType;

public class ConditionalHandler4 extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ConditionalHandler4.class);

    private final boolean _skip;
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<Predicate<Request>, Request> _predicates = new IncludeExcludeSet<>(PredicateSet.class);
    private MethodHandle _doHandle;

    public ConditionalHandler4()
    {
        this(false);
    }

    public ConditionalHandler4(boolean skip)
    {
        _skip = skip;
    }

    @Override
    protected void doStart() throws Exception
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType handleType = methodType(boolean.class, Request.class, Response.class, Callback.class);

        _doHandle = lookup.findVirtual(ConditionalHandler4.class, "doHandle", handleType);
        MethodHandle nextHandle = !_skip || getHandler() == null
            ? MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, ConditionalHandler4.class, Request.class, Response.class, Callback.class)
            : lookup.findVirtual(ConditionalHandler4.class, "nextHandle", handleType);

        if (!_methods.isEmpty())
        {
            MethodHandle checkMethod = lookup.findVirtual(ConditionalHandler4.class, "checkMethod", methodType(boolean.class, Request.class));
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkMethod, 2, Response.class, Callback.class),
                _doHandle,
                nextHandle);
        }

        if (!_paths.isEmpty())
        {
            MethodHandle checkPath = lookup.findVirtual(ConditionalHandler4.class, "checkPath", methodType(boolean.class, Request.class));
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkPath, 2, Response.class, Callback.class),
                _doHandle,
                nextHandle);
        }

        if (!_predicates.isEmpty())
        {
            MethodHandle checkPredicates = lookup.findVirtual(ConditionalHandler4.class, "checkPredicates", methodType(boolean.class, Request.class));
            _doHandle = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(checkPredicates, 2, Response.class, Callback.class),
                _doHandle,
                nextHandle);
        }

        super.doStart();
    }

    public void includeMethod(String... methods)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.include(methods);
    }

    public void excludeMethod(String... methods)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.exclude(methods);
    }

    public void includePath(String... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _paths.include(paths);
    }

    public void excludePath(String... paths)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _paths.exclude(paths);
    }

    public void includeInetAddress(String addressPattern)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _predicates.include(new InetAddressPredicate(addressPattern));
    }

    public void excludeInetAddress(String addressPattern)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _predicates.exclude(new InetAddressPredicate(addressPattern));
    }

    /**
     * Includes a combination predicate.
     *
     * @param connectorName optional name of a connector to include or {@code null}.
     * @param addressPattern optional InetAddress pattern to include or {@code null}.
     * @param method optional method to include or {@code null}.
     * @param pathSpec optional pathSpec to include or {@code null}.
     */
    public void include(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        include(new PredicateTuple(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
    }

    /**
     * Excludes a combination predicate.
     *
     * @param connectorName optional name of a connector to include or {@code null}.
     * @param addressPattern optional InetAddress pattern to include or {@code null}.
     * @param method optional method to include or {@code null}.
     * @param pathSpec optional pathSpec to include or {@code null}.
     */
    public void exclude(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        exclude(new PredicateTuple(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
    }

    @SafeVarargs
    public final void include(Predicate<Request>... predicates)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _predicates.include(predicates);
    }

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
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        try
        {
            return (boolean)_doHandle.invokeExact(this, request, response, callback);
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

    protected boolean doHandle(Request request, Response response, Callback callback) throws Exception
    {
        return nextHandle(request, response, callback);
    }

    private boolean nextHandle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        return next != null && next.handle(request, response, callback);
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

        public InetAddressPredicate(String pattern)
        {
            _pattern = InetAddressPattern.from(pattern);
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

    public static class PredicateTuple implements Predicate<Request>
    {
        private final String connector;
        private final InetAddressPattern address;
        private final String method;
        private final PathSpec pathSpec;

        public PredicateTuple(String connector, InetAddressPattern address, String method, PathSpec pathSpec)
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
