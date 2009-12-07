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
package org.eclipse.jetty.deploy.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * Basic directed graph implementation
 */
public class Graph
{
    private class EdgeSearch
    {
        private Set<Edge> seenEdges = new HashSet<Edge>();
        private List<NodePath> paths = new ArrayList<NodePath>();

        public EdgeSearch(Node from)
        {
            NodePath path = new NodePath();
            path.add(from);
            paths.add(path);
        }

        public void breadthFirst(Node destination)
        {
            // Test existing edge endpoints
            if (hasReachedDestination(destination))
            {
                // Found our destination!

                // Now remove the other paths that do not end at the destination
                ListIterator<NodePath> pathiter = paths.listIterator();
                while (pathiter.hasNext())
                {
                    NodePath path = pathiter.next();
                    if (path.lastNode() != destination)
                    {
                        pathiter.remove();
                    }
                }
                return;
            }

            List<NodePath> extrapaths = null;

            // Add next unseen segments to paths.
            boolean pathsAdded = false;

            for (NodePath path : paths)
            {
                List<Edge> next = nextUnseenEdges(path);
                if (next.size() == 0)
                {
                    continue; // no new edges
                }

                pathsAdded = true;

                // More than 1 path out? Split it.
                if (next.size() > 1)
                {
                    if (extrapaths == null)
                    {
                        extrapaths = new ArrayList<NodePath>();
                    }

                    // Split path for other edges
                    for (int i = 1, n = next.size(); i < n; i++)
                    {
                        NodePath split = path.forkPath();
                        // Add segment to split'd path
                        split.add(next.get(i).getTo());

                        // Add to extra paths
                        extrapaths.add(split);
                    }
                }

                // Add edge to current path
                Edge edge = next.get(0);
                path.add(edge.getTo());

                // Mark all edges as seen
                for (Edge e : next)
                {
                    seenEdges.add(e);
                }
            }

            // Do we have any extra paths?
            if (extrapaths != null)
            {
                paths.addAll(extrapaths);
            }

            if (pathsAdded)
            {
                // recurse
                breadthFirst(destination);
            }
        }

        public NodePath getShortestPath()
        {
            NodePath shortest = null;
            int shortestlen = Integer.MAX_VALUE;

            for (NodePath path : paths)
            {
                if (shortest == null)
                {
                    shortest = path;
                    continue;
                }

                int len = path.length();

                if (len < shortestlen)
                {
                    shortest = path;
                    shortestlen = len;
                }
            }

            return shortest;
        }

        private boolean hasReachedDestination(Node destination)
        {
            for (NodePath path : paths)
            {
                if (path.lastNode() == destination)
                {
                    return true;
                }
            }
            return false;
        }

        private List<Edge> nextUnseenEdges(NodePath path)
        {
            List<Edge> next = new ArrayList<Edge>();

            for (Edge edge : findEdgesFrom(path.lastNode()))
            {
                if (seenEdges.contains(edge) == false)
                {
                    next.add(edge);
                }
            }

            return next;
        }
    }

    private Set<Node> nodes = new HashSet<Node>();
    private Set<Edge> edges = new HashSet<Edge>();

    public void addEdge(Edge edge)
    {
        this.edges.add(edge);
    }

    public void addEdge(String from, String to)
    {
        Node fromNode = null;
        Node toNode = null;

        try
        {
            fromNode = getNodeByName(from);
        }
        catch (NodeNotFoundException e)
        {
            fromNode = new Node(from);
            addNode(fromNode);
        }

        try
        {
            toNode = getNodeByName(to);
        }
        catch (NodeNotFoundException e)
        {
            toNode = new Node(to);
            addNode(toNode);
        }

        addEdge(fromNode,toNode);
    }

    private void addEdge(Node fromNode, Node toNode)
    {
        Edge edge = new Edge(fromNode,toNode);
        addEdge(edge);
    }

    public void addNode(Node node)
    {
        this.nodes.add(node);
    }

    public void writeGraph(GraphOutput output) throws IOException
    {
        output.write(this);
    }

