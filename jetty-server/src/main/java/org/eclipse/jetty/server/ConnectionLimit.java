//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Connection.Listener;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A Connection Listener that limits the number of Connections.</p>
 * <p>This listener applies a limit to the number of connections, which when 
 * exceeded results in  a call to {@link AbstractConnector#setAccepting(boolean)} 
 * to prevent further connections being received.  It can be applied to an
 * entire server or to a specific connector.
 * </p>
 * @see Connection.Listener
 */
@ManagedObject
public class ConnectionLimit extends AbstractLifeCycle implements Listener
{
    private static final Logger LOG = Log.getLogger(ConnectionLimit.class);
    
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private int _connections;
    private int _maxConnections;
    private boolean _accepting = true;

    public ConnectionLimit(int maxConnections, Server server)
    {
        _maxConnections = maxConnections;
        _server = server;
    }
    
    public ConnectionLimit(int maxConnections, Connector...connectors)
    {
        _maxConnections = maxConnections;
        _server = null;
        for (Connector c: connectors)
        {
            if (c instanceof AbstractConnector)
                _connectors.add((AbstractConnector)c);
            else
                LOG.warn("Connector {} is not an AbstractConnection. Connections not limited",c);
        }
    }
    
    @ManagedAttribute("The maximum number of connections allowed")
    public synchronized int getMaxConnections()
    {
        return _maxConnections;
    }
    
    public synchronized void setMaxConnections(int max)
    {
        _maxConnections = max;
    }

    @ManagedAttribute("The current number of connections ")
    public synchronized int getConnections()
    {
        return _connections;
    }
    
    @Override
    protected synchronized void doStart() throws Exception
    {
        if (_server!=null)
        {
            for (Connector c: _server.getConnectors())
            {
                if (c instanceof AbstractConnector)
                    _connectors.add((AbstractConnector)c);
                else
                    LOG.warn("Connector {} is not an AbstractConnection. Connections not limited",c);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("ConnectionLimit {} for {}",_maxConnections,_connectors);
        
        _connections = 0;
        _accepting = true;
        
        for (AbstractConnector c : _connectors)
            c.addBean(this);
    }

    @Override
    protected synchronized void doStop() throws Exception
    {
        for (AbstractConnector c : _connectors)
            c.removeBean(this);
        _connections = 0;
        if (_server!=null)
            _connectors.clear();   
    }
    
    @Override
    public synchronized void onOpened(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {} < {} {}",_connections, _maxConnections, connection);
        if ( ++_connections >= _maxConnections && _accepting)
        {
            _accepting = false;
            LOG.info("Connection Limit({}) reached for {}",_maxConnections,_connectors);
            for (AbstractConnector c : _connectors)
                c.setAccepting(false);
        }
    }

    @Override
    public synchronized void onClosed(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClosed {} < {} {}",_connections, _maxConnections, connection);
        if ( --_connections < _maxConnections && !_accepting)
        {
            _accepting = true;
            LOG.info("Connection Limit({}) cleared for {}",_maxConnections,_connectors);
            for (AbstractConnector c : _connectors)
                c.setAccepting(true);
        }
    }

}
