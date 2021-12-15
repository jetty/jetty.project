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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Jetty HTTP Request Handler.
 * <p>
 * Incoming requests to the Server (itself a Handler) are passed to one or more Handlers
 * until the request is handled and a response is produced.  Handlers are asynchronous,
 * so handling may happen during or after a call to {@link #handle(Request, Response)}.
 * A handler indicates that returns true from the {@link #handle(Request, Response)} method
 * is indicated that it (or one of it's contained handlers) has undertaken to produce the
 * response and ultimately call {@link Request#succeeded()} or {@link Request#failed(Throwable)}
 * to indicate the end of the request handling.
 * <p>
 * A call to {@link #handle(Request, Response)} may:
 * <ul>
 * <li>return false to indicate that it will not handle the request</li>
 * <li>Completely generate the HTTP Response and call {@link Request#succeeded()}</li>
 * <li>Arrange for an async process to generate the HTTP Response and call {@link Request#succeeded()}</li>
 * <li>Pass the request to one or more other Handlers.</li>
 * <li>Wrap the request and/or response and pass them to one or more other Handlers.</li>
 * </ul>
 *
 */
@ManagedObject("Handler")
public interface Handler extends LifeCycle, Destroyable
{

    /**
     * Handle an HTTP request and produce a response.
     * Implementations of this method should strive to be non-blocking, but may block subject on if
     * {@link Invocable#isNonBlockingInvocation()} returns false.  Thus tasks calling this method
     * may be scheduled with type {@link InvocationType#EITHER}. The {@link Blocking} sub-class
     * will execute blocking handling if the invocation type is non-blocking.
     * @param request The immutable request, which is also a {@link Callback} used to signal success or failure.
     * @param response The muttable response
     * @return True if this handle has or will handle the request. This is a commitment to ultimately call
     *         either {@link Request#succeeded()} or {@link Request#failed(Throwable)}.
     * @throws Exception Thrown if there is a problem handling.
     */
    boolean handle(Request request, Response response) throws Exception;

    @ManagedAttribute(value = "the jetty server for this handler", readonly = true)
    Server getServer();

    @ManagedOperation(value = "destroy associated resources", impact = "ACTION")
    @Override
    void destroy();

    static <T extends Handler.Container> T findContainerOf(Handler.Container root, Class<T> type, Handler handler)
    {
        if (root == null || handler == null)
            return null;

        for (T container :  root.getChildHandlersByClass(type))
        {
            if (container.getChildHandlersByClass(handler.getClass()).contains(handler))
                return container;
        }
        return null;
    }

    /**
     * A Handler that contains one or more other handlers
     */
    interface Container extends Handler
    {
        /**
         * @return immutable collection of handlers directly contained by this handler.
         */
        @ManagedAttribute("handlers in this container")
        List<Handler> getHandlers();

        /**
         * @param byclass the child handler class to get
         * @return collection of all handlers contained by this handler and it's children of the passed type.
         */
        <T extends Handler> List<T> getChildHandlersByClass(Class<T> byclass);

        default List<Handler> getChildHandlers()
        {
            return getChildHandlersByClass(Handler.class);
        }

        /**
         * @param byclass the child handler class to get
         * @param <T> the type of handler
         * @return first handler of all handlers contained by this handler and it's children of the passed type.
         */
        <T extends Handler> T getChildHandlerByClass(Class<T> byclass);
    }

    /**
     * An Abstract Handler that is a {@link ContainerLifeCycle}
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

        void setServer(Server server)
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
                throw new IllegalStateException(String.format("No Server set for {}", this));
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
     * An abstract implementation of a Handler that container one or more other Handlers
     */
    abstract class AbstractContainer extends Abstract implements Container
    {
        @Override
        public <T extends Handler> List<T> getChildHandlersByClass(Class<T> byclass)
        {
            List<T> list = new ArrayList<>();
            expandHandler(this, list, byclass);
            return list;
        }

        @SuppressWarnings("unchecked")
        protected <H extends Handler> void expandHandler(Handler handler, List<H> list, Class<H> byClass)
        {
            if (!(handler instanceof Container))
                return;

            for (Handler h : ((Container)handler).getHandlers())
            {
                if (byClass == null || byClass.isInstance(h))
                    list.add((H)h);
                expandHandler(h, list, byClass);
            }
        }

        @Override
        public <T extends Handler> T getChildHandlerByClass(Class<T> byclass)
        {
            return findHandler(this, byclass);
        }

        @SuppressWarnings("unchecked")
        protected <H extends Handler> H findHandler(Handler handler, Class<H> byClass)
        {
            if (!(handler instanceof Container))
                return null;

            for (Handler h : ((Container)handler).getHandlers())
            {
                if (byClass == null || byClass.isInstance(h))
                    return ((H)h);
                H c = findHandler(h, byClass);
                if (c != null)
                    return c;
            }
            return null;
        }

        @Override
        void setServer(Server server)
        {
            super.setServer(server);
            for (Handler h : getHandlers())
            {
                if (h instanceof Abstract)
                    ((Abstract)h).setServer(server);
            }
        }
    }

    /**
     * A Handler Container that wraps a single other Handler
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
            // check for loops
            if (handler == this || (handler instanceof Handler.Container &&
                ((Handler.Container)handler).getChildHandlers().contains(this)))
                throw new IllegalStateException("setHandler loop");

            if (handler instanceof Abstract)
                ((Abstract)handler).setServer(getServer());
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
            if (_handler == null)
                return Collections.emptyList();
            return Collections.singletonList(_handler);
        }

        @Override
        void setServer(Server server)
        {
            super.setServer(server);
            if (_handler instanceof Abstract)
                ((Abstract)_handler).setServer(getServer());
        }

        @Override
        public boolean handle(Request request, Response response) throws Exception
        {
            Handler next = getHandler();
            return next != null && next.handle(request, response);
        }
    }

    // TODO review
    @Deprecated
    class HotSwap extends Wrapper
    {
        volatile Handler _hotHandler;

        @Override
        public Handler getHandler()
        {
            return _hotHandler;
        }

        @Override
        public void setHandler(Handler handler)
        {
            super.setHandler(handler);
            if (isStarted())
                LifeCycle.start(handler);
            _hotHandler = handler;
        }
    }

    /**
     * A Handler Container that wraps a list of other Handlers.
     * By default, each handler is called in turn until one returns true from {@link Handler#handle(Request, Response)}.
     */
    class Collection extends AbstractContainer
    {
        private volatile List<Handler> _handlers = new ArrayList<>();

        public Collection(Handler... handlers)
        {
            if (handlers.length > 0)
                setHandlers(handlers);
        }

        public Collection(List<Handler> handlers)
        {
            setHandlers(handlers);
        }

        @Override
        public boolean handle(Request request, Response response) throws Exception
        {
            for (Handler h : _handlers)
            {
                if (h.handle(request, response))
                    return true;
            }
            return false;
        }

        @Override
        public List<Handler> getHandlers()
        {
            return _handlers;
        }

        public void setHandlers(Handler... handlers)
        {
            setHandlers(handlers.length == 0 ? null : Arrays.asList(handlers));
        }

        protected List<Handler> newHandlers(List<Handler> handlers)
        {
            return handlers == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(handlers));
        }

        public void setHandlers(List<Handler> handlers)
        {
            List<Handler> newHandlers = newHandlers(handlers);

            // check for loops
            for (Handler handler : newHandlers)
            {
                if (handler == this || (handler instanceof Handler.Container &&
                    ((Handler.Container)handler).getChildHandlers().contains(this)))
                    throw new IllegalStateException("setHandler loop");
                if (handler instanceof Abstract)
                    ((Abstract)handler).setServer(getServer());
            }

            updateBeans(_handlers, handlers);
            _handlers = newHandlers;
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
