//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.xml.objects;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.annotation.Name;

public class BogusServer
{
    private BogusThreadPool threadPool;
    private List<BogusConnector> connectors;

    public BogusServer()
    {
    }

    public BogusServer(@Name("threadPool") BogusThreadPool threadPool)
    {
        this.threadPool = threadPool;
    }

    public BogusThreadPool getThreadPool()
    {
        return threadPool;
    }

    public List<BogusConnector> getConnectors()
    {
        return connectors;
    }

    public void addConnector(BogusConnector connector)
    {
        if (connectors == null)
            connectors = new ArrayList<>();
        connectors.add(connector);
    }
}
