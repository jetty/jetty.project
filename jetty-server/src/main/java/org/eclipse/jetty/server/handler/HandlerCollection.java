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
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/* ------------------------------------------------------------ */
/** 
 * A collection of handlers.
 * <p>
 * The default implementations  calls all handlers in list order,
 * regardless of the response status or exceptions. Derived implementation
 * may alter the order or the conditions of calling the contained
 * handlers.
 */
@ManagedObject("Handler of multiple handlers")
public class HandlerCollection extends AbstractHandlerContainer
{
    private final boolean _mutableWhenRunning;
    private volatile Handler[] _handlers;

    /* ------------------------------------------------------------ */
    public HandlerCollection()
    {
        _mutableWhenRunning=false;
    }

    /* ------------------------------------------------------------ */
    public HandlerCollection(boolean mutableWhenRunning)
    {
        _mutableWhenRunning=mutableWhenRunning;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the handlers.
     */
    @Override
    @ManagedAttribute(value="Wrapped handlers", readonly=true)
    public Handler[] getHandlers()
    {
        return _handlers;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param handlers The handlers to set.
     */
    public void setHandlers(Handler[] handlers)
    {
        if (!_mutableWhenRunning && isStarted())
            throw new IllegalStateException(STARTED);

        if (handlers!=null)
        {
            // check for loops
            for (Handler handler:handlers)
                if (handler == this || (handler instanceof HandlerContainer &&
                    Arrays.asList(((HandlerContainer)handler).getChildHandlers()).contains(this)))
                        throw new IllegalStateException("setHandler loop");
          
            // Set server
            for (Handler handler:handlers)
                if (handler.getServer()!=getServer())
                    handler.setServer(getServer());
        }
        Handler[] old=_handlers;;
        _handlers = handlers;
        updateBeans(old, handlers);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see Handler#handle(String, Request, HttpServletRequest, HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (_handlers!=null && isStarted())
        {
            MultiException mex=null;

            for (int i=0;i<_handlers.length;i++)
            {
                try
                {
                    _handlers[i].handle(target,baseRequest, request, response);
                }
                catch(IOException e)
                {
                    throw e;
                }
                catch(RuntimeException e)
                {
                    throw e;
                }
                catch(Exception e)
                {
                    if (mex==null)
                        mex=new MultiException();
                    mex.add(e);
                }
            }
            if (mex!=null)
            {
                if (mex.size()==1)
                    throw new ServletException(mex.getThrowable(0));
                else
                    throw new ServletException(mex);
            }

        }
    }

    /* ------------------------------------------------------------ */
    /* Add a handler.
     * This implementation adds the passed handler to the end of the existing collection of handlers.
     * @see org.eclipse.jetty.server.server.HandlerContainer#addHandler(org.eclipse.jetty.server.server.Handler)
     */
    public void addHandler(Handler handler)
    {
        setHandlers(ArrayUtil.addToArray(getHandlers(), handler, Handler.class));
    }

    /* ------------------------------------------------------------ */
    public void removeHandler(Handler handler)
    {
        Handler[] handlers = getHandlers();

        if (handlers!=null && handlers.length>0 )
            setHandlers(ArrayUtil.removeFromArray(handlers, handler));
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
        if (getHandlers()!=null)
            for (Handler h:getHandlers())
                expandHandler(h, list, byClass);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        Handler[] children=getChildHandlers();
        setHandlers(null);
        for (Handler child: children)
            child.destroy();
        super.destroy();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        Handler[] handlers=getHandlers();
        return super.toString()+(handlers==null?"[]":Arrays.asList(getHandlers()).toString());
    }
}
