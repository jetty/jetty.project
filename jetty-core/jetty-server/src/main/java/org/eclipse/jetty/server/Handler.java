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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Jetty component that handles HTTP requests, of any version (HTTP/1.1, HTTP/2 or HTTP/3).
 * A {@code Handler} is a {@link Request.Processor} with the addition of {@link LifeCycle}
 * behaviours, plus variants that allow organizing {@code Handler}s as a tree structure.</p>
 * <p>{@code Handler}s may wrap the {@link Request}, {@link Response} and/or {@link Callback} and
 * then forward the wrapped instances to their children, so that they see a modified request;
 * and/or to intercept the read of the request content; and/or intercept the generation of the
 * response; and/or to intercept the completion of the callback.
 * <p>A {@code Handler} is an {@link Invocable} and implementations must respect
 * the {@link InvocationType} they declare within calls to
 * {@link #process(Request, Response, Callback)}.</p>
 * <p>A minimal tree structure could be:</p>
 * <pre>
 * Server
 * `- YourCustomHandler
 * </pre>
 * <p>A more sophisticated tree structure:</p>
 * <pre>
 * Server
 * `- DelayedHandler.UntilContent
 *    `- GzipHandler
 *       `- ContextHandlerCollection
 *          +- ContextHandler (contextPath="/user")
 *          |  `- YourUserHandler
 *          |- ContextHandler (contextPath="/admin")
 *          |  `- YourAdminHandler
 *          `- DefaultHandler
 * </pre>
 * <p>A simple {@code Handler} implementation could be:</p>
 * <pre>{@code
 * class SimpleHandler extends Handler.Abstract.NonBlocking
 * {
 *     @Override
 *     public boolean process(Request request, Response response, Callback callback)
 *     {
 *         // Implicitly sends a 200 OK response with no content.
 *         callback.succeeded();
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <p>A more sophisticated example of a {@code Handler} that decides whether to handle
 * requests based on their URI path:</p>
 * <pre>{@code
 * class YourHelloHandler extends Handler.Abstract.NonBlocking
 * {
 *     @Override
 *     public boolean process(Request request, Response response, Callback callback)
 *     {
 *         if (request.getHttpURI().getPath().startsWith("/yourPath"))
 *         {
 *             // The request is for this Handler
 *             response.setStatus(200);
 *             // The callback is completed when the write is completed.
 *             response.write(true, callback, "hello");
 *             return true;
 *         }
 *         return false;
 *     }
 * }
 * }</pre>
 * <p>An example of a {@code Handler} that decides whether to pass the request to
 * a child:
 * <pre>{@code
 * class ConditionalHandler extends Handler.Wrapper
 * {
 *     @Override
 *     public boolean process(Request request, Response response, Callback callback)
 *     {
 *         if (request.getHttpURI().getPath().startsWith("/yourPath")
 *             return super.process(request, response, callback);
 *         if (request.getHttpURI().getPath().startsWith("/wrong"))
 *         {
 *             Response.writeError(request, response, callback, 400);
 *             return true;
 *         }
 *         return false;
 *     }
 * }
 * }</pre>
 *
 * @see Request.Processor
 */
@ManagedObject("Handler")
public interface Handler extends LifeCycle, Destroyable, Invocable, Request.Processor
{
    /**
     * @return the {@code Server} associated with this {@code Handler}
     */
    @ManagedAttribute(value = "the Server instance associated to this Handler", readonly = true)
    Server getServer();

    /**
     * @param server the {@code Server} to associate to this {@code Handler}
     */
    void setServer(Server server);

    @Override
    void destroy();

    /**
     * <p>A {@code Handler} that contains one or more other {@code Handler}s.
     */
    interface Container extends Handler
    {
        /**
         * @return an immutable collection of {@code Handler}s directly contained by this {@code Handler}.
         */
        @ManagedAttribute("The direct children Handlers of this container")
        List<Handler> getHandlers();

        /**
         * @return an immutable collection of {@code Handler}s descendants of this {@code Handler}.
         */
        default List<Handler> getDescendants()
        {
            return getDescendants(Handler.class);
        }

        /**
         * Get a list of descendants of the passed type.
         * The default implementation is not memory efficient and should be overridden.
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return an immutable collection of {@code Handler}s of the given type, descendants of this {@code Handler}
         */
        default <T extends Handler> List<T> getDescendants(Class<T> type)
        {
            List<T> handlers = new ArrayList<>();
            for (Handler h : getHandlers())
            {
                if (type.isInstance(h))
                {
                    @SuppressWarnings("unchecked")
                    T t = (T)h;
                    handlers.add(t);
                }
                if (h instanceof Container c)
                    handlers.addAll(c.getDescendants(type));
            }
            return handlers;
        }

        /**
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return the first {@code Handler} of the given type, descendants of this {@code Handler},
         * or null if no such {@code Handler} exist
         */
        default <T extends Handler> T getDescendant(Class<T> type)
        {
            for (Handler h : getHandlers())
            {
                if (type.isInstance(h))
                {
                    @SuppressWarnings("unchecked")
                    T t = (T)h;
                    return t;
                }
                if (h instanceof Container c)
                {
                    T t = c.getDescendant(type);
                    if (t != null)
                        return t;
                }
            }
            return null;
        }

        /**
         * @param handler the child {@code Handler}
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return the {@code Handler.Container} of the given type, parent of the given {@code Handler}
         */
        default <T extends Handler.Container> T getContainer(Handler handler, Class<T> type)
        {
            if (handler == null)
                return null;

            for (T container : getDescendants(type))
            {
                if (container.getDescendants(handler.getClass()).contains(handler))
                    return container;
            }
            return null;
        }

        /**
         * <p>Make a {@link Container} the parent of a {@link Handler}</p>
         * @param parent The {@link Container} that will be the parent
         * @param handler The {@link Handler} that will be the child
         */
        static void setAsParent(Container parent, Handler handler)
        {
            if (parent instanceof Collection collection)
                collection.addHandler(handler);
            else if (parent instanceof Singleton wrapper)
                wrapper.setHandler(handler);
            else if (parent != null)
                throw new IllegalArgumentException("Unknown parent type: " + parent);
        }
    }

    /**
     * <p>A {@link Handler.Container} that can contain multiple other {@link Handler}s.</p>
     * @see Sequence for an implementation of {@link Collection}.
     */
    interface Collection extends Container
    {
        void addHandler(Handler handler);

        default void addHandler(Supplier<Handler> supplier)
        {
            addHandler(supplier.get());
        }

        void setHandlers(List<Handler> handlers);

        default void setHandlers(Handler... handlers)
        {
            setHandlers(handlers.length == 0 ? null : List.of(handlers));
        }
    }

    /**
     * <p>A {@link Handler.Container} that can contain a single other {@code Handler}.</p>
     * <p>This is "singleton" in the sense of {@link Collections#singleton(Object)} and not
     * in the sense of the singleton pattern of a single instance per JVM.</p>
     * @see Wrapper for an implementation of {@link Singleton}.
     */
    interface Singleton extends Container
    {
        Handler getHandler();

        /**
         * Set the nested handler.
         * Implementations should check for loops, set the server and update any {@link ContainerLifeCycle} beans, all
         * of which can be done by using the utility method {@link #updateHandler(Singleton, Handler)}
         * @param handler The handler to set.
         */
        void setHandler(Handler handler);

        /**
         * Set the nested handler from a supplier.  This allows for Handler type conversion.
         * @param supplier A supplier of a Handler.
         */
        default void setHandler(Supplier<Handler> supplier)
        {
            setHandler(supplier.get());
        }

        @Override
        default List<Handler> getHandlers()
        {
            Handler next = getHandler();
            return (next == null) ? Collections.emptyList() : Collections.singletonList(next);
        }

        default void insertHandler(Singleton handler)
        {
            Singleton tail = handler;
            while (tail.getHandler() instanceof Wrapper)
            {
                tail = (Wrapper)tail.getHandler();
            }
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            tail.setHandler(getHandler());
            setHandler(handler);
        }

        /**
         * @return The tail {@link Singleton} of a chain of {@link Singleton}s
         */
        default Singleton getTail()
        {
            Singleton tail = this;
            while (tail.getHandler() instanceof Singleton wrapped)
                tail = wrapped;
            return tail;
        }

        /**
         * Utility method to: <ul>
         *     <li>Check the server state and invocation type</li>
         *     <li>Check for handler loops</li>
         *     <li>Set the server on the handler</li>
         *     <li>Update the beans on if the Nests is a {@link ContainerLifeCycle} </li>
         * </ul>
         * @param wrapper The Nested implementation to update
         * @param handler The handle to set
         * @return The set handler.
         */
        static Handler updateHandler(Singleton wrapper, Handler handler)
        {
            // check state
            Server server = wrapper.getServer();

            // If the collection is changed whilst started, then the risk is that if we switch from NON_BLOCKING to BLOCKING
            // whilst the execution strategy may have already dispatched the very last available thread, thinking it would
            // never block, only for it to lose the race and find a newly added BLOCKING handler.
            if (server != null && server.isStarted() && handler != null)
            {
                InvocationType serverInvocationType = server.getInvocationType();
                if (serverInvocationType != Invocable.combine(serverInvocationType, handler.getInvocationType()) &&
                    serverInvocationType != InvocationType.BLOCKING)
                    throw new IllegalArgumentException("Cannot change invocation type of started server");
            }

            // Check for loops.
            if (handler == wrapper || (handler instanceof Handler.Container container &&
                container.getDescendants().contains(wrapper)))
                throw new IllegalStateException("setHandler loop");

            if (handler != null && server != null)
                handler.setServer(server);

            if (wrapper instanceof org.eclipse.jetty.util.component.ContainerLifeCycle container)
                container.updateBean(wrapper.getHandler(), handler);

            return handler;
        }
    }

    /**
     * <p>An abstract implementation of {@link Handler} that is a {@link ContainerLifeCycle}.</p>
     * <p>The {@link InvocationType} is by default {@link InvocationType#BLOCKING} unless a
     * {@code NonBlocking} variant has been extended or a specific
     * {@link InvocationType} passed to a constructor.</p>
     */
    abstract class Abstract extends ContainerLifeCycle implements Handler
    {
        private static final Logger LOG = LoggerFactory.getLogger(Abstract.class);

        private final InvocationType _invocationType;
        private Server _server;

        public Abstract()
        {
            this(InvocationType.BLOCKING);
        }

        public Abstract(InvocationType type)
        {
            _invocationType = type;
        }

        @Override
        public Server getServer()
        {
            return _server;
        }

        @Override
        public void setServer(Server server)
        {
            if (_server == server)
                return;
            if (isStarted())
                throw new IllegalStateException(getState());
            _server = server;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _invocationType;
        }

        @Override
        protected void doStart() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("starting {}", this);
            if (_server == null)
                LOG.warn("No Server set for {}", this);
            super.doStart();
        }

        @Override
        protected void doStop() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("stopping {}", this);
            super.doStop();
        }

        @Override
        public void destroy()
        {
            if (isRunning())
                throw new IllegalStateException(getState());
            super.destroy();
        }

        public abstract static class NonBlocking extends Abstract
        {
            public NonBlocking()
            {
                super(InvocationType.NON_BLOCKING);
            }
        }
    }

    /**
     * <p>A {@link Handler.Abstract} that implements {@link Handler.Container}.</p>
     */
    abstract class AbstractContainer extends Abstract implements Container
    {
        private boolean _dynamic;

        protected AbstractContainer()
        {
            this(true);
        }

        /**
         * @param dynamic If true, then handlers may be added to the container dynamically when started,
         *                thus the InvocationType is assumed to be BLOCKING,
         *                otherwise handlers cannot be modified once the container is started.
         */
        protected AbstractContainer(boolean dynamic)
        {
            _dynamic = dynamic;
        }

        public boolean isDynamic()
        {
            return _dynamic;
        }

        public void setDynamic(boolean dynamic)
        {
            if (isStarted())
                throw new IllegalStateException(getState());
            _dynamic = dynamic;
        }

        @Override
        public <T extends Handler> List<T> getDescendants(Class<T> type)
        {
            List<T> list = new ArrayList<>();
            expandHandler(this, list, type);
            return list;
        }

        @SuppressWarnings("unchecked")
        private <H extends Handler> void expandHandler(Handler handler, List<H> list, Class<H> type)
        {
            if (!(handler instanceof Container container))
                return;

            for (Handler child : container.getHandlers())
            {
                if (type == null || type.isInstance(child))
                    list.add((H)child);
                expandHandler(child, list, type);
            }
        }

        @Override
        public <T extends Handler> T getDescendant(Class<T> type)
        {
            return findHandler(this, type);
        }

        @SuppressWarnings("unchecked")
        private <H extends Handler> H findHandler(Handler handler, Class<H> type)
        {
            if (!(handler instanceof Container container))
                return null;

            for (Handler child : container.getHandlers())
            {
                if (type == null || type.isInstance(child))
                    return (H)child;
                H descendant = findHandler(child, type);
                if (descendant != null)
                    return descendant;
            }
            return null;
        }

        @Override
        public void setServer(Server server)
        {
            super.setServer(server);
            for (Handler child : getHandlers())
            {
                child.setServer(server);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            // Dynamic is always BLOCKING, as a blocking handler can be added at any time.
            if (_dynamic)
                return InvocationType.BLOCKING;
            InvocationType invocationType = InvocationType.NON_BLOCKING;
            for (Handler child : getHandlers())
                invocationType = Invocable.combine(invocationType, child.getInvocationType());
            return invocationType;
        }

        @SuppressWarnings("unchecked")
        public static <T extends Handler.Container> T findContainerOf(Handler.Container root, Class<T> type, Handler handler)
        {
            if (root == null || handler == null)
                return null;

            List<Handler.Container> branches = (List<Handler.Container>)root.getDescendants(type);
            if (branches != null)
            {
                for (Handler.Container container : branches)
                {
                    List<Handler> candidates = (List<Handler>)container.getDescendants(handler.getClass());
                    if (candidates != null)
                    {
                        for (Handler c : candidates)
                        {
                            if (c == handler)
                                return (T)container;
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * An implementation of {@link Singleton}, which is a {@link Handler.Container} that wraps a single other {@link Handler}.
     */
    class Wrapper extends AbstractContainer implements Singleton
    {
        private Handler _handler;

        public Wrapper()
        {
            this(null);
        }

        public Wrapper(boolean dynamic)
        {
            this(dynamic, null);
        }

        public Wrapper(Handler handler)
        {
            this(false, handler);
        }

        public Wrapper(boolean dynamic, Handler handler)
        {
            super(dynamic);
            _handler = handler == null ? null : Singleton.updateHandler(this, handler);
        }

        @Override
        public Handler getHandler()
        {
            return _handler;
        }

        @Override
        public void setHandler(Handler handler)
        {
            if (!isDynamic() && isStarted())
                throw new IllegalStateException(getState());
            _handler = Singleton.updateHandler(this, handler);
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            Handler next = getHandler();
            return next != null && next.process(request, response, callback);
        }

        @Override
        public InvocationType getInvocationType()
        {
            if (isDynamic())
                return InvocationType.BLOCKING;
            Handler next = getHandler();
            return next == null ? InvocationType.NON_BLOCKING : next.getInvocationType();
        }
    }

    /**
     * <p>A {@link Handler.Container} that contains a list of other {@code Handler}s that are
     * tried in sequence by {@link #process(Request, Response, Callback)}.</p>
     */
    class Sequence extends AbstractContainer implements Collection
    {
        private volatile List<Handler> _handlers = new ArrayList<>();

        public Sequence(Handler... handlers)
        {
            this(handlers.length == 0, List.of(handlers));
        }

        public Sequence(boolean dynamic)
        {
            this(dynamic, Collections.emptyList());
        }

        public Sequence(List<Handler> handlers)
        {
            this(handlers == null || handlers.size() == 0, handlers);
        }

         /**
         * @param dynamic If true, then handlers may be added dynamically once started,
         *                so the InvocationType is assumed to be BLOCKING, otherwise
         *                handlers cannot be modified once started.
         *
         * @param handlers The handlers to add.
         */
        public Sequence(boolean dynamic, List<Handler> handlers)
        {
            super(dynamic);
            setHandlers(handlers);
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            for (Handler h : _handlers)
            {
                if (h.process(request, response, callback))
                    return true;
            }
            return false;
        }

        @Override
        public List<Handler> getHandlers()
        {
            return _handlers;
        }

        @Override
        public void setHandlers(List<Handler> handlers)
        {
            if (!isDynamic() && isStarted())
                throw new IllegalStateException(getState());

            List<Handler> newHandlers = newHandlers(handlers);

            Server server = getServer();
            InvocationType serverInvocationType = server == null ? null : server.getInvocationType();
            InvocationType invocationType = InvocationType.NON_BLOCKING;

            // Check for loops && InvocationType changes.
            for (Handler handler : newHandlers)
            {
                if (handler == null)
                    continue;

                if (handler == this || (handler instanceof Handler.Container container &&
                    container.getDescendants().contains(this)))
                    throw new IllegalStateException("setHandler loop");
                invocationType = Invocable.combine(invocationType, handler.getInvocationType());
                if (server != null)
                    handler.setServer(server);
            }

            // If the collection can be changed dynamically, then the risk is that if we switch from NON_BLOCKING to BLOCKING
            // whilst the execution strategy may have already dispatched the very last available thread, thinking it would
            // never block, only for it to lose the race and find a newly added BLOCKING handler.
            if (isDynamic() && server != null && server.isStarted() && serverInvocationType != invocationType && serverInvocationType != InvocationType.BLOCKING)
                throw new IllegalArgumentException("Cannot change invocation type of started server");

            updateBeans(_handlers, handlers);

            _handlers = newHandlers;
        }

        @Override
        public InvocationType getInvocationType()
        {
            if (isDynamic())
                return InvocationType.BLOCKING;

            InvocationType invocationType = InvocationType.NON_BLOCKING;
            for (Handler handler : _handlers)
                invocationType = Invocable.combine(invocationType, handler.getInvocationType());
            return invocationType;
        }

        protected List<Handler> newHandlers(List<Handler> handlers)
        {
            return handlers == null ? List.of() : List.copyOf(handlers);
        }

        public void addHandler(Handler handler)
        {
            List<Handler> list = new ArrayList<>(getHandlers());
            list.add(handler);
            setHandlers(list);
        }

        public void removeHandler(Handler handler)
        {
            List<Handler> list = new ArrayList<>(getHandlers());
            if (list.remove(handler))
                setHandlers(list);
        }
    }
}
