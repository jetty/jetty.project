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

package org.eclipse.jetty.server.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.thread.SerializedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a
 * Map of contexts to it's contained handlers based
 * on the context path and virtual hosts of any contained {@link ContextHandler}s.
 * The contexts do not need to be directly contained, only children of the contained handlers.
 * Multiple contexts may have the same context path and they are called in order until one
 * handles the request.
 */
@ManagedObject("Context Handler Collection")
public class ContextHandlerCollection extends Handler.Collection
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextHandlerCollection.class);
    private final SerializedExecutor _serializedExecutor = new SerializedExecutor();

    public ContextHandlerCollection(ContextHandler... contexts)
    {
        if (contexts.length > 0)
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
                List<Handler> handlers = getHandlers();
                if (handlers == null)
                    break;
                super.setHandlers(newHandlers(handlers));
            }
        });
    }

    @Override
    protected List<Handler> newHandlers(List<Handler> handlers)
    {
        if (handlers == null || handlers.size() == 0)
            return Collections.emptyList();

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
    public boolean handle(Request request, Response response) throws Exception
    {
        List<Handler> handlers = getHandlers();

        // Handle no contexts
        if (handlers == null || handlers.isEmpty())
            return false;

        if (!(handlers instanceof Mapping))
            return super.handle(request, response);

        Mapping mapping = (Mapping)getHandlers();

        // handle only a single context.
        if (handlers.size() == 1)
            return handlers.get(0).handle(request, response);

        // handle many contexts
        Index<Map.Entry<String, Branch[]>> pathBranches = mapping._pathBranches;
        if (pathBranches == null)
            return false;

        String path = request.getPath();
        if (!path.startsWith("/"))
            return super.handle(request, response);

        int limit = path.length() - 1;

        while (limit >= 0)
        {
            // Get best match
            Map.Entry<String, Branch[]> branches = pathBranches.getBest(path, 1, limit);

            if (branches == null)
                break;

            int l = branches.getKey().length();
            if (l == 1 || path.length() == l || path.charAt(l) == '/')
            {
                for (Branch branch : branches.getValue())
                {
                    if (branch.getHandler().handle(request, response))
                        return true;
                }
            }

            limit = l - 2;
        }

        return false;
    }

    /**
     * Thread safe deploy of a Handler.
     * <p>
     * This method is the equivalent of {@link #addHandler(Handler)},
     * but its execution is non-blocking and mutually excluded from all
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
            else if (handler instanceof Handler.Container)
            {
                List<ContextHandler> contexts = ((Handler.Container)handler).getChildHandlersByClass(ContextHandler.class);
                _contexts = new ContextHandler[contexts.size()];
                System.arraycopy(contexts, 0, _contexts, 0, contexts.size());
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
                if (context.getVirtualHosts() != null && context.getVirtualHosts().size() > 0)
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

    private static class Mapping extends ArrayList<Handler>
    {
        private final Map<ContextHandler, Handler> _contextBranches;
        private final Index<Map.Entry<String, Branch[]>> _pathBranches;

        private Mapping(List<Handler> handlers, Map<String, Branch[]> path2Branches)
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
