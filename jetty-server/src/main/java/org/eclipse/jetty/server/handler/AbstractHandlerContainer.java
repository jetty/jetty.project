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

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.TypeUtil;


/* ------------------------------------------------------------ */
/** Abstract Handler Container.
 * This is the base class for handlers that may contain other handlers.
 *
 */
public abstract class AbstractHandlerContainer extends AbstractHandler implements HandlerContainer
{
    /* ------------------------------------------------------------ */
    public AbstractHandlerContainer()
    {
    }
    
    /* ------------------------------------------------------------ */
    public Handler[] getChildHandlers()
    {
        Object list = expandChildren(null,null);
        return (Handler[])LazyList.toArray(list, Handler.class);
    }
        
    /* ------------------------------------------------------------ */
    public Handler[] getChildHandlersByClass(Class<?> byclass)
    {
        Object list = expandChildren(null,byclass);
        return (Handler[])LazyList.toArray(list, byclass);
    }
    
    /* ------------------------------------------------------------ */
    public <T extends Handler> T getChildHandlerByClass(Class<T> byclass)
    {
        // TODO this can be more efficient?
        Object list = expandChildren(null,byclass);
        if (list==null)
            return null;
        return (T)LazyList.get(list, 0);
    }
    
    /* ------------------------------------------------------------ */
    protected Object expandChildren(Object list, Class<?> byClass)
    {
        return list;
    }

    /* ------------------------------------------------------------ */
    protected Object expandHandler(Handler handler, Object list, Class<Handler> byClass)
    {
        if (handler==null)
            return list;
        
        if (byClass==null || byClass.isAssignableFrom(handler.getClass()))
            list=LazyList.add(list, handler);

        if (handler instanceof AbstractHandlerContainer)
            list=((AbstractHandlerContainer)handler).expandChildren(list, byClass);
        else if (handler instanceof HandlerContainer)
        {
            HandlerContainer container = (HandlerContainer)handler;
            Handler[] handlers=byClass==null?container.getChildHandlers():container.getChildHandlersByClass(byClass);
            list=LazyList.addArray(list, handlers);
        }
        
        return list;
    }
    
    /* ------------------------------------------------------------ */
    public static <T extends HandlerContainer> T findContainerOf(HandlerContainer root,Class<T>type, Handler handler)
    {
        if (root==null || handler==null)
            return null;
        
        Handler[] branches=root.getChildHandlersByClass(type);
        if (branches!=null)
        {
            for (Handler h:branches)
            {
                T container = (T)h;
                Handler[] candidates = container.getChildHandlersByClass(handler.getClass());
                if (candidates!=null)
                {
                    for (Handler c:candidates)
                        if (c==handler)
                            return container;
                }
            }
        }
        return null;
    }
    
    /* ------------------------------------------------------------ */
    public void dump(Appendable out,String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,getBeans(),TypeUtil.asList(getHandlers()));
    }
}
