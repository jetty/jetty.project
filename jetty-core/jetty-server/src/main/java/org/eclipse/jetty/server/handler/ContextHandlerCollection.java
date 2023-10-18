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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
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
public class ContextHandlerCollection extends Handler.Sequence
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextHandlerCollection.class);
    private final SerializedExecutor _serializedExecutor = new SerializedExecutor();

    public ContextHandlerCollection(ContextHandler... contexts)
    {
        this(true, contexts);
    }

    /**
     * @param dynamic If true, then contexts may be added dynamically once started,
     *                so the InvocationType is assumed to be BLOCKING, otherwise
     *                the InvocationType is fixed once started and handlers cannot be
     *                subsequently added.
     * @param contexts The contexts to add.
     */
    public ContextHandlerCollection(boolean dynamic, ContextHandler... contexts)
    {
        super(dynamic, List.of(contexts));
    }

    /**
     * Remap the contexts.  Normally this is not required as context
     * mapping is maintained as a side effect of {@link #setHandlers(Handler[])}
     * However, if configuration changes in the deep handler structure (eg contextpath is changed), then
     * this call will trigger a remapping.
     * This method is mutually excluded from {@link #deployHandler(Handler, Callback)} and
     * {@link #undeployHandler(Handler, Callback)}
     */
    @ManagedOperation(value = "Update the mapping of context path to context", impact = "ACTION")
    public void mapContexts()
    {
        _serializedExecutor.execute(() ->
        {
            List<Handler> handlers = getHandlers();
            if (handlers == null)
                return;
            super.setHandlers(newHandlers(handlers));
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
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        List<Handler> handlers = getHandlers();

        if (handlers == null || handlers.isEmpty())
            return false;

        if (!(handlers instanceof Mapping))
            return super.handle(request, response, callback);

        Mapping mapping = (Mapping)getHandlers();

        // handle only a single context.
        if (handlers.size() == 1)
            return handlers.get(0).handle(request, response, callback);

        // handle many contexts
        Index<Map.Entry<String, Branch[]>> pathBranches = mapping._pathBranches;
        if (pathBranches == null)
            return false;

        String path = Request.getPathInContext(request);
        if (!path.startsWith("/"))
        {
            return super.handle(request, response, callback);
        }

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
                    try
                    {
                        if (branch.getHandler().handle(request, response, callback))
                            return true;
                    }
                    catch (Throwable t)
                    {
                        LOG.warn("Unaccepted error {}", this, t);
                    }
                }
            }

            limit = l - 2;
        }
        return false;
    }

    @ManagedAttribute("The paths of the contexts in this collection")
    public Set<String> getContextPaths()
    {
        List<Handler> handlers = getHandlers();
        if (handlers instanceof Mapping mapping)
        {
            Index<Map.Entry<String, Branch[]>> index = mapping._pathBranches;
            return index.keySet().stream()
                .map(index::get)
                .map(Map.Entry::getValue)
                .flatMap(Stream::of)
                .flatMap(b -> b.getContextPaths().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        }
        return Set.of();
    }

    /**
     * Thread safe deploy of a Handler.
     * <p>
     * This method is the equivalent of {@link #addHandler(Handler)},
     * but its execution is non-blocking and mutually excluded from all
     * other callers to itself and {@link #undeployHandler(Handler, Callback)}.
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
     * itself. The handler may be removed after this call returns.
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
        private final List<ContextHandler> _contexts;

        Branch(Handler handler)
        {
            _handler = handler;

            if (handler instanceof ContextHandler)
            {
                _contexts = List.of((ContextHandler)handler);
            }
            else if (handler instanceof Handler.Container)
            {
                List<ContextHandler> contexts = ((Handler.Container)handler).getDescendants(ContextHandler.class);
                _contexts = new ArrayList<>(contexts);
            }
            else
                _contexts = List.of();
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

        Handler getHandler()
        {
            return _handler;
        }

        @Override
        public String toString()
        {
            return String.format("{%s,%s}", _handler, _contexts);
        }
    }

    private static class Mapping extends ArrayList<Handler>
    {
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
        }
    }
}
