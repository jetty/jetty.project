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

import org.eclipse.jetty.http.HttpStatus;
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
 * A {@link Handler.Wrapper} that conditionally handles a {@link Request}.
 * The conditions are implemented by {@link IncludeExclude}s of:
 * <ul>
 *     <li>A HTTP method name, which can be efficiently matched</li>
 *     <li>A {@link PathSpec} or string representation, which can be efficiently matched.</li>
 *     <li>An arbitrary {@link Predicate} taking the {@link Request}, which is matched in a linear test of all predicates.</li>
 * </ul>
 *
 * <p>If the conditions are met, the abstract {@link #onConditionsMet(Request, Response, Callback)} method will be invoked,
 * otherwise the {@link #onConditionsNotMet(Request, Response, Callback)} method will be invoked.  Implementations may call
 * the {@link #nextHandler(Request, Response, Callback)} method to call the wrapped handler.</p>
 *
 * <p>A typical usage is to extend the {@link Abstract} sub class and provide an implementation of
 * {@link #onConditionsMet(Request, Response, Callback)} and {@link #onConditionsNotMet(Request, Response, Callback)}:</p>
 * <pre>{@code
 * public class MyOptionalHandler extends ConditionalHandler.Abstract
 * {
 *     @Override
 *     public boolean onConditionsMet(Request request, Response response, Callback callback)
 *     {
 *         response.getHeaders().add("Test", "My Optional Handling");
 *         return nextHandle(request, response, callback);
 *     }
 *
 *     @Override
 *     public boolean onConditionsNoMet(Request request, Response response, Callback callback)
 *     {
 *         return false;
 *     }
 * }
 * }</pre>
 *
 * <p>If the conditions added to {@code MyOptionalHandler} are met, then the {@link #onConditionsMet(Request, Response, Callback)}
 * method is called and a response header added before invoking {@link #nextHandler(Request, Response, Callback)}, otherwise
 * the {@link #onConditionsNotMet(Request, Response, Callback)} is called, which returns false to indicate no more handling.</p>
 *
 * <p>Alternatively, one of the concrete subclasses may be used.  These implementations conditionally provide a specific
 * action in their {@link #onConditionsMet(Request, Response, Callback)} methods. Otherwise, these subclasses are all extension
 * of the abstract {@link ElseNext} subclass, that implements {@link #onConditionsNotMet(Request, Response, Callback)} to
 * call {@link #nextHandler(Request, Response, Callback)}.
 *
 * <ul>
 *     <li>{@link DontHandle} - If the conditions are met, terminate further handling by returning {@code false}</li>
 *     <li>{@link Reject} - If the conditions are met, reject the request with a {@link HttpStatus#FORBIDDEN_403} (or other status code) response.</li>
 *     <li>{@link SkipNext} - If the conditions are met, then the {@link #getHandler() next handler} is skipped and the
 *     {@link Singleton#getHandler() following hander} invoked instead.</li>
 * </ul>
 *
 * <p>These concrete handlers are ideal for retrofitting conditional behavior. For example, if an application handler was
 * found to not correctly handle the {@code OPTION} method for the path "/secret/*", it could be protected as follows:</p>
 * <pre>{@code
 *    Server server = new Server();
 *    ApplicationHandler application = new ApplicationHandler();
 *    server.setHandler(application);
 *
 *    ConditionalHandler reject = new ConditionalHandler.Reject(403); // or DontHandle
 *    reject.includeMethod("OPTION");
 *    reject.includePath("/secret/*");
 *    server.insertHandler(reject);
 * }</pre>
 *
 * <p>Another example, in an application comprised of several handlers, one of which is a wrapping handler whose behavior
 * needs to be skipped for "POST" requests, then it could be achieved as follows:</p>
 * <pre>{@code
 *    Server server = new Server();
 *    ApplicationWrappingHandler wrappingHandler = new ApplicationWrappingHandler();
 *    ApplicationHandler applicationHandler = new ApplicationHandler();
 *    server.setHandler(wrappingHandler);
 *    filter.setHandler(applicationHandler);
 *
 *    ConditionalHandler skipNext = new ConditionalHandler.SkipNext();
 *    skipNext.includeMethod("POST");
 *    skipNext.setHandler(wrappingHandler);
 *    server.setHandler(skipNext);
 * }</pre>
 * <p>Note that a better solution, if possible, would be for the {@code ApplicationFilterHandler} and/or
 * {@code ApplicationHandler} handlers to extend {@code ConditionalHandler}.</p>
 */
public abstract class ConditionalHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ConditionalHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _pathSpecs = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExcludeSet<Predicate<Request>, Request> _predicates = new IncludeExcludeSet<>(PredicateSet.class);
    private Predicate<Request> _handlePredicate;

    private ConditionalHandler()
    {
        this(false, null);
    }

    private ConditionalHandler(Handler nextHandler)
    {
        this(false, nextHandler);
    }

    private ConditionalHandler(boolean dynamic, Handler nextHandler)
    {
        super(dynamic, nextHandler);
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
     * tested against the {@link Request#getPathInContext(Request) pathInContext}.
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
     * tested against the {@link Request#getPathInContext(Request) pathInContext}.
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
     * tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
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
     * tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
     */
    public void includeInetAddressPattern(String... patterns)
    {
        for (String p : patterns)
            include(InetAddressPattern.from(p));
    }

    /**
     * Exclude {@link InetAddressPattern}s in the conditions to be met
     * @param patterns {@link InetAddressPattern}s that are
     * tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
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
     * tested against the {@link ConnectionMetaData#getRemoteSocketAddress() getRemoteSocketAddress()} of
     * {@link Request#getConnectionMetaData()}.
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
        _handlePredicate = TypeUtil.truePredicate();

        if (!_methods.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testMethods);
        if (!_pathSpecs.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testPathSpecs);
        if (!_predicates.isEmpty())
            _handlePredicate = _handlePredicate.and(this::testPredicates);

        super.doStart();
    }

    public final boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (_handlePredicate.test(request))
            return onConditionsMet(request, response, callback);

        return onConditionsNotMet(request, response, callback);
    }

    /**
     * Handle a request that has met the conditions.
     * Typically, the implementation will provide optional handling and then call the
     * {@link #nextHandler(Request, Response, Callback)} method to continue handling.
     * @param request The request to handle
     * @param response The response to generate
     * @param callback The callback for completion
     * @return True if this handler will complete the callback
     * @throws Exception If there is a problem handling
     * @see Handler#handle(Request, Response, Callback)
     */
    protected abstract boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception;

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
    protected abstract boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception;

    /**
     * Handle a request by invoking the {@link #handle(Request, Response, Callback)} method of the
     * {@link #getHandler() next Handler}.
     * @param request The request to handle
     * @param response The response to generate
     * @param callback The callback for completion
     * @return True if this handler will complete the callback
     * @throws Exception If there is a problem handling
     * @see Handler#handle(Request, Response, Callback)
     */
    protected boolean nextHandler(Request request, Response response, Callback callback) throws Exception
    {
        return super.handle(request, response, callback);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new DumpableCollection("included methods", _methods.getIncluded()),
            new DumpableCollection("included paths", _pathSpecs.getIncluded()),
            new DumpableCollection("included predicates", _predicates.getIncluded()),
            new DumpableCollection("excluded methods", _methods.getExcluded()),
            new DumpableCollection("excluded paths", _pathSpecs.getExcluded()),
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
     *     <li>{@link TypeUtil#truePredicate()}</li>
     *     <li>{@link ConnectorPredicate}</li>
     *     <li>{@link InetAddressPatternPredicate}</li>
     *     <li>{@link MethodPredicate}</li>
     *     <li>{@link PathSpecPredicate}</li>
     * </ul>
     * @param connectorName The connector name or {@code null}
     * @param inetAddressPattern An {@link InetAddressPattern} or {@code null}
     * @param method A {@link org.eclipse.jetty.http.HttpMethod} name or {@code null}
     * @param pathSpec A {@link PathSpec} or {@code null}
     * @return the combined {@link Predicate} over {@link Request}
     */
    public static Predicate<Request> from(String connectorName, InetAddressPattern inetAddressPattern, String method, PathSpec pathSpec)
    {
        Predicate<Request> predicate = TypeUtil.truePredicate();

        if (connectorName != null)
            predicate = predicate.and(new ConnectorPredicate(connectorName));

        if (inetAddressPattern != null)
            predicate = predicate.and(new InetAddressPatternPredicate(inetAddressPattern));

        if (method != null)
            predicate = predicate.and(new MethodPredicate(method));

        if (pathSpec != null)
            predicate = predicate.and(new PathSpecPredicate(pathSpec));
        
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
            if (_predicates.contains(predicate))
                return false;
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
        public int hashCode()
        {
            return _connector.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof ConnectorPredicate other && _connector.equals(other._connector);
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
        public boolean equals(Object other)
        {
            return other instanceof InetAddressPatternPredicate inetAddressPatternPredicate && _pattern.equals(inetAddressPatternPredicate._pattern);
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), _pattern);
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
        public int hashCode()
        {
            return _method.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof MethodPredicate other && _method.equals(other._method);
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
        public int hashCode()
        {
            return _pathSpec.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof PathSpecPredicate other && _pathSpec.equals(other._pathSpec);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _pathSpec);
        }
    }

    /**
     * An Abstract {@link ConditionalHandler}.  Implementations must provide
     * both {@link #onConditionsMet(Request, Response, Callback)} and
     * {@link #onConditionsNotMet(Request, Response, Callback)} implementations.
     */
    public abstract static class Abstract extends ConditionalHandler
    {
        protected Abstract()
        {
        }

        protected Abstract(Handler nextHandler)
        {
            super(nextHandler);
        }

        protected Abstract(boolean dynamic, Handler nextHandler)
        {
            super(dynamic, nextHandler);
        }
    }

    /**
     * An abstract implementation of {@link ConditionalHandler} that, if conditions are not met, will call
     * the {@link #nextHandler(Request, Response, Callback)} from {@link #onConditionsNotMet(Request, Response, Callback)}.
     * Implementations must provide an {@link #onConditionsMet(Request, Response, Callback)} to provide the
     * handling for when conditions are met.
     */
    public abstract static class ElseNext extends ConditionalHandler
    {
        public ElseNext()
        {
            this(null);
        }

        public ElseNext(Handler handler)
        {
            super(handler);
        }

        @Override
        protected boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception
        {
            return nextHandler(request, response, callback);
        }
    }

    /**
     * An implementation of {@link ConditionalHandler} that, if conditions are met, will not do any further
     * handling by returning {@code false} from {@link #onConditionsMet(Request, Response, Callback)}.
     * Otherwise, the {@link #nextHandler(Request, Response, Callback) next handler} will be invoked.
     */
    public static class DontHandle extends ConditionalHandler.ElseNext
    {
        public DontHandle()
        {
            super();
        }

        public DontHandle(Handler handler)
        {
            super(handler);
        }

        @Override
        protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
        {
            return false;
        }
    }

    /**
     * An implementation of {@link ConditionalHandler} that, if conditions are met, will reject
     * the request by sending a response (by default a {@link HttpStatus#FORBIDDEN_403}).
     * Otherwise, the {@link #nextHandler(Request, Response, Callback) next handler} will be invoked.
     */
    public static class Reject extends ConditionalHandler.ElseNext
    {
        private final int _status;

        public Reject()
        {
            this(null, HttpStatus.FORBIDDEN_403);
        }

        public Reject(int status)
        {
            this(null, status);
        }

        public Reject(Handler handler)
        {
            this(handler, HttpStatus.FORBIDDEN_403);
        }

        public Reject(Handler handler, int status)
        {
            super(handler);
            if (status < 200 || status > 999)
                throw new IllegalArgumentException("bad status");
            _status = status;
        }

        @Override
        protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
        {
            Response.writeError(request, response, callback, _status);
            return true;
        }
    }

    /**
     * An implementation of {@link ConditionalHandler} that, if conditions are met, will skip the next {@link Handler} by
     * invoking its {@link Singleton#getHandler() next Handler}.
     * Otherwise, the {@link #nextHandler(Request, Response, Callback) next handler} will be invoked.
     */
    public static class SkipNext extends ConditionalHandler.ElseNext
    {
        public SkipNext()
        {
            super();
        }

        public SkipNext(Handler handler)
        {
            super(handler);
        }

        @Override
        protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
        {
            if (!(getHandler() instanceof Singleton nextHandler))
                return false;
            Handler nextNext = nextHandler.getHandler();
            return nextNext != null && nextNext.handle(request, response, callback);
        }
    }
}
