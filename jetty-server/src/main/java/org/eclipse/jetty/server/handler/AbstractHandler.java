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
    protected String _string;
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
    protected void doStart() throws Exception
    {
        Log.debug("starting {}",this);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.LifeCycle#stop()
     */
    protected void doStop() throws Exception
    {
        Log.debug("stopping {}",this);
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        if (_string==null)
        {
            _string=super.toString();
            _string=_string.substring(_string.lastIndexOf('.')+1);
        }
        return _string;
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

}
