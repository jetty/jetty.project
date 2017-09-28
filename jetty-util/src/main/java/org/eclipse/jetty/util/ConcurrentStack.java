//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ConcurrentStack
 *
 * Nonblocking stack using variation of Treiber's algorithm
 * that allows for reduced garbage
 */
public class ConcurrentStack<I>
{
    private final NodeStack<Holder> stack = new NodeStack<>();

    public void push(I item)
    {
        stack.push(new Holder(item));
    }

    public I pop()
    {
        Holder<I> holder = stack.pop();
        if (holder==null)
            return null;
        return holder.item;
    }

    private static class Holder<I> extends Node
    {
        final I item;

        Holder(I item)
        {
            this.item = item;
        }
    }

    public static class Node
    {
        Node next;
    }

    public static class NodeStack<N extends Node>
    {
        AtomicReference<Node> stack = new AtomicReference<Node>();

        public void push(N node)
        {
            while(true)
            {
                Node top = stack.get();
                node.next = top;
                if (stack.compareAndSet(top,node))
                    break;
            }
        }

        public N pop()
        {
            while (true)
            {
                Node top = stack.get();
                if (top==null)
                    return null;
                if (stack.compareAndSet(top,top.next))
                {
                    top.next = null;
                    return (N)top;
                }
            }
        }
    }
}
