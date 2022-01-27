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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.thread.SerializedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This {@link org.eclipse.jetty.server.handler.HandlerCollection} is creates a
 * Map of contexts to it's contained handlers based
 * on the context path and virtual hosts of any contained {@link org.eclipse.jetty.server.handler.ContextHandler}s.
 * The contexts do not need to be directly contained, only children of the contained handlers.
 * Multiple contexts may have the same context path and they are called in order until one
 * handles the request.
 */
@ManagedObject("Context Handler Collection")
public class ContextHandlerCollection extends HandlerCollection
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextHandlerCollection.class);
    private final SerializedExecutor _serializedExecutor = new SerializedExecutor();

    public ContextHandlerCollection()
    {
        super(true);
    }

    public ContextHandlerCollection(ContextHandler... contexts)
    {
        super(true);
        setHandlers(contexts);
    }

    /**
     * Remap the contexts.  Normally this is not required as context
     * mapping is maintained as a side effect of {@link #setHandlers(Handler[])}
     * However, if configuration changes in the deep handler structure (eg contextpath is changed), then
     * this call will trigger a remapping.
     * This method is mutually excluded from {@link #deployHandler(Handler, Callback)} and
     * {@link #undeployHandler(Handler, Callback)}
     */
    @ManagedOperation("Update the mapping of context path to context")
    public void mapContexts()
    {
        _serializedExecutor.execute(() ->
        {
            while (true)
            {
                Handlers handlers = _handlers.get();
                if (handlers == null)
                    break;
                if (updateHandlers(handlers, newHandlers(handlers.getHandlers())))
                    break;
            }
        });
    }

    @Override
    protected Handlers newHandlers(Handler[] handlers)
    {
        if (handlers == null || handlers.length == 0)
            return null;

        // Create map of contextPath to handler Branch
        // A branch is a Handler that could contain 0 or more ContextHandlers
        Map<String, Branch[]> path2Branches = new HashMap<>();
        for (Handler handler : handlers)
        {
            Branch branch = new Branch(handler);
            for (String contextPath : branch.getContextPaths())
            {
                Branch[] branches = path2Branches.get(contextPath);
                path2Branches.put(contextPath, ArrayUtil.addToArray(branches, branch, Branch.class));
            }
        }

        // Sort the branches for each contextPath so those with virtual hosts are considered before those without
        for (Map.Entry<String, Branch[]> entry : path2Branches.entrySet())
        {
            Branch[] branches = entry.getValue();
            Branch[] sorted = new Branch[branches.length];
            int i = 0;
            for (Branch branch : branches)
            {
                if (branch.hasVirtualHost())
                    sorted[i++] = branch;
            }
            for (Branch branch : branches)
            {
                if (!branch.hasVirtualHost())
                    sorted[i++] = branch;
            }
            entry.setValue(sorted);
        }

        Mapping mapping = new Mapping(handlers, path2Branches);
        if (LOG.isDebugEnabled())
            LOG.debug("{}", mapping._pathBranches);
        return mapping;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Mapping mapping = (Mapping)_handlers.get();

        // Handle no contexts
        if (mapping == null)
            return;
        Handler[] handlers = mapping.getHandlers();
        if (handlers == null || handlers.length == 0)
            return;

        // handle only a single context.
        if (handlers.length == 1)
        {
            handlers[0].handle(target, baseRequest, request, response);
            return;
        }

        // handle async dispatch to specific context
        HttpChannelState async = baseRequest.getHttpChannelState();
        if (async.isAsync())
        {
            ContextHandler context = async.getContextHandler();
            if (context != null)
            {
                Handler branch = mapping._contextBranches.get(context);

                if (branch == null)
                    context.handle(target, baseRequest, request, response);
                else
                    branch.handle(target, baseRequest, request, response);
                return;
            }
        }

        // handle many contexts
        if (target.startsWith("/"))
        {
            Index<Map.Entry<String, Branch[]>> pathBranches = mapping._pathBranches;
            if (pathBranches == null)
                return;

            int limit = target.length() - 1;

            while (limit >= 0)
            {
                // Get best match
                Map.Entry<String, Branch[]> branches = pathBranches.getBest(target, 1, limit);

                if (branches == null)
                    break;

                int l = branches.getKey().length();
                if (l == 1 || target.length() == l || target.charAt(l) == '/')
                {
                    for (Branch branch : branches.getValue())
                    {
                        branch.getHandler().handle(target, baseRequest, request, response);
                        if (baseRequest.isHandled())
                            return;
                    }
                }

                limit = l - 2;
            }
        }
        else
        {
            for (Handler handler : handlers)
            {
                handler.handle(target, baseRequest, request, response);
                if (baseRequest.isHandled())
                    return;
            }
        }
    }

    /**
     * Thread safe deploy of a Handler.
     * <p>
     * This method is the equivalent of {@link #addHandler(Handler)},
     * but its execution is non-block and mutually excluded from all
     * other calls to {@link #deployHandler(Handler, Callback)} and
     * {@link #undeployHandler(Handler, Callback)}.
     * The handler may be added after this call returns.
     * </p>
     *
     * @param handler the handler to deploy
     * @param callback Called after handler has been added
     */
    public void deployHandler(Handler handler, Callback callback)
    {
        if (handler.getServer() != getServer())
            handler.setServer(getServer());

        _serializedExecutor.execute(new SerializedExecutor.ErrorHandlingTask()
        {
            @Override
            public void run()
            {
                addHandler(handler);
                callback.succeeded();
            }

            @Override
            public void accept(Throwable throwable)
            {
                callback.failed(throwable);
            }
        });
    }

    /**
     * Thread safe undeploy of a Handler.
     * <p>
     * This method is the equivalent of {@link #removeHandler(Handler)},
     * but its execution is non-block and mutually excluded from all
     * other calls to {@link #deployHandler(Handler, Callback)} and
     * {@link #undeployHandler(Handler, Callback)}.
     * The handler may be removed after this call returns.
     * </p>
     *
     * @param handler The handler to undeploy
     * @param callback Called after handler has been removed
     */
    public void undeployHandler(Handler handler, Callback callback)
    {
        _serializedExecutor.execute(new SerializedExecutor.ErrorHandlingTask()
        {
            @Override
            public void run()
            {
                removeHandler(handler);
                callback.succeeded();
            }

            @Override
            public void accept(Throwable throwable)
            {
                callback.failed(throwable);
            }
        });
    }

    private static final class Branch
    {
        private final Handler _handler;
        private final ContextHandler[] _contexts;

        Branch(Handler handler)
        {
            _handler = handler;

            if (handler instanceof ContextHandler)
            {
                _contexts = new ContextHandler[]{(ContextHandler)handler};
            }
            else if (handler instanceof HandlerContainer)
            {
                Handler[] contexts = ((HandlerContainer)handler).getChildHandlersByClass(ContextHandler.class);
                _contexts = new ContextHandler[contexts.length];
                System.arraycopy(contexts, 0, _contexts, 0, contexts.length);
            }
            else
                _contexts = new ContextHandler[0];
        }

        Set<String> getContextPaths()
        {
            Set<String> set = new HashSet<>();
            for (ContextHandler context : _contexts)
            {
                set.add(context.getContextPath());
            }
            return set;
        }

        boolean hasVirtualHost()
        {
            for (ContextHandler context : _contexts)
            {
                if (context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
                    return true;
            }
            return false;
        }

        ContextHandler[] getContextHandlers()
        {
            return _contexts;
        }

        Handler getHandler()
        {
            return _handler;
        }

        @Override
        public String toString()
        {
            return String.format("{%s,%s}", _handler, Arrays.asList(_contexts));
        }
    }

    private static class Mapping extends Handlers
    {
        private final Map<ContextHandler, Handler> _contextBranches;
        private final Index<Map.Entry<String, Branch[]>> _pathBranches;

        private Mapping(Handler[] handlers, Map<String, Branch[]> path2Branches)
        {
            super(handlers);
            _pathBranches = new Index.Builder<Map.Entry<String, Branch[]>>()
                .caseSensitive(true)
                .withAll(() ->
                {
                    Map<String, Map.Entry<String, Branch[]>> result = new LinkedHashMap<>();
                    for (Map.Entry<String, Branch[]> entry : path2Branches.entrySet())
                    {
                        result.put(entry.getKey().substring(1), entry);
                    }
                    return result;
                })
                .build();

            // add new context branches to map
            Map<ContextHandler, Handler> contextBranches = new HashMap<>();
            for (Branch[] branches : path2Branches.values())
            {
                for (Branch branch : branches)
                {
                    for (ContextHandler context : branch.getContextHandlers())
                    {
                        contextBranches.put(context, branch.getHandler());
                    }
                }
            }
            _contextBranches = Collections.unmodifiableMap(contextBranches);
        }
    }
}
