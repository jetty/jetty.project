//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.core.server.handler.ErrorProcessor;
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
 * <p>A Jetty component that handles HTTP requests, of any version (HTTP/1.1, HTTP/2 or HTTP/3).</p>
 * <p>{@code Handler}s are organized in a tree structure.</p>
 * <p>An incoming HTTP request is first delivered to the {@link Server} instance
 * (itself the root {@code Handler}), which forwards it to one or more children {@code Handler}s,
 * which may recursively forward it to their children {@code Handler}s, until one of them
 * returns a non-null {@link Request.Processor}.</p>
 * <p>Returning a non-null {@code Request.Processor} indicates that the {@code Handler}
 * will process the HTTP request, and subsequent sibling or children {@code Handler}s
 * are not invoked.</p>
 * <p>If none of the {@code Handler}s returns a {@code Request.Processor}, a default HTTP 404
 * response is generated.</p>
 * <p>{@code Handler}s may wrap the {@link Request} and then forward the wrapped instance
 * to their children, so that they see modified HTTP headers or a modified HTTP URI,
 * or to intercept the read of the request content.</p>
 * <p>Similarly, {@code Handler}s may wrap the {@link Request.Processor} returned by one
 * of the descendants.</p>
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
 *
 * @see Request.Processor
 */
@ManagedObject("Handler")
public interface Handler extends LifeCycle, Destroyable, Invocable
{
    /**
     * <p>Invoked to decide whether to handle the given HTTP request.</p>
     * <p>If the HTTP request can be handled by this {@code Handler},
     * this method must return a non-null {@link Request.Processor}.</p>
     * <p>Otherwise, the HTTP request is not handled by this {@code Handler}
     * (for example, the HTTP request's URI does not match those handled
     * by this {@code Handler}), and this method must return {@code null}.</p>
     * <p>This method may inspect the HTTP request with the following rules:</p>
     * <ul>
     * <li>it may access read-only fields such as the HTTP headers, or the HTTP URI, etc.</li>
     * <li>it may wrap the {@link Request} in a {@link Request.Wrapper}, for example
     * to modify HTTP headers or modify the HTTP URI, etc.</li>
     * <li>it may directly modify {@link Request#getAttribute(String) request attributes}</li>
     * <li>it may directly add/remove request listeners supported in the {@link Request} APIs</li>
     * <li>it must <em>not</em> read the request content (otherwise an {@link IllegalStateException}
     * will be thrown)</li>
     * </ul>
     * <p>Exceptions thrown by this method are processed by an {@link ErrorProcessor},
     * if present, otherwise a default HTTP 500 error is generated.</p>
     *
     * @param request the incoming HTTP request to analyze
     * @return a non-null {@link Request.Processor} that processes the request/response,
     * or null if this {@code Handler} does not handle the request
     * @throws Exception Thrown if there is a problem handling.
     */
    Request.Processor handle(Request request) throws Exception;

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
            return getDescendantsByClass(Handler.class);
        }

        /**
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return an immutable collection of {@code Handler}s of the given type, descendants of this {@code Handler}
         */
        <T extends Handler> List<T> getDescendants(Class<T> type);

        /**
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return the first {@code Handler} of the given type, descendants of this {@code Handler},
         * or null if no such {@code Handler} exist
         */
        <T extends Handler> T getDescendant(Class<T> type);

        /**
         * @param handler the child {@code Handler}
         * @param type the type of {@code Handler}
         * @param <T> the type of {@code Handler}
         * @return the {@code Handler.Container} of the given type, parent of the given {@code Handler}
         */
        default <T extends Handler.Container> T getContainerByClass(Handler handler, Class<T> type)
        {
            if (handler == null)
                return null;

            for (T container : getDescendantsByClass(type))
            {
                if (container.getDescendantsByClass(handler.getClass()).contains(handler))
                    return container;
            }
            return null;
        }
    }

    /**
     * <p>An abstract implementation of {@link Handler} that is a {@link ContainerLifeCycle}.</p>
     */
    abstract class Abstract extends ContainerLifeCycle implements Handler
    {
        private static final Logger LOG = LoggerFactory.getLogger(Abstract.class);

        private Server _server;

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
        protected void doStart() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("starting {}", this);
            if (_server == null)
                throw new IllegalStateException(String.format("No Server set for %s", this));
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
    }

    /**
     * <p>A {@link Handler.Abstract} that implements {@link Handler.Container}.</p>
     */
    abstract class AbstractContainer extends Abstract implements Container
    {
        @Override
        public <T extends Handler> List<T> getDescendantsByClass(Class<T> type)
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
        public <T extends Handler> T getDescendantByClass(Class<T> type)
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
            InvocationType invocationType = InvocationType.NON_BLOCKING;
            for (Handler child : getHandlers())
                invocationType = Invocable.combine(invocationType, child.getInvocationType());
            return invocationType;
        }
    }

    /**
     * <p>A {@link Handler.Container} that wraps a single other {@code Handler}.</p>
     */
    class Wrapper extends AbstractContainer
    {
        private Handler _handler;

        public Handler getHandler()
        {
            return _handler;
        }

        public void setHandler(Handler handler)
        {
            Server server = getServer();
            if (server != null && server.isStarted() && handler != null &&
                server.getInvocationType() != Invocable.combine(server.getInvocationType(), handler.getInvocationType()))
                throw new IllegalArgumentException("Cannot change invocation type of started server");

            // Check for loops.
            if (handler == this || (handler instanceof Handler.Container container &&
                container.getDescendants().contains(this)))
                throw new IllegalStateException("setHandler loop");

            if (handler != null)
                handler.setServer(getServer());

            updateBean(_handler, handler);

            _handler = handler;
        }

        public void insertHandler(Handler.Wrapper handler)
        {
            Handler.Wrapper tail = handler;
            while (tail.getHandler() instanceof Handler.Wrapper)
            {
                tail = (Handler.Wrapper)tail.getHandler();
            }
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            tail.setHandler(getHandler());
            setHandler(handler);
        }

        @Override
        public List<Handler> getHandlers()
        {
            Handler next = getHandler();
            if (next == null)
                return List.of();
            return List.of(next);
        }

        @Override
        public void setServer(Server server)
        {
            super.setServer(server);
            Handler next = getHandler();
            if (next != null)
                next.setServer(getServer());
        }

        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            Handler next = getHandler();
            return next == null ? null : next.handle(request);
        }

        @Override
        public InvocationType getInvocationType()
        {
            Handler next = getHandler();
            return next == null ? InvocationType.NON_BLOCKING : next.getInvocationType();
        }
    }

    /**
     * <p>A {@link Handler.Container} that contains a list of other {@code Handler}s.</p>
     */
    class Collection extends AbstractContainer
    {
        private volatile List<Handler> _handlers = new ArrayList<>();

        public Collection(Handler... handlers)
        {
            this(List.of(handlers));
        }

        public Collection(List<Handler> handlers)
        {
            setHandlers(handlers);
        }

        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            for (Handler h : _handlers)
            {
                Request.Processor processor = h.handle(request);
                if (processor != null)
                    return processor;
            }
            return null;
        }

        @Override
        public List<Handler> getHandlers()
        {
            return _handlers;
        }

        public void setHandlers(Handler... handlers)
        {
            setHandlers(handlers.length == 0 ? null : List.of(handlers));
        }

        public void setHandlers(List<Handler> handlers)
        {
            List<Handler> newHandlers = newHandlers(handlers);

            Server server = getServer();
            InvocationType invocationType = server == null ? null : server.getInvocationType();

            // Check for loops && InvocationType changes.
            for (Handler handler : newHandlers)
            {
                if (handler == null)
                    continue;

                if (handler == this || (handler instanceof Handler.Container container &&
                    container.getDescendants().contains(this)))
                    throw new IllegalStateException("setHandler loop");
                invocationType = Invocable.combine(invocationType, handler.getInvocationType());
                if (server != null && server.isStarted() &&
                    server.getInvocationType() != Invocable.combine(server.getInvocationType(), handler.getInvocationType()))
                    throw new IllegalArgumentException("Cannot change invocation type of started server");

                handler.setServer(getServer());
            }

            updateBeans(_handlers, handlers);

            _handlers = newHandlers;
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

    /**
     * <p>A {@link Handler.Abstract} that implements {@link Request.Processor},
     * therefore handling any {@link Request} so that subclasses only need to
     * implement {@link #process(Request, Response, Callback)}.</p>
     */
    abstract class Processor extends Abstract implements Request.Processor
    {
        private final InvocationType _type;

        public Processor()
        {
            this(InvocationType.NON_BLOCKING);
        }

        public Processor(InvocationType type)
        {
            _type = type;
        }

        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            return this;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _type;
        }
    }
}
