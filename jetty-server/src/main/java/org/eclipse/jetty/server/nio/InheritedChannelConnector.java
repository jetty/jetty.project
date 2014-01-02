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

package org.eclipse.jetty.server.nio;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An implementation of the SelectChannelConnector which first tries to  
 * inherit from a channel provided by the system. If there is no inherited
 * channel available, or if the inherited channel provided not usable, then 
 * it will fall back upon normal ServerSocketChannel creation.
 * <p> 
 * Note that System.inheritedChannel() is only available from Java 1.5 onwards.
 * Trying to use this class under Java 1.4 will be the same as using a normal
 * SelectChannelConnector. 
 * <p> 
 * Use it with xinetd/inetd, to launch an instance of Jetty on demand. The port
 * used to access pages on the Jetty instance is the same as the port used to
 * launch Jetty. 
 * 
 * @author athena
 */
public class InheritedChannelConnector extends SelectChannelConnector
{
    private static final Logger LOG = Log.getLogger(InheritedChannelConnector.class);

    /* ------------------------------------------------------------ */
    @Override
    public void open() throws IOException
    {
        synchronized(this)
        {
            try 
            {
                Channel channel = System.inheritedChannel();
                if ( channel instanceof ServerSocketChannel )
                    _acceptChannel = (ServerSocketChannel)channel;
                else
                    LOG.warn("Unable to use System.inheritedChannel() [" +channel+ "]. Trying a new ServerSocketChannel at " + getHost() + ":" + getPort());
                
                if ( _acceptChannel != null )
                    _acceptChannel.configureBlocking(true);
            }
            catch(NoSuchMethodError e)
            {
                LOG.warn("Need at least Java 5 to use socket inherited from xinetd/inetd.");
            }

            if (_acceptChannel == null)
                super.open();
        }
    }

}
