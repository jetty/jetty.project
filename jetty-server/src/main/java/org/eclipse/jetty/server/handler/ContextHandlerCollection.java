//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** ContextHandlerCollection.
 *
 * This {@link org.eclipse.jetty.server.handler.HandlerCollection} is creates a
 * Map of contexts to it's contained handlers based
 * on the context path and virtual hosts of any contained {@link org.eclipse.jetty.server.handler.ContextHandler}s.
 * The contexts do not need to be directly contained, only children of the contained handlers.
 * Multiple contexts may have the same context path and they are called in order until one
 * handles the request.
 *
 */
@ManagedObject("Context Handler Collection")
public class ContextHandlerCollection extends HandlerCollection
{
    private static final Logger LOG = Log.getLogger(ContextHandlerCollection.class);

    private final ConcurrentMap<ContextHandler,Handler> _contextBranches = new ConcurrentHashMap<>();
    private volatile Trie<Map.Entry<String,Branch[]>> _pathBranches;
    private Class<? extends ContextHandler> _contextClass = ContextHandler.class;

    /* ------------------------------------------------------------ */
    public ContextHandlerCollection()
    {
        super(true);
    }


    /* ------------------------------------------------------------ */
    /**
     * Remap the context paths.
     */
    @ManagedOperation("update the mapping of context path to context")
    public void mapContexts()
    {
        _contextBranches.clear();
        
        if (getHandlers()==null)
        {
            _pathBranches=new ArrayTernaryTrie<>(false,16);
            return;
        }
        
        // Create map of contextPath to handler Branch
        Map<String,Branch[]> map = new HashMap<>();
        for (Handler handler:getHandlers())
        {
            Branch branch=new Branch(handler);
            for (String contextPath : branch.getContextPaths())
            {
                Branch[] branches=map.get(contextPath);
                map.put(contextPath, ArrayUtil.addToArray(branches, branch, Branch.class));
            }
            
            for (ContextHandler context : branch.getContextHandlers())
                _contextBranches.putIfAbsent(context, branch.getHandler());
        }
        
        // Sort the branches so those with virtual hosts are considered before those without
        for (Map.Entry<String,Branch[]> entry: map.entrySet())
        {
            Branch[] branches=entry.getValue();
            Branch[] sorted=new Branch[branches.length];
            int i=0;
            for (Branch branch:branches)
                if (branch.hasVirtualHost())
                    sorted[i++]=branch;
            for (Branch branch:branches)
                if (!branch.hasVirtualHost())
                    sorted[i++]=branch;
            entry.setValue(sorted);
        }
        
        // Loop until we have a big enough trie to hold all the context paths
        int capacity=512;
        Trie<Map.Entry<String,Branch[]>> trie;
        loop: while(true)
        {
            trie=new ArrayTernaryTrie<>(false,capacity);
            for (Map.Entry<String,Branch[]> entry: map.entrySet())
            {
                if (!trie.put(entry.getKey().substring(1),entry))
                {
                    capacity+=512;
                    continue loop;
                }
            }
            break loop;
        }
            
        
        if (LOG.isDebugEnabled())
        {
            for (String ctx : trie.keySet())
                LOG.debug("{}->{}",ctx,Arrays.asList(trie.get(ctx).getValue()));
        }
        _pathBranches=trie;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.handler.HandlerCollection#setHandlers(org.eclipse.jetty.server.server.Handler[])
     */
    @Override
    public void setHandlers(Handler[] handlers)
    {
        super.setHandlers(handlers);
        if (isStarted())
            mapContexts();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        mapContexts();
        super.doStart();
    }


    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handler[] handlers = getHandlers();
        if (handlers==null || handlers.length==0)
            return;

        HttpChannelState async = baseRequest.getHttpChannelState();
        if (async.isAsync())
        {
            ContextHandler context=async.getContextHandler();
            if (context!=null)
            {
                Handler branch = _contextBranches.get(context);
                
                if (branch==null)
                    context.handle(target,baseRequest,request, response);
                else
                    branch.handle(target, baseRequest, request, response);
                return;
            }
        }
        
        // data structure which maps a request to a context; first-best match wins
        // { context path => [ context ] }
        // }
        if (target.startsWith("/"))
        {
            int limit = target.length()-1;

            while (limit>=0)
            {
                // Get best match
                Map.Entry<String,Branch[]> branches = _pathBranches.getBest(target,1,limit);
                
                
                if (branches==null)
                    break;
                
                int l=branches.getKey().length();
                if (l==1 || target.length()==l || target.charAt(l)=='/')
                {
                    for (Branch branch : branches.getValue())
                    {
                        branch.getHandler().handle(target,baseRequest, request, response);
                        if (baseRequest.isHandled())
                            return;
                    }
                }
                
                limit=l-2;
            }
        }
        else
        {
            // This may not work in all circumstances... but then I think it should never be called
            for (int i=0;i<handlers.length;i++)
            {
                handlers[i].handle(target,baseRequest, request, response);
                if ( baseRequest.isHandled())
                    return;
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Add a context handler.
     * @param contextPath  The context path to add
     * @param resourceBase the base (root) Resource
     * @return the ContextHandler just added
     */
    public ContextHandler addContext(String contextPath,String resourceBase)
    {
        try
        {
            ContextHandler context = _contextClass.newInstance();
            context.setContextPath(contextPath);
            context.setResourceBase(resourceBase);
            addHandler(context);
            return context;
        }
        catch (Exception e)
        {
            LOG.debug(e);
            throw new Error(e);
        }
    }



    /* ------------------------------------------------------------ */
    /**
     * @return The class to use to add new Contexts
     */
    public Class<?> getContextClass()
    {
        return _contextClass;
    }


    /* ------------------------------------------------------------ */
    /**
     * @param contextClass The class to use to add new Contexts
     */
    public void setContextClass(Class<? extends ContextHandler> contextClass)
    {
        if (contextClass ==null || !(ContextHandler.class.isAssignableFrom(contextClass)))
            throw new IllegalArgumentException();
        _contextClass = contextClass;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final static class Branch
    {
        private final Handler _handler;
        private final ContextHandler[] _contexts;
        
        Branch(Handler handler)
        {
            _handler=handler;

            if (handler instanceof ContextHandler)
            {
                _contexts = new ContextHandler[]{(ContextHandler)handler};
            }
            else if (handler instanceof HandlerContainer)
            {
                Handler[] contexts=((HandlerContainer)handler).getChildHandlersByClass(ContextHandler.class);
                _contexts = new ContextHandler[contexts.length];
                System.arraycopy(contexts, 0, _contexts, 0, contexts.length);
            }
            else
                _contexts = new ContextHandler[0];
        }
        
        Set<String> getContextPaths()
        {
            Set<String> set = new HashSet<String>();
            for (ContextHandler context:_contexts)
                set.add(context.getContextPath());
            return set;
        }
        
        boolean hasVirtualHost()
        {
            for (ContextHandler context:_contexts)
                if (context.getVirtualHosts()!=null && context.getVirtualHosts().length>0)
                    return true;
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
            return String.format("{%s,%s}",_handler,Arrays.asList(_contexts));
        }
    }


}
