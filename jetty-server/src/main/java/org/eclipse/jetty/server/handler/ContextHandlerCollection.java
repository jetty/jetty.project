//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
 * {@link org.eclipse.jetty.http.PathMap} to it's contained handlers based
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

    private volatile Trie<ContextHandler[]> _contexts;
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
        int capacity=512;
        
        // Loop until we have a big enough trie to hold all the context paths
        Trie<ContextHandler[]> trie;
        loop: while(true)
        {
            trie=new ArrayTernaryTrie<>(false,capacity);

            Handler[] branches = getHandlers();

            // loop over each group of contexts
            for (int b=0;branches!=null && b<branches.length;b++)
            {
                Handler[] handlers=null;

                if (branches[b] instanceof ContextHandler)
                {
                    handlers = new Handler[]{ branches[b] };
                }
                else if (branches[b] instanceof HandlerContainer)
                {
                    handlers = ((HandlerContainer)branches[b]).getChildHandlersByClass(ContextHandler.class);
                }
                else
                    continue;

                // for each context handler in a group
                for (int i=0;handlers!=null && i<handlers.length;i++)
                {
                    ContextHandler handler=(ContextHandler)handlers[i];
                    String contextPath=handler.getContextPath().substring(1);
                    ContextHandler[] contexts=trie.get(contextPath);
                    
                    if (!trie.put(contextPath,ArrayUtil.addToArray(contexts,handler,ContextHandler.class)))
                    {
                        capacity+=512;
                        continue loop;
                    }
                }
            }
            
            break;
        }
        
        // Sort the contexts so those with virtual hosts are considered before those without
        for (String ctx : trie.keySet())
        {
            ContextHandler[] contexts=trie.get(ctx);
            ContextHandler[] sorted=new ContextHandler[contexts.length];
            int i=0;
            for (ContextHandler handler:contexts)
                if (handler.getVirtualHosts()!=null && handler.getVirtualHosts().length>0)
                    sorted[i++]=handler;
            for (ContextHandler handler:contexts)
                if (handler.getVirtualHosts()==null || handler.getVirtualHosts().length==0)
                    sorted[i++]=handler;
            trie.put(ctx,sorted);
        }

        //for (String ctx : trie.keySet())
        //    System.err.printf("'%s'->%s%n",ctx,Arrays.asList(trie.get(ctx)));
        _contexts=trie;
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
	        context.handle(target,baseRequest,request, response);
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
	        ContextHandler[] contexts = _contexts.getBest(target,1,limit);
	        if (contexts==null)
	            break;

	        int l=contexts[0].getContextPath().length();
	        if (l==1 || target.length()==l || target.charAt(l)=='/')
	        {
	            for (ContextHandler handler : contexts)
	            {
	                handler.handle(target,baseRequest, request, response);
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


}
