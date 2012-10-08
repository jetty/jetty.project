//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FilterConnection;
import org.eclipse.jetty.util.annotation.Name;

public class FilterConnectionFactory extends AbstractConnectionFactory
{
    private final String _nextProtocol;
    private final int _outputBufferSize;
    private final CopyOnWriteArrayList<FilterConnection.Filter> _filters = new CopyOnWriteArrayList<>();
    
    public FilterConnectionFactory()
    {
        this(HttpVersion.HTTP_1_1.asString());
    }
    
    public FilterConnectionFactory(String nextProtocol)
    {
        this(nextProtocol,16*1024);
    }
    
    public FilterConnectionFactory(@Name("nextProtocol") String nextProtocol,@Name("outputBufferSize") int outputBufferSize)
    {
        super("filter-"+nextProtocol);
        _nextProtocol=nextProtocol;
        _outputBufferSize=outputBufferSize;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        FilterConnection filteredConnection = new FilterConnection(connector.getByteBufferPool(),connector.getExecutor(),endPoint,_outputBufferSize);
        
        configure(filteredConnection, connector, endPoint);
        addFilters(connector, filteredConnection);

        ConnectionFactory next = connector.getConnectionFactory(_nextProtocol);
        EndPoint filteredEndPoint = filteredConnection.getFilterEndPoint(); 
            
        Connection connection = next.newConnection(connector, filteredEndPoint);
        filteredEndPoint.setConnection(connection);

        return filteredConnection;
    }

    protected void addFilters(Connector connector, FilterConnection filteredConnection)
    {        
        for (FilterConnection.Filter filter : _filters)
            filteredConnection.addFilter(filter);
    }

    public void addFilter(FilterConnection.Filter filter)
    {
        addBean(filter);
        _filters.add(filter);
    }
    
    public boolean removeFilter(FilterConnection.Filter filter)
    {
        removeBean(filter);
        return _filters.remove(filter);
    }
}


