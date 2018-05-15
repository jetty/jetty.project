//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Set;

/** 
 * Lazy Set creation.
 * <p>
 * A Set helper class that attempts to avoid unnecessary Set
 * creation.   If a method needs to create a Set to return, but it is
 * expected that this will either be empty or frequently contain a
 * single item, then using LazySet will avoid additional object
 * creations by using {@link Collections#EMPTY_SET} or
 * {@link Collections#singleton(Object)} where possible.
 * </p>
 * <p>
 * LazySet works by passing an opaque representation of the set in
 * and out of all the LazySet methods.  This opaque object is either
 * null for an empty set, an Object for a set with a single entry
 * or an {@link LinkedHashSet} for a set of items.
 * </p>
 * <strong>Usage</strong>
 * <pre>
 *   Object lazyset =null;
 *   while(loopCondition)
 *   {
 *     Object item = getItem();
 *     if (item.isToBeAdded())
 *         lazyset = LazySet.add(lazyset,item);
 *   }
 *   return LazySet.getSet(lazyset);
 * </pre>
 *
 * An LinkedHashSet of default size is used as the initial LazySet.
 *
 * @see Set
 */
@SuppressWarnings("serial")
public class LazySet
    implements Cloneable, Serializable
{
    private static final String[] __EMTPY_STRING_ARRAY = new String[0];
    
    /* ------------------------------------------------------------ */
    private LazySet()
    {}
    
    /* ------------------------------------------------------------ */
    /** Add an item to a LazySet 
     * @param set The set to add to or null if none yet created.
     * @param item The item to add.
     * @return The lazyset created or added to.
     */
    @SuppressWarnings("unchecked")
    public static Object add(Object set, Object item)
    {
        if (set==null)
        {
            if (item instanceof Set || item==null)
            {
                Set<Object> l = new LinkedHashSet<>();
                l.add(item);
                return l;
            }

            return item;
        }

        if (set instanceof Set)
        {
            ((Set<Object>)set).add(item);
            return set;
        }

        Set<Object> l=new LinkedHashSet<>();
        l.add(set);
        l.add(item);
        return l;    
    }


    /* ------------------------------------------------------------ */
    /** Add the contents of a Collection to a LazySet
     * @param set The set to add to or null if none yet created.
     * @param collection The Collection whose contents should be added.
     * @return The lazyset created or added to.
     */
    public static Object addCollection(Object set, Collection<?> collection)
    {
        Iterator<?> i=collection.iterator();
        while(i.hasNext())
            set=LazySet.add( set, i.next());
        return set;
    }
    
    /* ------------------------------------------------------------ */
    /** Add the contents of an array to a LazySet
     * @param set The set to add to or null if none yet created.
     * @param array The array whose contents should be added.
     * @return The lazyset created or added to.
     */
    public static Object addArray(Object set, Object[] array)
    {
        for(int i=0;array!=null && i<array.length;i++)
            set=LazySet.add( set, array[i]);
        return set;
    }

    /* ------------------------------------------------------------ */
    /** Ensure the capacity of the underlying set.
     * @param set the set to grow
     * @param initialSize the size to grow to
     * @return the new Set with new size
     */
    public static Object ensureSize(Object set, int initialSize)
    {
        if (set==null)
            return new LinkedHashSet<>(initialSize);
        if (set instanceof LinkedHashSet)
        {
            LinkedHashSet<?> ol=(LinkedHashSet<?>)set;
            if (ol.size()>initialSize)
                return ol;
            LinkedHashSet<Object> nl = new LinkedHashSet<>(initialSize);
            nl.addAll(ol);
            return nl;
        }
        LinkedHashSet<Object> l= new LinkedHashSet<>(initialSize);
        l.add(set);
        return l;    
    }

    /* ------------------------------------------------------------ */
    public static Object remove(Object set, Object o)
    {
        if (set==null)
            return null;

        if (set instanceof Set)
        {
            Set<?> l = (Set<?>)set;
            l.remove(o);
            if (l.size()==0)
                return null;
            return set;
        }

        if (set.equals(o))
            return null;
        return set;
    }
    
    /* ------------------------------------------------------------ */
    public static Object remove(Object set, int i)
    {
        if (set==null)
            return null;

        if (set instanceof Set)
        {
            Set<?> l = (Set<?>)set;
            l.remove(i);
            if (l.size()==0)
                return null;
            return set;
        }

        if (i==0)
            return null;
        return set;
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** Get the real Set from a LazySet.
     * 
     * @param set A LazySet returned from LazySet.add(Object)
     * @return The Set of added items, which may be an EMPTY_SET
     * or a SingletonSet.
     * @param <E> the set entry type
     */
    public static<E> Set<E> getSet(Object set)
    {
        return getSet(set,false);
    }
    

    /* ------------------------------------------------------------ */
    /** Get the real Set from a LazySet.
     * 
     * @param set A LazySet returned from LazySet.add(Object) or null
     * @param nullForEmpty If true, null is returned instead of an
     * empty set.
     * @return set Set of added items, which may be null, an EMPTY_SET
     * or a ingleton.
     * @param <E> the set entry type
     */
    @SuppressWarnings("unchecked")
    public static<E> Set<E> getSet(Object set, boolean nullForEmpty)
    {
        if (set==null)
        {
            if (nullForEmpty)
                return null;
            return Collections.emptySet();
        }
        if (set instanceof Set)
            return (Set<E>)set;
        
        return (Set<E>)Collections.singleton(set);
    }
    
    /**
     * Simple utility method to test if Set has at least 1 entry.
     * 
     * @param set
     *            a LazySet, {@link Set} or {@link Object}
     * @return true if not-null and is not empty
     */
    public static boolean hasEntry(Object set)
    {
        if (set == null)
            return false;
        if (set instanceof Set)
            return !((Set<?>)set).isEmpty();
        return true;
    }
    
    /**
     * Simple utility method to test if Set is empty
     * 
     * @param set
     *            a LazySet, {@link Set} or {@link Object}
     * @return true if null or is empty
     */
    public static boolean isEmpty(Object set)
    {
        if (set == null)
            return true;
        if (set instanceof Set)
            return ((Set<?>)set).isEmpty();
        return false;
    }

    
    /* ------------------------------------------------------------ */
    public static String[] toStringArray(Object set)
    {
        if (set==null)
            return __EMTPY_STRING_ARRAY;
        
        if (set instanceof Set)
        {
            Set<?> s = (Set<?>)set;
            String[] a = new String[s.size()];
            int i = 0;
            for (Object o : s)
            {
                if (o!=null)
                    a[i]=o.toString();
                i++;
            }
            return a;
        }
        
        return new String[] {set.toString()};
    }

    /* ------------------------------------------------------------ */
    /** Convert a lazyset to an array
     * @param set The set to convert
     * @param clazz The class of the array, which may be a primitive type
     * @return array of the lazyset entries passed in
     */
    public static Object toArray(Object set,Class<?> clazz)
    {
        if (set==null)
            return Array.newInstance(clazz,0);
        
        if (set instanceof Set)
        {
            Set<?> s = (Set<?>)set;
            if (clazz.isPrimitive())
            {
                Object a = Array.newInstance(clazz,s.size());
                int i = 0;
                for (Object o : s)
                    Array.set(a,i,o);
                i++;
                return a;
            }
            return s.toArray((Object[])Array.newInstance(clazz,s.size()));
            
        }
        
        Object a = Array.newInstance(clazz,1);
        Array.set(a,0,set);
        return a;
    }

    /* ------------------------------------------------------------ */
    /**
     * The size of a lazy Set
     * @param set  A LazySet returned from LazySet.add(Object) or null
     * @return the size of the set.
     */
    public static int size(Object set)
    {
        if (set==null)
            return 0;
        if (set instanceof Set)
            return ((Set<?>)set).size();
        return 1;
    }

    
    /* ------------------------------------------------------------ */
    public static boolean contains(Object set,Object item)
    {
        if (set==null)
            return false;
        
        if (set instanceof Set)
            return ((Set<?>)set).contains(item);

        return set.equals(item);
    }
    

    /* ------------------------------------------------------------ */
    public static Object clone(Object set)
    {
        if (set==null)
            return null;
        if (set instanceof Set)
            return new LinkedHashSet<>((Set<?>)set);
        return set;
    }
    
    /* ------------------------------------------------------------ */
    public static String toString(Object set)
    {
        if (set==null)
            return "[]";
        if (set instanceof Set)
            return set.toString();
        return "["+set+"]";
    }

    /* ------------------------------------------------------------ */
    @SuppressWarnings("unchecked")
    public static<E> Iterator<E> iterator(Object set)
    {
        if (set==null)
        {
            Set<E> empty=Collections.emptySet();
            return empty.iterator();
        }
        if (set instanceof Set)
        {
            return ((Set<E>)set).iterator();
        }
        Set<E> l=getSet(set);
        return l.iterator();
    }

    
}

