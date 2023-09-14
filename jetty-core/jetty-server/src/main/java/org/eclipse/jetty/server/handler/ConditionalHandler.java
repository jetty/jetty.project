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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
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
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Handler.Wrapper} that may conditionally apply an action to a request.
 * The conditions are implemented by an {@link IncludeExclude} of:
 * <ul>
 *     <li>A method name, which can be efficiently matched</li>
 *     <li>A {@link PathSpec} or sting representation, which can be efficient matched.</li>
 *     <li>An arbitrary {@link Predicate} taking the {@link Request}, which is matched in a linear test of all predicates.</li>
 * </ul>
 * <p>
 * If the conditions are met, the {@link #doHandle(Request, Response, Callback)} method will be invoked.
 * However, as the default implementation to to call the {@link #getHandler() next Handler}, an optimization is applied to
 * directly call the next {@code Handler} if {@code doHandler} has not been extended.
 * </p>
 * <p>
 * If the conditions are not met, then the behaviour will be determined by the {@link ConditionNotMetAction} passed to the
 * constructor.
 * </p>
 *
 */
public class ConditionalHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ConditionalHandler.class);

    /**
     * Enumeration of the actions that can be taken if the request is to not be handled due to failing to
     * meet the conditions.
     */
    public enum ConditionNotMetAction
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

    private final ConditionNotMetAction _conditionNotMetAction;
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _pathSpecs = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<Predicate<Request>, Request> _predicates = new IncludeExcludeSet<>(PredicateSet.class);
    private Predicate<Request> _handlePredicate;

    public ConditionalHandler()
    {
        this(ConditionNotMetAction.DO_NOT_HANDLE);
    }

    public ConditionalHandler(Handler nextHandler)
    {
        this(ConditionNotMetAction.SKIP_NEXT, nextHandler);
    }

    public ConditionalHandler(ConditionNotMetAction conditionNotMetAction)
    {
        this(conditionNotMetAction, null);
    }

    public ConditionalHandler(ConditionNotMetAction conditionNotMetAction, Handler nextHandler)
    {
        _conditionNotMetAction = conditionNotMetAction;
        if (nextHandler != null)
            setHandler(nextHandler);
    }

    /**
     * Clear all inclusions and exclusions.
     */
    public void clear()
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _methods.clear();
        _pathSpecs.clear();
        _predicates.clear();
    }

    IncludeExclude<String> getMethods()
    {
        // Used only for testing
        return _methods;
    }

    IncludeExclude<String> getPathSpecs()
    {
        // Used only for testing
        return _pathSpecs;
    }

    IncludeExcludeSet<Predicate<Request>, Request> getPredicates()
    {
        // Used only for testing
        return _predicates;
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
            ((PathSpecSet)_pathSpecs.getIncluded()).add(p);
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
            ((PathSpecSet)_pathSpecs.getExcluded()).add(p);
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
        _pathSpecs.include(paths);
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
        _pathSpecs.exclude(paths);
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
            _predicates.include(new InetAddressPatternPredicate(p));
    }

    /**
     * Include {@link InetAddressPattern}s in the conditions to be met
     * @param patterns String representations of {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void includeInetAddressPattern(String... patterns)
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
            _predicates.exclude(new InetAddressPatternPredicate(p));
    }

    /**
     * Exclude {@link InetAddressPattern} in the conditions to be met
     * @param patterns String representations of {@link InetAddressPattern}s that are
     *              tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     *              {@link Request#getConnectionMetaData()}.
     */
    public void excludeInetAddressPattern(String... patterns)
    {
        for (String p : patterns)
            exclude(InetAddressPattern.from(p));
    }

    /**
     * {@link IncludeExclude#include(Object) Include} arbitrary {@link Predicate}s in the conditions.
     * @param predicates {@link Predicate}s that are tested against the {@link Request}.
     * This method is optimized so that a passed {@link MethodPredicate} or {@link PathSpecPredicate} is
     * converted to a more efficient {@link #includeMethod(String...)} or {@link #include(PathSpec...)} respectively.
     */
    @SafeVarargs
    public final void include(Predicate<Request>... predicates)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        for (Predicate<Request> p : predicates)
        {
            if (p instanceof MethodPredicate methodPredicate)
                includeMethod(methodPredicate._method);
            else if (p instanceof PathSpecPredicate pathSpecPredicate)
                include(pathSpecPredicate._pathSpec);
            else
                _predicates.include(p);
        }
    }

    /**
     * {@link IncludeExclude#exclude(Object) Exclude} arbitrary {@link Predicate}s in the conditions.
     * @param predicates {@link Predicate}s that are tested against the {@link Request}.
     * This method is optimized so that a passed {@link MethodPredicate} or {@link PathSpecPredicate} is
     * converted to a more efficient {@link #excludeMethod(String...)} or {@link #exclude(PathSpec...)} respectively.
     */
    @SafeVarargs
    public final void exclude(Predicate<Request>... predicates)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        for (Predicate<Request> p : predicates)
        {
            if (p instanceof MethodPredicate methodPredicate)
                excludeMethod(methodPredicate._method);
            else if (p instanceof PathSpecPredicate pathSpecPredicate)
                exclude(pathSpecPredicate._pathSpec);
            else
                _predicates.exclude(p);
        }
    }

    private boolean testMethods(Request request)
    {
        return _methods.test(request.getMethod());
    }

    private boolean testPathSpecs(Request request)
    {
        return _pathSpecs.test(Request.getPathInContext(request));
    }

    private boolean testPredicates(Request request)
    {
        return _predicates.test(request);
    }

    @Override
    protected void doStart() throws Exception
    {
        switch (_conditionNotMetAction)
        {
            case SKIP_THIS ->
            {
                if (getHandler() == null)
                    throw new IllegalStateException("No next Handler");
            }
            case SKIP_NEXT ->
            {
                if (!(getHandler() instanceof Handler.Singleton nextWrapper) || nextWrapper.getHandler() == null)
                   throw new IllegalStateException("No next Handler.Wrapper next");
            }
        }

        _handlePredicate = TypeUtil.truePredicate();

        if (!_methods.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testMethods);
        if (!_pathSpecs.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testPathSpecs);
        if (!_predicates.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testPredicates);

        super.doStart();
    }

    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        // This is the code we would have in handle if MethodHandlers were not used
        if (_handlePredicate.test(request))
            return doHandle(request, response, callback);

        return switch (_conditionNotMetAction)
        {
            case DO_NOT_HANDLE -> doNotHandle(request, response, callback);
            case SKIP_THIS -> getHandler().handle(request, response, callback);
            case SKIP_NEXT -> getHandler() instanceof Singleton singleton &&
                singleton.getHandler() != null &&
                singleton.getHandler().handle(request, response, callback);
        };
    }

    /**
     * Handle a request that has met the conditions.
     * The default implementation calls the {@link Handler#handle(Request, Response, Callback)} method
     * on the {@link #getHandler() next Handler}.
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
     * This method is called when the request has not met the conditions and is not to
     * be handled by this handler.  The default implementation returns {@code false}.
     * Derived implementations may send an error response or handle the request differently.
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
            new DumpableCollection("included paths", _pathSpecs.getIncluded()),
            new DumpableCollection("excluded paths", _pathSpecs.getExcluded()),
            new DumpableCollection("included predicates", _predicates.getIncluded()),
            new DumpableCollection("excluded predicates", _predicates.getExcluded())
        );
    }

    /**
     * Create a {@link Predicate} over {@link Request} built from the {@link Predicate#and(Predicate) and} of one or more of: <ul>
     *     <li>{@link ConnectorPredicate}</li>
     *     <li>{@link InetAddressPatternPredicate}</li>
     *     <li>{@link MethodPredicate}</li>
     *     <li>{@link PathSpecPredicate}</li>
     * </ul>
     * @param connectorName The connector name or {@code null}
     * @param inetAddressPattern An {@link InetAddressPattern} string or {@code null}
     * @param method A {@link org.eclipse.jetty.http.HttpMethod} name or {@code null}
     * @param pathSpec A {@link PathSpec} string or {@code null}
     * @return the combined {@link Predicate} over {@link Request}
     */
    public static Predicate<Request> from(String connectorName, String inetAddressPattern, String method, String pathSpec)
    {
        return from(connectorName, InetAddressPattern.from(inetAddressPattern), method, pathSpec == null ? null : PathSpec.from(pathSpec));
    }

    /**
     * Create a {@link Predicate} over {@link Request} built from the {@link Predicate#and(Predicate) and} of one or more of: <ul>
     *     <li>{@link ConnectorPredicate}</li>
     *     <li>{@link InetAddressPatternPredicate}</li>
     *     <li>{@link MethodPredicate}</li>
     *     <li>{@link PathSpecPredicate}</li>
     * </ul>
     * @param connectorName The connector name or {@code null}
     * @param addressPattern An {@link InetAddressPattern} or {@code null}
     * @param method A {@link org.eclipse.jetty.http.HttpMethod} name or {@code null}
     * @param pathSpec A {@link PathSpec} or {@code null}
     * @return the combined {@link Predicate} over {@link Request}
     */
    public static Predicate<Request> from(String connectorName, InetAddressPattern addressPattern, String method, PathSpec pathSpec)
    {
        Predicate<Request> predicate = connectorName == null ? null : new ConnectorPredicate(connectorName);

        if (addressPattern != null)
        {
            InetAddressPatternPredicate inetAddressPatternPredicate = new InetAddressPatternPredicate(addressPattern);
            predicate = predicate == null ? inetAddressPatternPredicate : predicate.and(inetAddressPatternPredicate);
        }

        if (method != null)
        {
            MethodPredicate methodPredicate = new MethodPredicate(method);
            predicate = predicate == null ? methodPredicate : predicate.and(methodPredicate);
        }

        if (pathSpec != null)
        {
            PathSpecPredicate pathSpecPredicate = new PathSpecPredicate(pathSpec);
            predicate = predicate == null ? pathSpecPredicate : predicate.and(pathSpecPredicate);
        }
        
        return predicate;
    }

    /**
     * A Set of {@link Predicate} over {@link Request} optimized for use by {@link IncludeExclude}.
     */
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
     * A {@link Predicate} over {@link Request} that tests the {@link Connector#getName() name} of the
     * {@link ConnectionMetaData#getConnector() connector} obtained from {@link Request#getConnectionMetaData()}
     */
    public static class ConnectorPredicate implements Predicate<Request>
    {
        private final String _connector;

        public ConnectorPredicate(String connector)
        {
            this._connector = Objects.requireNonNull(connector);
        }

        @Override
        public boolean test(Request request)
        {
            return _connector.equals(request.getConnectionMetaData().getConnector().getName());
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _connector);
        }
    }

    /**
     * A {@link Predicate} over {@link Request} that tests an {@link InetAddressPattern}
     * against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
     */
    public static class InetAddressPatternPredicate implements Predicate<Request>
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

        public InetAddressPatternPredicate(InetAddressPattern pattern)
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
            return obj instanceof InetAddressPatternPredicate inetAddressPatternPredicate && _pattern.equals(inetAddressPatternPredicate._pattern);
        }

        @Override
        public String toString()
        {
            return super.toString();
        }
    }

    /**
     * A {@link Predicate} over {@link Request} that tests {@link Request#getMethod() method} name.
     * Using predicates in less efficient than using {@link ConditionalHandler#includeMethod(String...)}
     * and {@link ConditionalHandler#excludeMethod(String...)}, so this predicate should only be used
     * if necessary to combine with other predicates.
     */
    public static class MethodPredicate implements Predicate<Request>
    {
        private final String _method;

        public MethodPredicate(String method)
        {
            _method = Objects.requireNonNull(method);
        }

        @Override
        public boolean test(Request request)
        {
            return _method.equals(request.getMethod());
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _method);
        }
    }

    /**
     * A {@link Predicate} over {@link Request} that tests a {@link PathSpec} against
     * the {@link Request#getPathInContext(Request) pathInContext}.
     * Using predicates in less efficient than using {@link ConditionalHandler#include(PathSpec...)}
     * and {@link ConditionalHandler#exclude(PathSpec...)}, so this predicate should only be used
     * if necessary to combine with other predicates.
     *
     */
    public static class PathSpecPredicate implements Predicate<Request>
    {
        private final PathSpec _pathSpec;

        public PathSpecPredicate(PathSpec pathSpec)
        {
            _pathSpec = Objects.requireNonNull(pathSpec);
        }

        @Override
        public boolean test(Request request)
        {
            return _pathSpec.matches(Request.getPathInContext(request));
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _pathSpec);
        }
    }
}
