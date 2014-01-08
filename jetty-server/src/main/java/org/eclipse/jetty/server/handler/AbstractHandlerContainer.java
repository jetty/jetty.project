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


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;


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
    @Override
    public Handler[] getChildHandlers()
    {
        List<Handler> list=new ArrayList<>();
        expandChildren(list,null);
        return list.toArray(new Handler[list.size()]);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Handler[] getChildHandlersByClass(Class<?> byclass)
    {
        List<Handler> list=new ArrayList<>();
        expandChildren(list,byclass);
        return list.toArray(new Handler[list.size()]);
    }

    /* ------------------------------------------------------------ */
    @Override
    public <T extends Handler> T getChildHandlerByClass(Class<T> byclass)
    {
        List<Handler> list=new ArrayList<>();
        expandChildren(list,byclass);
        if (list.isEmpty())
            return null;
        return (T)list.get(0);
    }

    /* ------------------------------------------------------------ */
    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
    }

    /* ------------------------------------------------------------ */
    protected void expandHandler(Handler handler, List<Handler> list, Class<?> byClass)
    {
        if (handler==null)
            return;

        if (byClass==null || byClass.isAssignableFrom(handler.getClass()))
            list.add(handler);

        if (handler instanceof AbstractHandlerContainer)
            ((AbstractHandlerContainer)handler).expandChildren(list, byClass);
        else if (handler instanceof HandlerContainer)
        {
            HandlerContainer container = (HandlerContainer)handler;
            Handler[] handlers=byClass==null?container.getChildHandlers():container.getChildHandlersByClass(byClass);
            list.addAll(Arrays.asList(handlers));
        }
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
}
