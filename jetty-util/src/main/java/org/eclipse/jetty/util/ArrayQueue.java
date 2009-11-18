// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util;

import java.util.AbstractList;
import java.util.NoSuchElementException;
import java.util.Queue;

/* ------------------------------------------------------------ */
/** Queue backed by circular array.
 * 
 * This partial Queue implementation (also with {@link #pop()} for stack operation)
 * is backed by a growable circular array.
 * 
 * 
 *
 * @param <E>
 */
public class ArrayQueue<E> extends AbstractList<E> implements Queue<E>
{
    public final int DEFAULT_CAPACITY=64;
    public final int DEFAULT_GROWTH=32;
    protected Object _lock=this;
    protected Object[] _elements;
    protected int _nextE;
    protected int _nextSlot;
    protected int _size;
    protected int _growCapacity;
  
    /* ------------------------------------------------------------ */
    public ArrayQueue()
    {
        _elements=new Object[64];
        _growCapacity=32;
    }
  
    /* ------------------------------------------------------------ */
    public ArrayQueue(int capacity)
    {
        _elements=new Object[capacity];
        _growCapacity=-1;
    }
    
    /* ------------------------------------------------------------ */
    public ArrayQueue(int initCapacity,int growBy)
    {
        _elements=new Object[initCapacity];
        _growCapacity=growBy;
    }
   
    /* ------------------------------------------------------------ */
    public ArrayQueue(int initCapacity,int growBy,Object lock)
    {
        _elements=new Object[initCapacity];
        _growCapacity=growBy;
        _lock=lock;
    }
    
    /* ------------------------------------------------------------ */
    public int getCapacity()
    {
        return _elements.length;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean add(E e)
    {
        if (!offer(e))
            throw new IllegalStateException("Full");
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean offer(E e)
    {
        synchronized(_lock)
        {
            if (_size==_elements.length && !grow())
                return false;
                
            _size++;
            _elements[_nextSlot++]=e;
            if (_nextSlot==_elements.length)
                _nextSlot=0;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
    * Add without synchronization or bounds checking
    * @see #add(Object)
    */
    public void addUnsafe(E e)
    {
        if (_size==_elements.length && !grow())
            throw new IllegalStateException("Full");
            
        _size++;
        _elements[_nextSlot++]=e;
        if (_nextSlot==_elements.length)
            _nextSlot=0;
    }
    
    /* ------------------------------------------------------------ */
    public E element()
    {
        synchronized(_lock)
        {
            if (_size==0)
                throw new NoSuchElementException();
            return (E)_elements[_nextE];
        }
    }

    /* ------------------------------------------------------------ */
    public E peek()
    {
        synchronized(_lock)
        {
            if (_size==0)
                return null;
            return (E)_elements[_nextE];
        }
    }

    /* ------------------------------------------------------------ */
    public E poll()
    {
        synchronized(_lock)
        {
            if (_size==0)
                return null;
            E e = (E)_elements[_nextE];
            _elements[_nextE]=null;
            _size--;
            if (++_nextE==_elements.length)
                _nextE=0;
            return e;
        }
    }

    /* ------------------------------------------------------------ */
    public E remove()
    {
        synchronized(_lock)
        {
            if (_size==0)
                throw new NoSuchElementException();
            E e = (E)_elements[_nextE];
            _elements[_nextE]=null;
            _size--;
            if (++_nextE==_elements.length)
                _nextE=0;
            return e;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void clear()
    {
        synchronized(_lock)
        {
            _size=0;
            _nextE=0;
            _nextSlot=0;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isEmpty()
    {
        synchronized(_lock)
        {
            return _size==0;
        }
    }


    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _size;
    }

    /* ------------------------------------------------------------ */
    @Override
    public E get(int index)
    {
        synchronized(_lock)
        {
            if (index<0 || index>=_size)
                throw new IndexOutOfBoundsException("!("+0+"<"+index+"<="+_size+")");
            int i = (_nextE+index)%_elements.length;
            return (E)_elements[i];
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get without synchronization or bounds checking.
     * @see get(int)
     */
    public E getUnsafe(int index)
    {
        int i = (_nextE+index)%_elements.length;
        return (E)_elements[i];
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public E remove(int index)
    {
        synchronized(_lock)
        {
            if (index<0 || index>=_size)
                throw new IndexOutOfBoundsException("!("+0+"<"+index+"<="+_size+")");

            int i = (_nextE+index)%_elements.length;
            E old=(E)_elements[i];
            
            if (i<_nextSlot)
            {
                // 0                         _elements.length
                //       _nextE........._nextSlot
                System.arraycopy(_elements,i+1,_elements,i,_nextSlot-i);
                _nextSlot--;
                _size--;
            }
            else
            {
                // 0                         _elements.length
                // ......_nextSlot   _nextE..........
                System.arraycopy(_elements,i+1,_elements,i,_elements.length-i-1);
                if (_nextSlot>0)
                {
                    _elements[_elements.length-1]=_elements[0];
                    System.arraycopy(_elements,1,_elements,0,_nextSlot-1);
                    _nextSlot--;
                }
                else
                    _nextSlot=_elements.length-1;

                _size--;
            }
            
            return old;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public E set(int index, E element)
    {
        synchronized(_lock)
        {
            if (index<0 || index>=_size)
                throw new IndexOutOfBoundsException("!("+0+"<"+index+"<="+_size+")");

            int i = _nextE+index;
            if (i>=_elements.length)
                i-=_elements.length;
            E old=(E)_elements[i];
            _elements[i]=element;
            return old;
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void add(int index, E element)
    {
        synchronized(_lock)
        {
            if (index<0 || index>_size)
                throw new IndexOutOfBoundsException("!("+0+"<"+index+"<="+_size+")");

            if (_size==_elements.length && !grow())
                    throw new IllegalStateException("Full");
            
            if (index==_size)
            {
                add(element);
            }
            else
            {
                int i = _nextE+index;
                if (i>=_elements.length)
                    i-=_elements.length;
                
                _size++;
                _nextSlot++;
                if (_nextSlot==_elements.length)
                    _nextSlot=0;
                
                if (i<_nextSlot)
                {
                    // 0                         _elements.length
                    //       _nextE.....i..._nextSlot
                    // 0                         _elements.length
                    // ..i..._nextSlot   _nextE..........
                    System.arraycopy(_elements,i,_elements,i+1,_nextSlot-i);
                    _elements[i]=element;
                }
                else
                {
                    // 0                         _elements.length
                    // ......_nextSlot   _nextE.....i....
                    if (_nextSlot>0)
                    {
                        System.arraycopy(_elements,0,_elements,1,_nextSlot);
                        _elements[0]=_elements[_elements.length-1];
                    }

                    System.arraycopy(_elements,i,_elements,i+1,_elements.length-i-1);
                    _elements[i]=element;
                }
            }
        }
    }

    protected boolean grow()
    {
        if (_growCapacity<=0)
            return false;

        Object[] elements=new Object[_elements.length+_growCapacity];

        int split=_elements.length-_nextE;
        if (split>0)
            System.arraycopy(_elements,_nextE,elements,0,split);
        if (_nextE!=0)
            System.arraycopy(_elements,0,elements,split,_nextSlot);

        _elements=elements;
        _nextE=0;
        _nextSlot=_size;
        return true;
    }

}
