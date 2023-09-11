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
import java.net.InetSocketAddress;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;

import static java.lang.invoke.MethodType.methodType;

public class ConditionalHandler4 extends Handler.Wrapper
{
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<Predicate<Request>, Request> _predicates = new IncludeExcludeSet<>(PredicateSet.class);
    private MethodHandle _doHandle;

    public ConditionalHandler4()
    {
    }

    @Override
    protected void doStart() throws Exception
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType handleType = methodType(boolean.class, Request.class, Response.class, Callback.class);

        _doHandle = lookup.findVirtual(ConditionalHandler4.class, "doHandle", handleType);
        MethodHandle nextHandle = getHandler() == null
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
        _methods.include(methods);
    }

    public void excludeMethod(String... methods)
    {
        _methods.exclude(methods);
    }

    public void includePath(String... paths)
    {
        _paths.include(paths);
    }

    public void excludePath(String... paths)
    {
        _paths.exclude(paths);
    }

    public void includeInetAddress(String addressPattern)
    {
        _predicates.include(new InetAddressPredicate(addressPattern));
    }

    public void excludeInetAddress(String addressPattern)
    {
        _predicates.exclude(new InetAddressPredicate(addressPattern));
    }

    @SafeVarargs
    public final void include(Predicate<Request>... predicates)
    {
        _predicates.include(predicates);
    }

    @SafeVarargs
    public final void exclude(Predicate<Request>... predicates)
    {
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
        private final InetAddressPattern _pattern;

        public InetAddressPredicate(String pattern)
        {
            _pattern = InetAddressPattern.from(pattern);
        }

        @Override
        public boolean test(Request request)
        {
            return request.getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress inetSocketAddress &&
                _pattern.test(inetSocketAddress.getAddress());
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
}
