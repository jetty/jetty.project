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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Basic directed graph implementation
 */
public class Graph
{
    private Set<Node> _nodes = new HashSet<Node>();
    private Set<Edge> _edges = new HashSet<Edge>();

    public void addEdge(Edge edge)
    {
        Node fromNode = getNodeByName(edge.getFrom().getName());
        if (fromNode == null)
            addNode(fromNode = edge.getFrom());
        Node toNode = getNodeByName(edge.getTo().getName());
        if (toNode == null)
            addNode(toNode = edge.getTo());

        // replace edge with normalized edge
        if (!edge.getFrom().equals(fromNode) || !edge.getTo().equals(toNode))
            edge = new Edge(fromNode, toNode);

        this._edges.add(edge);
    }

    public void addEdge(String from, String to)
    {
        Node fromNode = getNodeByName(from);
        if (fromNode == null)
        {
            fromNode = new Node(from);
            addNode(fromNode);
        }

        Node toNode = getNodeByName(to);
        if (toNode == null)
        {
            toNode = new Node(to);
            addNode(toNode);
        }

        addEdge(fromNode, toNode);
    }

    private void addEdge(Node fromNode, Node toNode)
    {
        Edge edge = new Edge(fromNode, toNode);
        addEdge(edge);
    }

    public void addNode(Node node)
    {
        this._nodes.add(node);
    }

    /**
     * Convenience method for {@link #insertNode(Edge, Node)}
     *
     * @param edge the edge to split and insert a node into
     * @param nodeName the name of the node to insert along the edge
     */
    public void insertNode(Edge edge, String nodeName)
    {
        Node node = getNodeByName(nodeName);
        if (node == null)
        {
            node = new Node(nodeName);
        }

        insertNode(edge, node);
    }

    /**
     * Insert an arbitrary node on an existing edge.
     *
     * @param edge the edge to split and insert a node into
     * @param node the node to insert along the edge
     */
    public void insertNode(Edge edge, Node node)
    {
        // Remove existing edge
        removeEdge(edge);
        // Ensure node is added
        addNode(node);
        // Add start edge
        addEdge(edge.getFrom(), node);
        // Add second edge
        addEdge(node, edge.getTo());
    }

    /**
     * Find all edges that are connected to the specific node, both as an outgoing {@link Edge#getFrom()} or incoming
     * {@link Edge#getTo()} end point.
     *
     * @param node the node with potential end points
     * @return the set of edges connected to the node
     */
    public Set<Edge> findEdges(Node node)
    {
        Set<Edge> fromedges = new HashSet<Edge>();

        for (Edge edge : this._edges)
        {
            if (edge.getFrom().equals(node) || edge.getTo().equals(node))
            {
                fromedges.add(edge);
            }
        }

        return fromedges;
    }

    /**
     * Find all edges that are connected {@link Edge#getFrom()} the specific node.
     *
     * @param from the node with potential edges from it
     * @return the set of edges from the node
     */
    public Set<Edge> findEdgesFrom(Node from)
    {
        Set<Edge> fromedges = new HashSet<Edge>();

        for (Edge edge : this._edges)
        {
            if (edge.getFrom().equals(from))
            {
                fromedges.add(edge);
            }
        }

        return fromedges;
    }

    /**
     * Convenience method for {@link #getPath(Node, Node)}
     *
     * @param nodeNameOrigin the name of the node to the path origin.
     * @param nodeNameDest the name of the node to the path destination.
     * @return the path to take
     */
    public Route getPath(String nodeNameOrigin, String nodeNameDest)
    {
        if (nodeNameOrigin.equals(nodeNameDest))
        {
            return new Route();
        }

        Node from = getNodeByName(nodeNameOrigin);
        Node to = getNodeByName(nodeNameDest);
        return getPath(from, to);
    }

    /**
     * Using BFS (Breadth First Search) return the path from a any arbitrary node to any other.
     *
     * @param from the node from
     * @param to the node to
     * @return the path to take or null if there is no path.
     */
    public Route getPath(Node from, Node to)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean sameObject = (from == to);
        if (sameObject)
        {
            return new Route();
        }

        // Perform a Breadth First Search (BFS) of the tree.
        Route path = breadthFirst(from, to, new CopyOnWriteArrayList<Route>(), new HashSet<Edge>());
        return path;
    }

    private Route breadthFirst(Node from, Node destination, CopyOnWriteArrayList<Route> paths, Set<Edge> seen)
    {
        // Add next unseen segments to paths.
        boolean edgesAdded = false;
        if (paths.size() == 0)
            paths.add(new Route());

        for (Route path : paths)
        {
            Set<Edge> next = findEdgesFrom(path.nodes() == 0 ? from : path.lastNode());
            if (next.size() == 0)
                continue; // no new edges

            // Split path for other edges
            int splits = 0;
            for (Edge edge : next)
            {
                if (seen.contains(edge))
                    continue;
                seen.add(edge);
                Route nextRoute = (++splits == next.size()) ? path : path.forkRoute();
                // Add segment to split'd path
                nextRoute.add(edge);

                // Are we there yet?
                if (destination.equals(edge.getTo()))
                    return nextRoute;

                edgesAdded = true;

                // Add to extra paths
                if (nextRoute != path)
                    paths.add(nextRoute);
            }
        }

        if (edgesAdded)
            return breadthFirst(from, destination, paths, seen);
        return null;
    }

    public Set<Edge> getEdges()
    {
        return _edges;
    }

    /**
     * Get the Node by Name.
     *
     * @param name the name to lookup
     * @return the node if found or null if not found.
     */
    public Node getNodeByName(String name)
    {
        for (Node node : _nodes)
        {
            if (node.getName().equals(name))
            {
                return node;
            }
        }
        return null;
    }

    public Set<Node> getNodes()
    {
        return _nodes;
    }

    public void removeEdge(Edge edge)
    {
        this._edges.remove(edge);
    }

    public void removeEdge(String fromNodeName, String toNodeName)
    {
        Node fromNode = getNodeByName(fromNodeName);
        Node toNode = getNodeByName(toNodeName);
        Edge edge = new Edge(fromNode, toNode);
        removeEdge(edge);
    }

    public void removeNode(Node node)
    {
        this._nodes.remove(node);
    }

    public void setEdges(Set<Edge> edges)
    {
        this._edges = edges;
    }

    public void setNodes(Set<Node> nodes)
    {
        this._nodes = nodes;
    }
}
