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

import java.util.Collection;
import java.util.Iterator;
import java.util.Stack;

public class NodePath implements Iterable<Node>
{
    private Stack<Node> path;

    public NodePath()
    {
        path = new Stack<Node>();
    }

    public void add(Node node)
    {
        path.push(node);
    }

    public NodePath forkPath()
    {
        NodePath ep = new NodePath();
        for (Node node : this)
        {
            ep.add(node);
        }
        return ep;
    }

    public Collection<Node> getCollection()
    {
        return path;
    }

    public Iterator<Node> iterator()
    {
        return path.iterator();
    }

    public Node lastNode()
    {
        return path.peek();
    }

    public int length()
    {
        return path.size();
    }

    public boolean isEmpty()
    {
        return path.isEmpty();
    }

    public Edge getEdge(int index)
    {
        if (index < 0)
        {
            throw new IndexOutOfBoundsException("Unable to use a negative index " + index);
        }

        int upperBound = path.size() - 1;

        if (index > upperBound)
        {
            throw new IndexOutOfBoundsException("Index " + index + " not within range (0 - " + upperBound + ")");
        }

        Node from = path.get(index);
        Node to = path.get(index + 1);
        return new Edge(from,to); // TODO: if edge has more details than from/to node, then this will need to be persisted within the NodePath as well.
    }

    public Node getNode(int index)
    {
        return path.get(index);
    }
}