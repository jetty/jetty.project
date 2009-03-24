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

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;

/* ------------------------------------------------------------ */
/** A <code>HandlerWrapper</code> acts as a {@link Handler} but delegates the {@link Handler#handle handle} method and
 * {@link LifeCycle life cycle} events to a delegate. This is primarily used to implement the <i>Decorator</i> pattern.
 * 
 */
public class HandlerWrapper extends AbstractHandlerContainer
{
    private Handler _handler;

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public HandlerWrapper()
    {
        super();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the handlers.
     */
    public Handler getHandler()
    {
        return _handler;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param handler Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        try
        {
            Handler old_handler = _handler;
            
            if (getServer()!=null)
                getServer().getContainer().update(this, old_handler, handler, "handler");
            
            if (handler!=null)
            {
                handler.setServer(getServer());
            }
            
            _handler = handler;
            
            if (old_handler!=null)
            {
                if (old_handler.isStarted())
                    old_handler.stop();
            }
        }
        catch(Exception e)
        {
            IllegalStateException ise= new IllegalStateException();
            ise.initCause(e);
            throw ise;
        }
    }

    /* ------------------------------------------------------------ */
    /** Add a handler.
     * This implementation of addHandler calls setHandler with the 
     * passed handler.  If this HandlerWrapper had a previous wrapped
     * handler, then it is passed to a call to addHandler on the passed
     * handler.  Thus this call can add a handler in a chain of 
     * wrapped handlers.
     * 
     * @param handler
     */
    public void addHandler(Handler handler)
    {
        Handler old = getHandler();
        if (old!=null && !(handler instanceof HandlerContainer))
            throw new IllegalArgumentException("Cannot add");
        setHandler(handler);
        if (old!=null)
            ((HandlerContainer)handler).addHandler(old);
    }
    
    
    public void removeHandler (Handler handler)
    {
        Handler old = getHandler();
        if (old!=null && (old instanceof HandlerContainer))
            ((HandlerContainer)old).removeHandler(handler);
        else if (old!=null && handler==old)
            setHandler(null);
        else
            throw new IllegalStateException("Cannot remove");
    }
    
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    protected void doStart() throws Exception
    {
        if (_handler!=null)
            _handler.start();
        super.doStart();
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_handler!=null)
            _handler.stop();
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.server.EventHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_handler!=null && isStarted())
        {
            _handler.handle(target,request, response);
        }
    }
    

    /* ------------------------------------------------------------ */
    public void setServer(Server server)
    {
        Server old_server=getServer();
        
        super.setServer(server);
        
        Handler h=getHandler();
        if (h!=null)
            h.setServer(server);
        
        if (server!=null && server!=old_server)
            server.getContainer().update(this, null,_handler, "handler");
    }
    

    /* ------------------------------------------------------------ */
    protected Object expandChildren(Object list, Class byClass)
    {
        return expandHandler(_handler,list,byClass);
    }

   
}
