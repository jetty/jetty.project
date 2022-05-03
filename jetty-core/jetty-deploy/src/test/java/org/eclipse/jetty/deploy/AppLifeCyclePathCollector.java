//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.deploy.graph.Node;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Override
    public String[] getBindingTargets()
    {
        return new String[]
            {"*"};
    }

    @Override
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

            assertEquals(expectedOrder.size(), actualOrder.size(), msg + " / count");
        }

        for (int i = 0, n = expectedOrder.size(); i < n; i++)
        {
            assertEquals(expectedOrder.get(i), actualOrder.get(i).getName(), msg + "[" + i + "]");
        }
    }
}
