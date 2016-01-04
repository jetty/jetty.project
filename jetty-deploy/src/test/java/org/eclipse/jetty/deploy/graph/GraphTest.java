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

package org.eclipse.jetty.deploy.graph;

import org.junit.Assert;
import org.junit.Test;

public class GraphTest
{
    final Node nodeA = new Node("A");
    final Node nodeB = new Node("B");
    final Node nodeC = new Node("C");
    final Node nodeD = new Node("D");
    final Node nodeE = new Node("E");


    @Test
    public void testPath()
    {

        Path path = new Path();

        Assert.assertEquals(0, path.nodes());
        Assert.assertEquals(null,path.firstNode());
        Assert.assertEquals(null,path.lastNode());

        path.add(new Edge(nodeA ,nodeB));
        Assert.assertEquals(2,path.nodes());
        Assert.assertEquals(nodeA,path.firstNode());
        Assert.assertEquals(nodeB,path.lastNode());

        path.add(new Edge(nodeB ,nodeC));
        Assert.assertEquals(3,path.nodes());
        Assert.assertEquals(nodeA,path.firstNode());
        Assert.assertEquals(nodeC,path.lastNode());
    }

    @Test
    public void testPoint()
    {
        Graph graph = new Graph();
        graph.addNode(nodeA);
        Assert.assertEquals(1,graph.getNodes().size());
        Assert.assertEquals(0,graph.getEdges().size());
        Path path = graph.getPath(nodeA,nodeA);
        Assert.assertEquals(0,path.nodes());
    }

    @Test
    public void testLine()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA,nodeB));
        Assert.assertEquals(2,graph.getNodes().size());
        Assert.assertEquals(1,graph.getEdges().size());
        Path path = graph.getPath(nodeA,nodeB);
        Assert.assertEquals(2,path.nodes());
    }

    @Test
    public void testTriangleDirected()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA,nodeB));
        graph.addEdge(new Edge(nodeA,nodeC));
        graph.addEdge(new Edge(nodeB,nodeC));
        Assert.assertEquals(3,graph.getNodes().size());
        Assert.assertEquals(3,graph.getEdges().size());
        Path path = graph.getPath(nodeA,nodeB);
        Assert.assertEquals(2,path.nodes());
        path = graph.getPath(nodeA,nodeC);
        Assert.assertEquals(2,path.nodes());
        path = graph.getPath(nodeB,nodeC);
        Assert.assertEquals(2,path.nodes());

    }

    @Test
    public void testSquareDirected()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA,nodeB));
        graph.addEdge(new Edge(nodeB,nodeC));
        graph.addEdge(new Edge(nodeA,nodeD));
        graph.addEdge(new Edge(nodeD,nodeC));
        Assert.assertEquals(4,graph.getNodes().size());
        Assert.assertEquals(4,graph.getEdges().size());
        Path path = graph.getPath(nodeA,nodeC);
        Assert.assertEquals(3,path.nodes());

        path = graph.getPath(nodeC,nodeA);
        Assert.assertEquals(null,path);

    }

    @Test
    public void testSquareCyclic()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA,nodeB));
        graph.addEdge(new Edge(nodeB,nodeC));
        graph.addEdge(new Edge(nodeC,nodeD));
        graph.addEdge(new Edge(nodeD,nodeA));
        Assert.assertEquals(4,graph.getNodes().size());
        Assert.assertEquals(4,graph.getEdges().size());
        Path path = graph.getPath(nodeA,nodeB);
        Assert.assertEquals(2,path.nodes());

        path = graph.getPath(nodeA,nodeC);
        Assert.assertEquals(3,path.nodes());
        path = graph.getPath(nodeA,nodeD);
        Assert.assertEquals(4,path.nodes());

        graph.addNode(nodeE);
        path = graph.getPath(nodeA,nodeE);
        Assert.assertEquals(null,path);
    }


}
