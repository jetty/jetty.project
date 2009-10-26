// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.handler;


import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.util.LazyList;


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
    public Handler getChildHandlerByClass(Class<?> byclass)
    {
        // TODO this can be more efficient?
        Object list = expandChildren(null,byclass);
        if (list==null)
            return null;
        return LazyList.get(list, 0);
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
    @Override
    protected void dump(StringBuilder b,String indent)
    {
        super.dump(b,indent);

        Handler[] handlers = getHandlers();
        if (handlers!=null)
        {   
            int last=handlers.length-1;
            for (int h=0;h<=last;h++)
            {
                b.append(indent);
                b.append(" +-");
                if (handlers[h] instanceof AbstractHandler)
                    ((AbstractHandler)handlers[h]).dump(b,indent+((h==last)?"   ":" | "));
                else
                {
                    b.append(handlers[h]);
                    b.append("\n");
                }
            }
        }
    }
}