    /**
     * Convenience method for {@link #insertNode(Edge, Node)}
     * 
     * @param edge
     *            the edge to split and insert a node into
     * @param nodeName
     *            the name of the node to insert along the edge
     */
    public void insertNode(Edge edge, String nodeName)
    {
        Node node;

        try
        {
            node = getNodeByName(nodeName);
        }
        catch (NodeNotFoundException e)
        {
            node = new Node(nodeName);
        }

        insertNode(edge,node);
    }

    /**
     * Insert an arbitrary node on an existing edge.
     * 
     * @param edge
     *            the edge to split and insert a node into
     * @param node
     *            the node to insert along the edge
     */
    public void insertNode(Edge edge, Node node)
    {
        // Remove existing edge
        removeEdge(edge);
        // Ensure node is added
        addNode(node);
        // Add start edge
        addEdge(edge.getFrom(),node);
        // Add second edge
        addEdge(node,edge.getTo());
    }

    /**
     * Find all edges that are connected to the specific node, both as an outgoing {@link Edge#getFrom()} or incoming
     * {@link Edge#getTo()} end point.
     * 
     * @param node
     *            the node with potential end points
     * @return the set of edges connected to the node
     */
    public Set<Edge> findEdges(Node node)
    {
        Set<Edge> fromedges = new HashSet<Edge>();

        for (Edge edge : this.edges)
        {
            if ((edge.getFrom() == node) || (edge.getTo() == node))
            {
                fromedges.add(edge);
            }
        }

        return fromedges;
    }

    /**
     * Find all edges that are connected {@link Edge#getFrom()} the specific node.
     * 
     * @param node
     *            the node with potential edges from it
     * @return the set of edges from the node
     */
    public Set<Edge> findEdgesFrom(Node from)
    {
        Set<Edge> fromedges = new HashSet<Edge>();

        for (Edge edge : this.edges)
        {
            if (edge.getFrom() == from)
            {
                fromedges.add(edge);
            }
        }

        return fromedges;
    }

    /**
     * Convenience method for {@link #getPath(Node, Node)}
     * 
     * @param nodeNameOrigin
     *            the name of the node to the path origin.
     * @param nodeNameDest
     *            the name of the node to the path destination.
     * @return the path to take
     */
    public NodePath getPath(String nodeNameOrigin, String nodeNameDest)
    {
        if (nodeNameOrigin.equals(nodeNameDest))
        {
            return new NodePath();
        }

        Node from = getNodeByName(nodeNameOrigin);
        Node to = getNodeByName(nodeNameDest);
        return getPath(from,to);
    }

    /**
     * Using BFS (Breadth First Search) return the path from a any arbitrary node to any other.
     * 
     * @param from
     *            the node from
     * @param to
     *            the node to
     * @return the path to take
     */
    public NodePath getPath(Node from, Node to)
    {
        if (from == to)
        {
            return new NodePath();
        }

        // Perform a Breadth First Search (BFS) of the tree.
        EdgeSearch search = new EdgeSearch(from);
        search.breadthFirst(to);

        return search.getShortestPath();
    }

    public Set<Edge> getEdges()
    {
        return edges;
    }

    /**
     * Get the Node by Name.
     * 
     * @param name
     *            the name to lookup
     * @return the node if found
     * @throws NodeNotFoundException
     *             if node not found
     */
    public Node getNodeByName(String name)
    {
        for (Node node : nodes)
        {
            if (node.getName().equals(name))
            {
                return node;
            }
        }

        throw new NodeNotFoundException("Unable to find node: " + name);
    }

    public Set<Node> getNodes()
    {
        return nodes;
    }

    public void removeEdge(Edge edge)
    {
        this.edges.remove(edge);
    }

    public void removeEdge(String fromNodeName, String toNodeName)
    {
        Node fromNode = getNodeByName(fromNodeName);
        Node toNode = getNodeByName(toNodeName);
        Edge edge = new Edge(fromNode,toNode);
        removeEdge(edge);
    }

    public void removeNode(Node node)
    {
        this.nodes.remove(node);
    }

    public void setEdges(Set<Edge> edges)
    {
        this.edges = edges;
    }

    public void setNodes(Set<Node> nodes)
    {
        this.nodes = nodes;
    }
}
