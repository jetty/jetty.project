// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/* ------------------------------------------------------------ */
/** Singleton List.
 * This simple efficient implementation of a List with a single
 * element is provided for JDK 1.2 JVMs, which do not provide
 * the Collections.singletonList method.
 *
 * 
 */
public class SingletonList extends AbstractList
{
    private Object o;
    
    /* ------------------------------------------------------------ */
    private SingletonList(Object o)
    {
        this.o=o;
    }

    /* ------------------------------------------------------------ */
    public static SingletonList newSingletonList(Object o)
    {
        return new SingletonList(o);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Object get(int i)
    {
        if (i!=0)
            throw new IndexOutOfBoundsException("index "+i);
        return o;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return 1;
    }

    /* ------------------------------------------------------------ */
    @Override
    public ListIterator listIterator()
    {
        return new SIterator();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public ListIterator listIterator(int i)
    {
        return new SIterator(i);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Iterator iterator()
    {
        return new SIterator();
    }


    /* ------------------------------------------------------------ */
    private class SIterator implements ListIterator
    {
        int i;
        
        SIterator(){i=0;}
        SIterator(int i)
        {
            if (i<0||i>1)
                throw new IndexOutOfBoundsException("index "+i);
            this.i=i;
        }
        public void add(Object o){throw new UnsupportedOperationException("SingletonList.add()");}
        public boolean hasNext() {return i==0;}
        public boolean hasPrevious() {return i==1;}
        public Object next() {if (i!=0) throw new NoSuchElementException();i++;return o;}
        public int nextIndex() {return i;}
        public Object previous() {if (i!=1) throw new NoSuchElementException();i--;return o;}
        public int previousIndex() {return i-1;}
        public void remove(){throw new UnsupportedOperationException("SingletonList.remove()");}
        public void set(Object o){throw new UnsupportedOperationException("SingletonList.add()");}
    }
}
