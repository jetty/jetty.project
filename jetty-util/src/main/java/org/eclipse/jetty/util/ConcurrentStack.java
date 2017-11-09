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
 * <p>Nonblocking stack using variation of Treiber's algorithm
 * that allows for reduced garbage.</p>
 */
public class ConcurrentStack<I>
{
    private final NodeStack<Holder<I>> stack = new NodeStack<>();

    public void push(I item)
    {
        stack.push(new Holder<>(item));
    }

    public I pop()
    {
        Holder<I> holder = stack.pop();
        if (holder == null)
            return null;
        return holder.item;
    }

    public static class Node<E extends Node<E>>
    {
        E next;
    }

    private static class Holder<I> extends Node<Holder<I>>
    {
        private final I item;

        private Holder(I item)
        {
            this.item = item;
        }
    }

    public static class NodeStack<N extends Node<N>>
    {
        private final AtomicReference<N> stack = new AtomicReference<>();

        public void push(N node)
        {
            while (true)
            {
                N top = stack.get();
                node.next = top;
                if (stack.compareAndSet(top, node))
                    break;
            }
        }

        public N pop()
        {
            while (true)
            {
                N top = stack.get();
                if (top == null)
                    return null;
                if (stack.compareAndSet(top, top.next))
                {
                    top.next = null;
                    return top;
                }
            }
        }
    }
}
