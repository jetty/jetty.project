//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/* ------------------------------------------------------------ */
/** Lazy List creation.
 * A List helper class that attempts to avoid unnecessary List
 * creation.   If a method needs to create a List to return, but it is
 * expected that this will either be empty or frequently contain a
 * single item, then using LazyList will avoid additional object
 * creations by using {@link Collections#EMPTY_LIST} or
 * {@link Collections#singletonList(Object)} where possible.
 * <p>
 * LazyList works by passing an opaque representation of the list in
 * and out of all the LazyList methods.  This opaque object is either
 * null for an empty list, an Object for a list with a single entry
 * or an {@link ArrayList} for a list of items.
 *
 * <p><h4>Usage</h4>
 * <pre>
 *   Object lazylist =null;
 *   while(loopCondition)
 *   {
 *     Object item = getItem();
 *     if (item.isToBeAdded())
 *         lazylist = LazyList.add(lazylist,item);
 *   }
 *   return LazyList.getList(lazylist);
 * </pre>
 *
 * An ArrayList of default size is used as the initial LazyList.
 *
 * @see java.util.List
 */
public class LazyList
    implements Cloneable, Serializable
{
    private static final String[] __EMTPY_STRING_ARRAY = new String[0];
    
    /* ------------------------------------------------------------ */
    private LazyList()
    {}
    
    /* ------------------------------------------------------------ */
    /** Add an item to a LazyList 
     * @param list The list to add to or null if none yet created.
     * @param item The item to add.
     * @return The lazylist created or added to.
     */
    @SuppressWarnings("unchecked")
    public static Object add(Object list, Object item)
    {
        if (list==null)
        {
            if (item instanceof List || item==null)
            {
                List<Object> l = new ArrayList<Object>();
                l.add(item);
                return l;
            }

            return item;
        }

        if (list instanceof List)
        {
            ((List<Object>)list).add(item);
            return list;
        }

        List<Object> l=new ArrayList<Object>();
        l.add(list);
        l.add(item);
        return l;    
    }

    /* ------------------------------------------------------------ */
    /** Add an item to a LazyList 
     * @param list The list to add to or null if none yet created.
     * @param index The index to add the item at.
     * @param item The item to add.
     * @return The lazylist created or added to.
     */
    @SuppressWarnings("unchecked")
    public static Object add(Object list, int index, Object item)
    {
        if (list==null)
        {
            if (index>0 || item instanceof List || item==null)
            {
                List<Object> l = new ArrayList<Object>();
                l.add(index,item);
                return l;
            }
            return item;
        }

        if (list instanceof List)
        {
            ((List<Object>)list).add(index,item);
            return list;
        }

        List<Object> l=new ArrayList<Object>();
        l.add(list);
        l.add(index,item);
        return l;
    }
    
    /* ------------------------------------------------------------ */
    /** Add the contents of a Collection to a LazyList
     * @param list The list to add to or null if none yet created.
     * @param collection The Collection whose contents should be added.
     * @return The lazylist created or added to.
     */
    public static Object addCollection(Object list, Collection<?> collection)
    {
        Iterator<?> i=collection.iterator();
        while(i.hasNext())
            list=LazyList.add(list,i.next());
        return list;
    }
    
    /* ------------------------------------------------------------ */
    /** Add the contents of an array to a LazyList
     * @param list The list to add to or null if none yet created.
     * @param array The array whose contents should be added.
     * @return The lazylist created or added to.
     */
    public static Object addArray(Object list, Object[] array)
    {
        for(int i=0;array!=null && i<array.length;i++)
            list=LazyList.add(list,array[i]);
        return list;
    }

    /* ------------------------------------------------------------ */
    /** Ensure the capacity of the underlying list.
     * 
     */
    public static Object ensureSize(Object list, int initialSize)
    {
        if (list==null)
            return new ArrayList<Object>(initialSize);
        if (list instanceof ArrayList)
        {
            ArrayList<?> ol=(ArrayList<?>)list;
            if (ol.size()>initialSize)
                return ol;
            ArrayList<Object> nl = new ArrayList<Object>(initialSize);
            nl.addAll(ol);
            return nl;
        }
        List<Object> l= new ArrayList<Object>(initialSize);
        l.add(list);
        return l;    
    }

    /* ------------------------------------------------------------ */
    public static Object remove(Object list, Object o)
    {
        if (list==null)
            return null;

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            l.remove(o);
            if (l.size()==0)
                return null;
            return list;
        }

        if (list.equals(o))
            return null;
        return list;
    }
    
    /* ------------------------------------------------------------ */
    public static Object remove(Object list, int i)
    {
        if (list==null)
            return null;

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            l.remove(i);
            if (l.size()==0)
                return null;
            return list;
        }

        if (i==0)
            return null;
        return list;
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** Get the real List from a LazyList.
     * 
     * @param list A LazyList returned from LazyList.add(Object)
     * @return The List of added items, which may be an EMPTY_LIST
     * or a SingletonList.
     */
    public static<E> List<E> getList(Object list)
    {
        return getList(list,false);
    }
    

    /* ------------------------------------------------------------ */
    /** Get the real List from a LazyList.
     * 
     * @param list A LazyList returned from LazyList.add(Object) or null
     * @param nullForEmpty If true, null is returned instead of an
     * empty list.
     * @return The List of added items, which may be null, an EMPTY_LIST
     * or a SingletonList.
     */
    @SuppressWarnings("unchecked")
    public static<E> List<E> getList(Object list, boolean nullForEmpty)
    {
        if (list==null)
        {
            if (nullForEmpty)
                return null;
            return Collections.emptyList();
        }
        if (list instanceof List)
            return (List<E>)list;
        
        return (List<E>)Collections.singletonList(list);
    }

    
    /* ------------------------------------------------------------ */
    public static String[] toStringArray(Object list)
    {
        if (list==null)
            return __EMTPY_STRING_ARRAY;
        
        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            String[] a = new String[l.size()];
            for (int i=l.size();i-->0;)
            {
                Object o=l.get(i);
                if (o!=null)
                    a[i]=o.toString();
            }
            return a;
        }
        
        return new String[] {list.toString()};
    }

    /* ------------------------------------------------------------ */
    /** Convert a lazylist to an array
     * @param list The list to convert
     * @param clazz The class of the array, which may be a primitive type
     * @return array of the lazylist entries passed in
     */
    public static Object toArray(Object list,Class<?> clazz)
    {
        if (list==null)
            return Array.newInstance(clazz,0);
        
        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            if (clazz.isPrimitive())
            {
                Object a = Array.newInstance(clazz,l.size());
                for (int i=0;i<l.size();i++)
                    Array.set(a,i,l.get(i));
                return a;
            }
            return l.toArray((Object[])Array.newInstance(clazz,l.size()));
            
        }
        
        Object a = Array.newInstance(clazz,1);
        Array.set(a,0,list);
        return a;
    }

    /* ------------------------------------------------------------ */
    /** The size of a lazy List 
     * @param list  A LazyList returned from LazyList.add(Object) or null
     * @return the size of the list.
     */
    public static int size(Object list)
    {
        if (list==null)
            return 0;
        if (list instanceof List)
            return ((List<?>)list).size();
        return 1;
    }
    
    /* ------------------------------------------------------------ */
    /** Get item from the list 
     * @param list  A LazyList returned from LazyList.add(Object) or null
     * @param i int index
     * @return the item from the list.
     */
    @SuppressWarnings("unchecked")
    public static <E> E get(Object list, int i)
    {
        if (list==null)
            throw new IndexOutOfBoundsException();
       
        if (list instanceof List)
            return (E)((List<?>)list).get(i);

        if (i==0)
            return (E)list;
        
        throw new IndexOutOfBoundsException();
    }
    
    /* ------------------------------------------------------------ */
    public static boolean contains(Object list,Object item)
    {
        if (list==null)
            return false;
        
        if (list instanceof List)
            return ((List<?>)list).contains(item);

        return list.equals(item);
    }
    

    /* ------------------------------------------------------------ */
    public static Object clone(Object list)
    {
        if (list==null)
            return null;
        if (list instanceof List)
            return new ArrayList<Object>((List<?>)list);
        return list;
    }
    
    /* ------------------------------------------------------------ */
    public static String toString(Object list)
    {
        if (list==null)
            return "[]";
        if (list instanceof List)
            return list.toString();
        return "["+list+"]";
    }

    /* ------------------------------------------------------------ */
    @SuppressWarnings("unchecked")
    public static<E> Iterator<E> iterator(Object list)
    {
        if (list==null)
        {
            List<E> empty=Collections.emptyList();
            return empty.iterator();
        }
        if (list instanceof List)
        {
            return ((List<E>)list).iterator();
        }
        List<E> l=getList(list);
        return l.iterator();
    }
    
    /* ------------------------------------------------------------ */
    @SuppressWarnings("unchecked")
    public static<E> ListIterator<E> listIterator(Object list)
    {
        if (list==null)
        {
            List<E> empty=Collections.emptyList();
            return empty.listIterator();
        }
        if (list instanceof List)
            return ((List<E>)list).listIterator();

        List<E> l=getList(list);
        return l.listIterator();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param array Any array of object
     * @return A new <i>modifiable</i> list initialised with the elements from <code>array</code>.
     */
    public static<E> List<E> array2List(E[] array)
    {	
        if (array==null || array.length==0)
            return new ArrayList<E>();
        return new ArrayList<E>(Arrays.asList(array));
    }

    /* ------------------------------------------------------------ */
    /** Add element to an array
     * @param array The array to add to (or null)
     * @param item The item to add
     * @param type The type of the array (in case of null array)
     * @return new array with contents of array plus item
     */
    public static<T> T[] addToArray(T[] array, T item, Class<?> type)
    {
        if (array==null)
        {
            if (type==null && item!=null)
                type= item.getClass();
            @SuppressWarnings("unchecked")
            T[] na = (T[])Array.newInstance(type, 1);
            na[0]=item;
            return na;
        }
        else
        {
            // TODO: Replace with Arrays.copyOf(T[] original, int newLength) from Java 1.6+
            Class<?> c = array.getClass().getComponentType();
            @SuppressWarnings("unchecked")
            T[] na = (T[])Array.newInstance(c, Array.getLength(array)+1);
            System.arraycopy(array, 0, na, 0, array.length);
            na[array.length]=item;
            return na;
        }
    }

    /* ------------------------------------------------------------ */
    public static<T> T[] removeFromArray(T[] array, Object item)
    {
        if (item==null || array==null)
            return array;
        for (int i=array.length;i-->0;)
        {
            if (item.equals(array[i]))
            {
                Class<?> c = array==null?item.getClass():array.getClass().getComponentType();
                @SuppressWarnings("unchecked")
                T[] na = (T[])Array.newInstance(c, Array.getLength(array)-1);
                if (i>0)
                    System.arraycopy(array, 0, na, 0, i);
                if (i+1<array.length)
                    System.arraycopy(array, i+1, na, i, array.length-(i+1));
                return na;
            }
        }
        return array;
    }
    
}

