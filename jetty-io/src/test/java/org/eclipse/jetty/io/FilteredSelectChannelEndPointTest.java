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

package org.eclipse.jetty.io;

import java.nio.channels.SocketChannel;

import org.junit.Ignore;

// TODO ignore this test as it creates too many /tmp files and is not portable and does not even check the contents!
@Ignore
public class FilteredSelectChannelEndPointTest extends SelectChannelEndPointTest
{
    public FilteredSelectChannelEndPointTest()
    {
    }

    @Override
    protected Connection newConnection(SocketChannel channel, EndPoint endpoint)
    {
        FilterConnection filter = new FilterConnection(new MappedByteBufferPool(),_threadPool,endpoint,8192);
        filter.addFilter(new FilterConnection.DumpToFileFilter());
        Connection connection= super.newConnection(null,filter.getFilterEndPoint());
        filter.getFilterEndPoint().setConnection(connection);
        return filter;
    }

    @Override
    public void testBlockedReadIdle() throws Exception
    {
        super.testBlockedReadIdle();
    }
    
    
}
