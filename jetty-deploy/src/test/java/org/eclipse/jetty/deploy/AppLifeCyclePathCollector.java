//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.deploy;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.deploy.graph.Node;
import org.junit.Assert;

/**
 * Binds to all lifecycle nodes, and tracks the order of the lifecycle nodes for testing purposes.
 */
public class AppLifeCyclePathCollector implements AppLifeCycle.Binding
{
    List<Node> actualOrder = new ArrayList<Node>();

    public void clear()
    {
        actualOrder.clear();
    }

    public List<Node> getCapturedPath()
    {
        return actualOrder;
    }

    public String[] getBindingTargets()
    {
        return new String[]
        { "*" };
    }

    public void processBinding(Node node, App app) throws Exception
    {
        actualOrder.add(node);
    }

    public void assertExpected(String msg, List<String> expectedOrder)
    {
        if (expectedOrder.size() != actualOrder.size())
        {
            System.out.println("/* Expected Path */");
            for (String path : expectedOrder)
            {
                System.out.println(path);
            }
            System.out.println("/* Actual Path */");
            for (Node path : actualOrder)
            {
                System.out.println(path.getName());
            }

            Assert.assertEquals(msg + " / count",expectedOrder.size(),actualOrder.size());
        }

        for (int i = 0, n = expectedOrder.size(); i < n; i++)
        {
            Assert.assertEquals(msg + "[" + i + "]",expectedOrder.get(i),actualOrder.get(i).getName());
        }
    }
}
