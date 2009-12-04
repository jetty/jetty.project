// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.deploy.graph.Node;
import org.junit.Assert;
import org.junit.Test;

/**
 * Just an overly picky test case to validate the potential paths.
 */
public class AppLifeCycleTest
{
    private void assertNoPath(String from, String to)
    {
        assertPath(from,to,new ArrayList<String>());
    }

    private void assertPath(AppLifeCycle lifecycle, String from, String to, List<String> expected)
    {
        Node fromNode = lifecycle.getNodeByName(from);
        Node toNode = lifecycle.getNodeByName(to);
        List<Node> actual = lifecycle.findPath(fromNode,toNode);
        String msg = "LifeCycle path from " + from + " to " + to;
        Assert.assertNotNull(msg + " should never be null",actual);

        if (expected.size() != actual.size())
        {
            System.out.println();
            System.out.printf("/* from '%s' -> '%s' */%n",from,to);
            System.out.println("/* Expected Path */");
            for (String path : expected)
            {
                System.out.println(path);
            }
            System.out.println("/* Actual Path */");
            for (Node path : actual)
            {
                System.out.println(path.getName());
            }

            Assert.assertEquals(msg + " / count",expected.size(),actual.size());
        }

        for (int i = 0, n = expected.size(); i < n; i++)
        {
            Assert.assertEquals(msg + "[" + i + "]",expected.get(i),actual.get(i).getName());
        }
    }

    private void assertPath(String from, String to, List<String> expected)
    {
        AppLifeCycle lifecycle = new AppLifeCycle();
        assertPath(lifecycle,from,to,expected);
    }

    @Test
    public void testFindPath_Deployed_Deployed()
    {
        assertNoPath("deployed","deployed");
    }

    @Test
    public void testFindPath_Deployed_Started()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("deployed");
        expected.add("pre-starting");
        expected.add("starting");
        expected.add("started");
        assertPath("deployed","started",expected);
    }

    @Test
    public void testFindPath_Deployed_Undeployed()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("deployed");
        expected.add("pre-undeploying");
        expected.add("undeploying");
        expected.add("undeployed");
        assertPath("deployed","undeployed",expected);
    }

    @Test
    public void testFindPath_Started_Deployed()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("started");
        expected.add("pre-stopping");
        expected.add("stopping");
        expected.add("deployed");
        assertPath("started","deployed",expected);
    }

    @Test
    public void testFindPath_Started_Started()
    {
        assertNoPath("started","started");
    }

    @Test
    public void testFindPath_Started_Undeployed()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("started");
        expected.add("pre-stopping");
        expected.add("stopping");
        expected.add("deployed");
        expected.add("pre-undeploying");
        expected.add("undeploying");
        expected.add("undeployed");
        assertPath("started","undeployed",expected);
    }

    @Test
    public void testFindPath_Undeployed_Deployed()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("undeployed");
        expected.add("pre-deploying");
        expected.add("deploying");
        expected.add("deployed");
        assertPath("undeployed","deployed",expected);
    }

    @Test
    public void testFindPath_Undeployed_Started()
    {
        List<String> expected = new ArrayList<String>();
        expected.add("undeployed");
        expected.add("pre-deploying");
        expected.add("deploying");
        expected.add("deployed");
        expected.add("pre-starting");
        expected.add("starting");
        expected.add("started");
        assertPath("undeployed","started",expected);
    }

    @Test
    public void testFindPath_Undeployed_Uavailable()
    {
        assertNoPath("undeployed","undeployed");
    }

    /**
     * Request multiple lifecycle paths with a single lifecycle instance. Just to ensure that there is no state
     * maintained between {@link AppLifeCycle#findPath(Node, Node)} requests.
     */
    @Test
    public void testFindPathMultiple()
    {
        AppLifeCycle lifecycle = new AppLifeCycle();
        List<String> expected = new ArrayList<String>();

        lifecycle.removeEdge("deployed","pre-starting");
        lifecycle.addEdge("deployed","staging");
        lifecycle.addEdge("staging","staged");
        lifecycle.addEdge("staged","pre-starting");

        // Deployed -> Deployed
        expected.clear();
        assertPath(lifecycle,"deployed","deployed",expected);

        // Deployed -> Staged
        expected.clear();
        expected.add("deployed");
        expected.add("staging");
        expected.add("staged");
        assertPath(lifecycle,"deployed","staged",expected);

        // Staged -> Undeployed
        expected.clear();
        expected.add("staged");
        expected.add("pre-starting");
        expected.add("starting");
        expected.add("started");
        expected.add("pre-stopping");
        expected.add("stopping");
        expected.add("deployed");
        expected.add("pre-undeploying");
        expected.add("undeploying");
        expected.add("undeployed");
        assertPath(lifecycle,"staged","undeployed",expected);

        // Undeployed -> Started
        expected.clear();
        expected.add("undeployed");
        expected.add("pre-deploying");
        expected.add("deploying");
        expected.add("deployed");
        expected.add("staging");
        expected.add("staged");
        expected.add("pre-starting");
        expected.add("starting");
        expected.add("started");
        assertPath(lifecycle,"undeployed","started",expected);
    }

}
