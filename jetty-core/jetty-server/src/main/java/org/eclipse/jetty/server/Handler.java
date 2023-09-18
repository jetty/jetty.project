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
 * A {@code Handler} is a {@link Request.Handler} with the addition of {@link LifeCycle}
 * behaviours, plus variants that allow organizing {@code Handler}s as a tree structure.</p>
 * <p>{@code Handler}s may wrap the {@link Request}, {@link Response} and/or {@link Callback} and
 * then forward the wrapped instances to their children, so that they see a modified request;
 * and/or to intercept the read of the request content; and/or intercept the generation of the
 * response; and/or to intercept the completion of the callback.</p>
 * <p>A {@code Handler} is an {@link Invocable} and implementations must respect
 * the {@link InvocationType} they declare within calls to
 * {@link #handle(Request, Response, Callback)}.</p>
 * <p>A minimal tree structure could be:</p>
 * <pre>{@code
 * Server
 * `- YourCustomHandler
 * }</pre>
 * <p>A more sophisticated tree structure:</p>
 * <pre>{@code
 * Server
 * `- GzipHandler
 *    `- ContextHandlerCollection
 *       +- ContextHandler (contextPath="/user")
 *       |  `- YourUserHandler
 *       |- ContextHandler (contextPath="/admin")
 *       |  `- YourAdminHandler
 *       `- DefaultHandler
 * }</pre>
 * <p>A simple {@code Handler} implementation could be:</p>
 * <pre>{@code
 * class SimpleHandler extends Handler.Abstract.NonBlocking
 * {
 *     @Override
 *     public boolean handle(Request request, Response response, Callback callback)
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
 *     public boolean handle(Request request, Response response, Callback callback)
 *     {
 *         if (request.getHttpURI().getPath().startsWith("/yourPath"))
 *         {
 *             // The request is for this Handler
 *             response.setStatus(200);
 *             // The callback is completed when the write is completed.
 *             response.write(true, UTF_8.encode("hello"), callback);
 *             return true;
 *         }
 *         return false;
 *     }
 * }
 * }</pre>
 * <p>An example of a {@code Handler} that decides whether to pass the request to
 * a child:</p>
 * <pre>{@code
 * class ConditionalHandler extends Handler.Wrapper
 * {
 *     @Override
 *     public boolean handle(Request request, Response response, Callback callback)
 *     {
 *         if (request.getHttpURI().getPath().startsWith("/yourPath")
 *             return super.handle(request, response, callback);
 *         if (request.getHttpURI().getPath().startsWith("/wrong"))
 *         {
 *             Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400);
 *             return true;
 *         }
 *         return false;
 *     }
 * }
 * }</pre>
 *
 * @see Request.Handler
 */
@ManagedObject("Handler")
public interface Handler extends LifeCycle, Destroyable, Request.Handler
{
    /**
     * @return the {@code Server} associated with this {@code Handler}
     */
    @ManagedAttribute(value = "the Server instance associated to this Handler", readonly = true)
    Server getServer();

    /**
     * Set the {@code Server} to associate to this {@code Handler}.
     * @param server the {@code Server} to associate to this {@code Handler}
     */
    void setServer(Server server);

    /**
     * <p>A {@code Handler} that contains one or more other {@code Handler}s.</p>
     *
     * @see Singleton
     * @see Collection
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
         * @param type the class of the descendant {@code Handler}
         * @param <T> the type of the descendant {@code Handler}
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
         * @param type the class of the descendant {@code Handler}
         * @param <T> the type of the descendant{@code Handler}
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
         * @param handler the descendant {@code Handler}
         * @param type the class of the container {@code Handler}
         * @param <T> the type of the container {@code Handler}
         * @return the {@code Handler.Container} descendant of this {@code Handler}
         * that is the ancestor of the given {@code Handler}
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
    }

    /**
     * <p>A {@link Handler.Container} that can contain multiple other {@link Handler}s.</p>
     *
     * @see Sequence for an implementation of {@link Collection}.
     * @see Singleton
     */
    interface Collection extends Container
    {
        /**
         * <p>Adds the given {@code Handler} to this collection of {@code Handler}s.</p>
         *
         * @param handler the {@code Handler} to add
         */
        default void addHandler(Handler handler)
        {
            List<Handler> list = new ArrayList<>(getHandlers());
            list.add(handler);
            setHandlers(list);
        }

        /**
         * <p>Removes the given {@code Handler} from this collection of {@code Handler}s.</p>
         *
         * @param handler the {@code Handler} to remove
         * @return whether the {@code Handler} was removed
         */
        default boolean removeHandler(Handler handler)
        {
            List<Handler> list = new ArrayList<>(getHandlers());
            boolean removed = list.remove(handler);
            if (removed)
                setHandlers(list);
            return removed;
        }

        /**
         * <p>Adds the {@code Handler} supplied by the given {@code Supplier}
         * to this collection of {@code Handler}s.</p>
         *
         * @param supplier the {@code Handler} supplier
         */
        default void addHandler(Supplier<Handler> supplier)
        {
            addHandler(supplier.get());
        }

        /**
         * <p>Sets the given {@code Handler}s as children of this collection of {@code Handler}s.</p>
         * <p>The list is copied and any subsequent modification to the list does not have any
         * effect on this {@code Handler}.</p>
         * <p>Any existing children {@code Handler} is removed.</p>
         *
         * @param handlers the {@code Handler} to set as children
         */
        void setHandlers(List<Handler> handlers);

        /**
         * <p>Similar to {@link #setHandlers(List)}.</p>
         *
         * @param handlers the {@code Handler} to set as children
         */
        default void setHandlers(Handler... handlers)
        {
            setHandlers(handlers == null || handlers.length == 0 ? List.of() : List.of(handlers));
        }
    }

    /**
     * <p>A {@link Handler.Container} that can contain one single other {@code Handler}.</p>
     * <p>This is a "singleton" in the sense of {@link Collections#singleton(Object)} and not
     * in the sense of the singleton pattern of a single instance per JVM.</p>
     *
     * @see Wrapper for an implementation of {@link Singleton}.
     * @see Collection
     */
    interface Singleton extends Container
    {
        /**
         * @return the child {@code Handler}
         */
        Handler getHandler();

        /**
         * @param handler The {@code Handler} to set as a child
         */
        void setHandler(Handler handler);

        /**
         * <p>Sets the child {@code Handler} supplied by the given {@code Supplier}.</p>
         *
         * @param supplier the {@code Handler} supplier
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

        /**
         * <p>Inserts the given {@code Handler} (and its chain of {@code Handler}s)
         * in front of the child of this {@code Handler}.</p>
         * <p>For example, if this {@code Handler} {@code A} has a child {@code B},
         * inserting {@code Handler} {@code X} built as a chain {@code Handler}s
         * {@code X-Y-Z} results in the structure {@code A-X-Y-Z-B}.</p>
         *
         * @param handler the {@code Handler} to insert
         */
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
         * @return the tail {@link Singleton} of a chain of {@link Singleton}s
         */
        default Singleton getTail()
        {
            Singleton tail = this;
            while (tail.getHandler() instanceof Singleton wrapped)
                tail = wrapped;
            return tail;
        }

        /**
         * <p>Utility method to perform sanity checks before adding the given {@code Handler} to
         * the given {@code Singleton}, typically used in implementations of {@link #setHandler(Handler)}.</p>
         * <p>The sanity checks are:</p>
         * <ul>
         *   <li>Check for the server start state and whether the invocation type is compatible</li>
         *   <li>Check for {@code Handler} loops</li>
         *   <li>Sets the {@code Server} on the {@code Handler}</li>
         *   <li>Update the beans on the {@code Singleton} if it is a {@link ContainerLifeCycle}</li>
         * </ul>
         *
         * @param wrapper the {@code Singleton} to set the {@code Handler}
         * @param handler the {@code Handler} to set
         * @return The {@code Handler} to set
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
                throw new IllegalStateException("Handler loop");

            if (handler != null && server != null)
                handler.setServer(server);

            if (wrapper instanceof org.eclipse.jetty.util.component.ContainerLifeCycle container)
                container.updateBean(wrapper.getHandler(), handler);

            return handler;
        }
    }

    /**
     * <p>An abstract implementation of {@link Handler} that is a {@link ContainerLifeCycle}.</p>
     * <p>The {@link InvocationType} is by default {@link InvocationType#BLOCKING} unless the
     * {@code NonBlocking} variant is used or a specific {@link InvocationType} is passed to
     * the constructor.</p>
     *
     * @see NonBlocking
     */
    abstract class Abstract extends ContainerLifeCycle implements Handler
    {
        private static final Logger LOG = LoggerFactory.getLogger(Abstract.class);

        private final InvocationType _invocationType;
        private Server _server;

        /**
         * <p>Creates a {@code Handler} with invocation type {@link InvocationType#BLOCKING}.</p>
         */
        public Abstract()
        {
            this(InvocationType.BLOCKING);
        }

        /**
         * <p>Creates a {@code Handler} with the given invocation type.</p>
         *
         * @param type the {@link InvocationType} of this {@code Handler}
         * @see AbstractContainer
         */
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

        /**
         * <p>An abstract {@code Handler} with a {@link InvocationType#NON_BLOCKING}
         * invocation type.</p>
         */
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
     * <p>An {@link AbstractContainer} may be dynamic, that is allow {@code Handler}s
     * to be added after it has been started.</p>
     * <p>If this {@link AbstractContainer} is dynamic, then its invocation type
     * is by default {@link InvocationType#BLOCKING}.</p>
     *
     * @see Abstract
     */
    abstract class AbstractContainer extends Abstract implements Container
    {
        private boolean _dynamic;

        /**
         * <p>Creates an instance that is dynamic.</p>
         */
        protected AbstractContainer()
        {
            this(true);
        }

        /**
         * <p>Creates an instance with the given dynamic argument.</p>
         *
         * @param dynamic whether this container is dynamic
         */
        protected AbstractContainer(boolean dynamic)
        {
            _dynamic = dynamic;
        }

        /**
         * @return whether this container is dynamic
         */
        public boolean isDynamic()
        {
            return _dynamic;
        }

        /**
         * @param dynamic whether this container is dynamic
         */
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
     * <p>An implementation of {@link Singleton}, which is a {@link Container}
     * that wraps one single other {@link Handler}.</p>
     * <p>A {@link Wrapper} may be dynamic, that is allow {@code Handler}s
     * to be set after it has been started.</p>
     * <p>If this {@link Wrapper} is dynamic, then its invocation type
     * is by default {@link InvocationType#BLOCKING}.</p>
     */
    class Wrapper extends AbstractContainer implements Singleton
    {
        private Handler _handler;

        /**
         * <p>Creates a wrapper with no wrapped {@code Handler}.</p>
         */
        public Wrapper()
        {
            this(null);
        }

        /**
         * <p>Creates a wrapper with no wrapped {@code Handler} with the given
         * {@code dynamic} parameter.</p>
         *
         * @param dynamic whether this container is dynamic
         */
        public Wrapper(boolean dynamic)
        {
            this(dynamic, null);
        }

        /**
         * <p>Creates a non-dynamic wrapper of the given {@code Handler}.</p>
         *
         * @param handler the {@code Handler} to wrap
         */
        public Wrapper(Handler handler)
        {
            this(false, handler);
        }

        /**
         * <p>Creates a wrapper with the given dynamic parameter wrapping the
         * given {@code Handler}.</p>
         *
         * @param dynamic whether this container is dynamic
         * @param handler the {@code Handler} to wrap
         */
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
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Handler next = getHandler();
            return next != null && next.handle(request, response, callback);
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
     * <p>A {@link Handler.Container} that contains an ordered list of children {@link Handler}s
     * whose {@link Handler#handle(Request, Response, Callback)} method is invoked
     * in sequence on each child until a child returns {@code true}.</p>
     */
    class Sequence extends AbstractContainer implements Collection
    {
        private volatile List<Handler> _handlers = new ArrayList<>();

        /**
         * <p>Creates a {@code Sequence} with the given {@code Handler}s.</p>
         * <p>The created sequence is dynamic only if the {@code Handler}
         * array is {@code null} or empty.</p>
         *
         * @param handlers the {@code Handler}s of this {@code Sequence}
         */
        public Sequence(Handler... handlers)
        {
            this(handlers == null || handlers.length == 0, handlers == null ? List.of() : List.of(handlers));
        }

        /**
         * <p>Creates a {@code Sequence} with the given {@code Handler}s.</p>
         * <p>The created sequence is dynamic only if the {@code Handler}
         * list is {@code null} or empty.</p>
         *
         * @param handlers the {@code Handler}s of this {@code Sequence}
         */
        public Sequence(List<Handler> handlers)
        {
            this(handlers == null || handlers.isEmpty(), handlers);
        }

         /**
          * <p>Creates a {@code Sequence} with the given {@code dynamic} parameter
          * and the given {@code Handler}s.</p>
          *
          * @param dynamic whether this {@code Sequence} is dynamic
          * @param handlers the {@code Handler}s of this {@code Sequence}
         */
        public Sequence(boolean dynamic, List<Handler> handlers)
        {
            super(dynamic);
            setHandlers(handlers);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            for (Handler h : _handlers)
            {
                if (h.handle(request, response, callback))
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
    }
}
