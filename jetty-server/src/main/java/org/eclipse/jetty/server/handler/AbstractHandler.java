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
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/** AbstractHandler.
 * 
 *
 */
public abstract class AbstractHandler extends AbstractLifeCycle implements Handler
{
    private Server _server;
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public AbstractHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.LifeCycle#start()
     */
    @Override
    protected void doStart() throws Exception
    {
        Log.debug("starting {}",this);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.LifeCycle#stop()
     */
    @Override
    protected void doStop() throws Exception
    {
        Log.debug("stopping {}",this);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        StringBuilder b=new StringBuilder();
        String s=super.toString();
        b.append(s,s.lastIndexOf('.')+1,s.length());
        ContextHandler.Context ctx = ContextHandler.getCurrentContext();
        if (ctx!=null && ctx.getContextPath()!=null && !(this instanceof ContextHandler))
        {
            b.append('@');
            b.append(ctx.getContextPath());
            
        }
        return b.toString();
    }

    /* ------------------------------------------------------------ */
    public void setServer(Server server)
    {
        Server old_server=_server;
        if (old_server!=null && old_server!=server)
            old_server.getContainer().removeBean(this);
        _server=server;
        if (_server!=null && _server!=old_server)
            _server.getContainer().addBean(this);
    }

    /* ------------------------------------------------------------ */
    public Server getServer()
    {
        return _server;
    }


    /* ------------------------------------------------------------ */
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        if (_server!=null)
            _server.getContainer().removeBean(this);
    }


    /* ------------------------------------------------------------ */
    public String dump()
    {
        StringBuilder b = new StringBuilder();
        dump(b,"");
        return b.toString();
    }    

    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append(isStarted()?" started":" STOPPED");
        b.append('\n');
    }

}
