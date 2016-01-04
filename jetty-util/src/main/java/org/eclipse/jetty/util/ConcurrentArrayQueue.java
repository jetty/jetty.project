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

package org.eclipse.jetty.util;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A concurrent, unbounded implementation of {@link Queue} that uses singly-linked array blocks
 * to store elements.
 * <p>
 * This class is a drop-in replacement for {@link ConcurrentLinkedQueue}, with similar performance
 * but producing less garbage because arrays are used to store elements rather than nodes.
 * </p>
 * <p>
 * The algorithm used is a variation of the algorithm from Gidenstam, Sundell and Tsigas
 * (http://www.adm.hb.se/~AGD/Presentations/CacheAwareQueue_OPODIS.pdf).
 * </p>
 *
 * @param <T> the Array entry type
 */
public class ConcurrentArrayQueue<T> extends AbstractQueue<T>
{
    public static final int DEFAULT_BLOCK_SIZE = 512;
    public static final Object REMOVED_ELEMENT = new Object()
    {
        @Override
        public String toString()
        {
            return "X";
        }
    };

    private static final int HEAD_OFFSET = MemoryUtils.getIntegersPerCacheLine() - 1;
    private static final int TAIL_OFFSET = MemoryUtils.getIntegersPerCacheLine()*2 -1;

    private final AtomicReferenceArray<Block<T>> _blocks = new AtomicReferenceArray<>(TAIL_OFFSET + 1);
    private final int _blockSize;

    public ConcurrentArrayQueue()
    {
        this(DEFAULT_BLOCK_SIZE);
    }

    public ConcurrentArrayQueue(int blockSize)
    {
        _blockSize = blockSize;
        Block<T> block = newBlock();
        _blocks.set(HEAD_OFFSET,block);
        _blocks.set(TAIL_OFFSET,block);
    }

    public int getBlockSize()
    {
        return _blockSize;
    }

    protected Block<T> getHeadBlock()
    {
        return _blocks.get(HEAD_OFFSET);
    }

    protected Block<T> getTailBlock()
    {
        return _blocks.get(TAIL_OFFSET);
    }

    @Override
    public boolean offer(T item)
    {
        item = Objects.requireNonNull(item);

        final Block<T> initialTailBlock = getTailBlock();
        Block<T> currentTailBlock = initialTailBlock;
        int tail = currentTailBlock.tail();
        while (true)
        {
            if (tail == getBlockSize())
            {
                Block<T> nextTailBlock = currentTailBlock.next();
                if (nextTailBlock == null)
                {
                    nextTailBlock = newBlock();
                    if (currentTailBlock.link(nextTailBlock))
                    {
                        // Linking succeeded, loop
                        currentTailBlock = nextTailBlock;
                    }
                    else
                    {
                        // Concurrent linking, use other block and loop
                        currentTailBlock = currentTailBlock.next();
                    }
                }
                else
                {
                    // Not at last block, loop
                    currentTailBlock = nextTailBlock;
                }
                tail = currentTailBlock.tail();
            }
            else
            {
                if (currentTailBlock.peek(tail) == null)
                {
                    if (currentTailBlock.store(tail, item))
                    {
                        // Item stored
                        break;
                    }
                    else
                    {
                        // Concurrent store, try next index
                        ++tail;
                    }
                }
                else
                {
                    // Not free, try next index
                    ++tail;
                }
            }
        }

        updateTailBlock(initialTailBlock, currentTailBlock);

        return true;
    }

    private void updateTailBlock(Block<T> oldTailBlock, Block<T> newTailBlock)
    {
        // Update the tail block pointer if needs to
        if (oldTailBlock != newTailBlock)
        {
            // The tail block pointer is allowed to lag behind.
            // If this update fails, it means that other threads
            // have filled this block and installed a new one.
            casTailBlock(oldTailBlock, newTailBlock);
        }
    }

    protected boolean casTailBlock(Block<T> current, Block<T> update)
    {
        return _blocks.compareAndSet(TAIL_OFFSET,current,update);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T poll()
    {
        final Block<T> initialHeadBlock = getHeadBlock();
        Block<T> currentHeadBlock = initialHeadBlock;
        int head = currentHeadBlock.head();
        T result = null;
        while (true)
        {
            if (head == getBlockSize())
            {
                Block<T> nextHeadBlock = currentHeadBlock.next();
                if (nextHeadBlock == null)
                {
                    // We could have read that the next head block was null
                    // but another thread allocated a new block and stored a
                    // new item. This thread could not detect this, but that
                    // is ok, otherwise we would not be able to exit this loop.

                    // Queue is empty
                    break;
                }
                else
                {
                    // Use next block and loop
                    currentHeadBlock = nextHeadBlock;
                    head = currentHeadBlock.head();
                }
            }
            else
            {
                Object element = currentHeadBlock.peek(head);
                if (element == REMOVED_ELEMENT)
                {
                    // Already removed, try next index
                    ++head;
                }
                else
                {
                    result = (T)element;
                    if (result != null)
                    {
                        if (currentHeadBlock.remove(head, result, true))
                        {
                            // Item removed
                            break;
                        }
                        else
                        {
                            // Concurrent remove, try next index
                            ++head;
                        }
                    }
                    else
                    {
                        // Queue is empty
                        break;
                    }
                }
            }
        }

        updateHeadBlock(initialHeadBlock, currentHeadBlock);

        return result;
    }

    private void updateHeadBlock(Block<T> oldHeadBlock, Block<T> newHeadBlock)
    {
        // Update the head block pointer if needs to
        if (oldHeadBlock != newHeadBlock)
        {
            // The head block pointer lagged behind.
            // If this update fails, it means that other threads
            // have emptied this block and pointed to a new one.
            casHeadBlock(oldHeadBlock, newHeadBlock);
        }
    }

    protected boolean casHeadBlock(Block<T> current, Block<T> update)
    {
        return _blocks.compareAndSet(HEAD_OFFSET,current,update);
    }

    @Override
    public T peek()
    {
        Block<T> currentHeadBlock = getHeadBlock();
        int head = currentHeadBlock.head();
        while (true)
        {
            if (head == getBlockSize())
            {
                Block<T> nextHeadBlock = currentHeadBlock.next();
                if (nextHeadBlock == null)
                {
                    // Queue is empty
                    return null;
                }
                else
                {
                    // Use next block and loop
                    currentHeadBlock = nextHeadBlock;
                    head = currentHeadBlock.head();
                }
            }
            else
            {
                T element = currentHeadBlock.peek(head);
                if (element == REMOVED_ELEMENT)
                {
                    // Already removed, try next index
                    ++head;
                }
                else
                {
                    return element;
                }
            }
        }
    }

    @Override
    public boolean remove(Object o)
    {
        Block<T> currentHeadBlock = getHeadBlock();
        int head = currentHeadBlock.head();
        boolean result = false;
        while (true)
        {
            if (head == getBlockSize())
            {
                Block<T> nextHeadBlock = currentHeadBlock.next();
                if (nextHeadBlock == null)
                {
                    // Not found
                    break;
                }
                else
                {
                    // Use next block and loop
                    currentHeadBlock = nextHeadBlock;
                    head = currentHeadBlock.head();
                }
            }
            else
            {
                Object element = currentHeadBlock.peek(head);
                if (element == REMOVED_ELEMENT)
                {
                    // Removed, try next index
                    ++head;
                }
                else
                {
                    if (element == null)
                    {
                        // Not found
                        break;
                    }
                    else
                    {
                        if (element.equals(o))
                        {
                            // Found
                            if (currentHeadBlock.remove(head, o, false))
                            {
                                result = true;
                                break;
                            }
                            else
                            {
                                ++head;
                            }
                        }
                        else
                        {
                            // Not the one we're looking for
                            ++head;
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public boolean removeAll(Collection<?> c)
    {
        // TODO: super invocations are based on iterator.remove(), which throws
        return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c)
    {
        // TODO: super invocations are based on iterator.remove(), which throws
        return super.retainAll(c);
    }

    @Override
    public Iterator<T> iterator()
    {
        final List<Object[]> blocks = new ArrayList<>();
        Block<T> currentHeadBlock = getHeadBlock();
        while (currentHeadBlock != null)
        {
            Object[] elements = currentHeadBlock.arrayCopy();
            blocks.add(elements);
            currentHeadBlock = currentHeadBlock.next();
        }
        return new Iterator<T>()
        {
            private int blockIndex;
            private int index;

            @Override
            public boolean hasNext()
            {
                while (true)
                {
                    if (blockIndex == blocks.size())
                        return false;

                    Object element = blocks.get(blockIndex)[index];

                    if (element == null)
                        return false;

                    if (element != REMOVED_ELEMENT)
                        return true;

                    advance();
                }
            }

            @Override
            public T next()
            {
                while (true)
                {
                    if (blockIndex == blocks.size())
                        throw new NoSuchElementException();

                    Object element = blocks.get(blockIndex)[index];

                    if (element == null)
                        throw new NoSuchElementException();

                    advance();

                    if (element != REMOVED_ELEMENT) {
                        @SuppressWarnings("unchecked")
                        T e = (T)element;
                        return e;
                    }
                }
            }

            private void advance()
            {
                if (++index == getBlockSize())
                {
                    index = 0;
                    ++blockIndex;
                }
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public int size()
    {
        Block<T> currentHeadBlock = getHeadBlock();
        int head = currentHeadBlock.head();
        int size = 0;
        while (true)
        {
            if (head == getBlockSize())
            {
                Block<T> nextHeadBlock = currentHeadBlock.next();
                if (nextHeadBlock == null)
                {
                    break;
                }
                else
                {
                    // Use next block and loop
                    currentHeadBlock = nextHeadBlock;
                    head = currentHeadBlock.head();
                }
            }
            else
            {
                Object element = currentHeadBlock.peek(head);
                if (element == REMOVED_ELEMENT)
                {
                    // Already removed, try next index
                    ++head;
                }
                else if (element != null)
                {
                    ++size;
                    ++head;
                }
                else
                {
                    break;
                }
            }
        }
        return size;
    }

    protected Block<T> newBlock()
    {
        return new Block<>(getBlockSize());
    }

    protected int getBlockCount()
    {
        int result = 0;
        Block<T> headBlock = getHeadBlock();
        while (headBlock != null)
        {
            ++result;
            headBlock = headBlock.next();
        }
        return result;
    }

    protected static final class Block<E>
    {
        private static final int headOffset = MemoryUtils.getIntegersPerCacheLine()-1;
        private static final int tailOffset = MemoryUtils.getIntegersPerCacheLine()*2-1;

        private final AtomicReferenceArray<Object> elements;
        private final AtomicReference<Block<E>> next = new AtomicReference<>();
        private final AtomicIntegerArray indexes = new AtomicIntegerArray(TAIL_OFFSET+1);

        protected Block(int blockSize)
        {
            elements = new AtomicReferenceArray<>(blockSize);
        }

        @SuppressWarnings("unchecked")
        public E peek(int index)
        {
            return (E)elements.get(index);
        }

        public boolean store(int index, E item)
        {
            boolean result = elements.compareAndSet(index, null, item);
            if (result)
                indexes.incrementAndGet(tailOffset);
            return result;
        }

        public boolean remove(int index, Object item, boolean updateHead)
        {
            boolean result = elements.compareAndSet(index, item, REMOVED_ELEMENT);
            if (result && updateHead)
                indexes.incrementAndGet(headOffset);
            return result;
        }

        public Block<E> next()
        {
            return next.get();
        }

        public boolean link(Block<E> nextBlock)
        {
            return next.compareAndSet(null, nextBlock);
        }

        public int head()
        {
            return indexes.get(headOffset);
        }

        public int tail()
        {
            return indexes.get(tailOffset);
        }

        public Object[] arrayCopy()
        {
            Object[] result = new Object[elements.length()];
            for (int i = 0; i < result.length; ++i)
                result[i] = elements.get(i);
            return result;
        }
    }
}
