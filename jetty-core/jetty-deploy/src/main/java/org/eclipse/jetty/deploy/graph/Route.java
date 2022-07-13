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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Route
{
    private final List<Edge> _edges = new CopyOnWriteArrayList<Edge>();
    private final List<Node> _nodes = new CopyOnWriteArrayList<Node>();

    public Route()
    {
    }

    public void add(Edge edge)
    {
        _edges.add(edge);
        if (_nodes.size() == 0)
        {
            _nodes.add(edge.getFrom());
        }
        else
        {
            assert _nodes.get(_nodes.size() - 1).equals(edge.getFrom());
        }
        _nodes.add(edge.getTo());
    }

    public Route forkRoute()
    {
        Route ep = new Route();
        for (Edge edge : _edges)
        {
            ep.add(edge);
        }
        return ep;
    }

    public List<Node> getNodes()
    {
        return _nodes;
    }

    public List<Node> getEdges()
    {
        return _nodes;
    }

    public Node getNode(int index)
    {
        return _nodes.get(index);
    }

    public Node firstNode()
    {
        if (_nodes.size() == 0)
        {
            return null;
        }
        return _nodes.get(0);
    }

    public Node lastNode()
    {
        if (_nodes.size() == 0)
        {
            return null;
        }
        return _nodes.get(_nodes.size() - 1);
    }

    public int nodes()
    {
        return _nodes.size();
    }

    public int edges()
    {
        return _edges.size();
    }

    public boolean isEmpty()
    {
        return _edges.isEmpty();
    }

    public Edge firstEdge()
    {
        if (_edges.size() == 0)
        {
            return null;
        }
        return _edges.get(0);
    }

    public Edge lastEdge()
    {
        if (_edges.size() == 0)
        {
            return null;
        }
        return _edges.get(_edges.size() - 1);
    }

    public Edge getEdge(int index)
    {
        return _edges.get(index);
    }

    @Override
    public String toString()
    {
        return super.toString() + _nodes.toString();
    }
}
