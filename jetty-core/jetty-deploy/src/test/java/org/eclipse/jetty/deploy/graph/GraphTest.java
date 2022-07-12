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

package org.eclipse.jetty.deploy.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        Route path = new Route();

        assertEquals(0, path.nodes());
        assertEquals(null, path.firstNode());
        assertEquals(null, path.lastNode());

        path.add(new Edge(nodeA, nodeB));
        assertEquals(2, path.nodes());
        assertEquals(nodeA, path.firstNode());
        assertEquals(nodeB, path.lastNode());

        path.add(new Edge(nodeB, nodeC));
        assertEquals(3, path.nodes());
        assertEquals(nodeA, path.firstNode());
        assertEquals(nodeC, path.lastNode());
    }

    @Test
    public void testPoint()
    {
        Graph graph = new Graph();
        graph.addNode(nodeA);
        assertEquals(1, graph.getNodes().size());
        assertEquals(0, graph.getEdges().size());
        Route path = graph.getPath(nodeA, nodeA);
        assertEquals(0, path.nodes());
    }

    @Test
    public void testLine()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA, nodeB));
        assertEquals(2, graph.getNodes().size());
        assertEquals(1, graph.getEdges().size());
        Route path = graph.getPath(nodeA, nodeB);
        assertEquals(2, path.nodes());
    }

    @Test
    public void testTriangleDirected()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA, nodeB));
        graph.addEdge(new Edge(nodeA, nodeC));
        graph.addEdge(new Edge(nodeB, nodeC));
        assertEquals(3, graph.getNodes().size());
        assertEquals(3, graph.getEdges().size());
        Route path = graph.getPath(nodeA, nodeB);
        assertEquals(2, path.nodes());
        path = graph.getPath(nodeA, nodeC);
        assertEquals(2, path.nodes());
        path = graph.getPath(nodeB, nodeC);
        assertEquals(2, path.nodes());
    }

    @Test
    public void testSquareDirected()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA, nodeB));
        graph.addEdge(new Edge(nodeB, nodeC));
        graph.addEdge(new Edge(nodeA, nodeD));
        graph.addEdge(new Edge(nodeD, nodeC));
        assertEquals(4, graph.getNodes().size());
        assertEquals(4, graph.getEdges().size());
        Route route = graph.getPath(nodeA, nodeC);
        assertEquals(3, route.nodes());

        route = graph.getPath(nodeC, nodeA);
        assertEquals(null, route);
    }

    @Test
    public void testSquareCyclic()
    {
        Graph graph = new Graph();
        graph.addEdge(new Edge(nodeA, nodeB));
        graph.addEdge(new Edge(nodeB, nodeC));
        graph.addEdge(new Edge(nodeC, nodeD));
        graph.addEdge(new Edge(nodeD, nodeA));
        assertEquals(4, graph.getNodes().size());
        assertEquals(4, graph.getEdges().size());
        Route route = graph.getPath(nodeA, nodeB);
        assertEquals(2, route.nodes());

        route = graph.getPath(nodeA, nodeC);
        assertEquals(3, route.nodes());
        route = graph.getPath(nodeA, nodeD);
        assertEquals(4, route.nodes());

        graph.addNode(nodeE);
        route = graph.getPath(nodeA, nodeE);
        assertEquals(null, route);
    }
}
