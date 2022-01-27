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

package org.eclipse.jetty.util;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * Lazy List creation.
 * <p>
 * A List helper class that attempts to avoid unnecessary List
 * creation.   If a method needs to create a List to return, but it is
 * expected that this will either be empty or frequently contain a
 * single item, then using LazyList will avoid additional object
 * creations by using {@link Collections#EMPTY_LIST} or
 * {@link Collections#singletonList(Object)} where possible.
 * </p>
 * <p>
 * LazyList works by passing an opaque representation of the list in
 * and out of all the LazyList methods.  This opaque object is either
 * null for an empty list, an Object for a list with a single entry
 * or an {@link ArrayList} for a list of items.
 * </p>
 * <strong>Usage</strong>
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
@SuppressWarnings("serial")
public class LazyList
    implements Cloneable, Serializable
{
    private static final String[] __EMPTY_STRING_ARRAY = new String[0];

    private LazyList()
    {
    }

    /**
     * Add an item to a LazyList
     *
     * @param list The list to add to or null if none yet created.
     * @param item The item to add.
     * @return The lazylist created or added to.
     */
    @SuppressWarnings("unchecked")
    public static Object add(Object list, Object item)
    {
        if (list == null)
        {
            if (item instanceof List || item == null)
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

        List<Object> l = new ArrayList<Object>();
        l.add(list);
        l.add(item);
        return l;
    }

    /**
     * Add an item to a LazyList
     *
     * @param list The list to add to or null if none yet created.
     * @param index The index to add the item at.
     * @param item The item to add.
     * @return The lazylist created or added to.
     */
    @SuppressWarnings("unchecked")
    public static Object add(Object list, int index, Object item)
    {
        if (list == null)
        {
            if (index > 0 || item instanceof List || item == null)
            {
                List<Object> l = new ArrayList<Object>();
                l.add(index, item);
                return l;
            }
            return item;
        }

        if (list instanceof List)
        {
            ((List<Object>)list).add(index, item);
            return list;
        }

        List<Object> l = new ArrayList<Object>();
        l.add(list);
        l.add(index, item);
        return l;
    }

    /**
     * Add the contents of a Collection to a LazyList
     *
     * @param list The list to add to or null if none yet created.
     * @param collection The Collection whose contents should be added.
     * @return The lazylist created or added to.
     */
    public static Object addCollection(Object list, Collection<?> collection)
    {
        Iterator<?> i = collection.iterator();
        while (i.hasNext())
        {
            list = LazyList.add(list, i.next());
        }
        return list;
    }

    /**
     * Add the contents of an array to a LazyList
     *
     * @param list The list to add to or null if none yet created.
     * @param array The array whose contents should be added.
     * @return The lazylist created or added to.
     */
    public static Object addArray(Object list, Object[] array)
    {
        for (int i = 0; array != null && i < array.length; i++)
        {
            list = LazyList.add(list, array[i]);
        }
        return list;
    }

    /**
     * Ensure the capacity of the underlying list.
     *
     * @param list the list to grow
     * @param initialSize the size to grow to
     * @return the new List with new size
     */
    public static Object ensureSize(Object list, int initialSize)
    {
        if (list == null)
            return new ArrayList<Object>(initialSize);
        if (list instanceof ArrayList)
        {
            ArrayList<?> ol = (ArrayList<?>)list;
            if (ol.size() > initialSize)
                return ol;
            ArrayList<Object> nl = new ArrayList<Object>(initialSize);
            nl.addAll(ol);
            return nl;
        }
        List<Object> l = new ArrayList<Object>(initialSize);
        l.add(list);
        return l;
    }

    public static Object remove(Object list, Object o)
    {
        if (list == null)
            return null;

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            l.remove(o);
            if (l.size() == 0)
                return null;
            return list;
        }

        if (list.equals(o))
            return null;
        return list;
    }

    public static Object remove(Object list, int i)
    {
        if (list == null)
            return null;

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            l.remove(i);
            if (l.size() == 0)
                return null;
            return list;
        }

        if (i == 0)
            return null;
        return list;
    }

    /**
     * Get the real List from a LazyList.
     *
     * @param list A LazyList returned from LazyList.add(Object)
     * @param <E> the list entry type
     * @return The List of added items, which may be an EMPTY_LIST
     * or a SingletonList.
     */
    public static <E> List<E> getList(Object list)
    {
        return getList(list, false);
    }

    /**
     * Get the real List from a LazyList.
     *
     * @param list A LazyList returned from LazyList.add(Object) or null
     * @param nullForEmpty If true, null is returned instead of an
     * empty list.
     * @param <E> the list entry type
     * @return The List of added items, which may be null, an EMPTY_LIST
     * or a SingletonList.
     */
    @SuppressWarnings("unchecked")
    public static <E> List<E> getList(Object list, boolean nullForEmpty)
    {
        if (list == null)
        {
            if (nullForEmpty)
                return null;
            return Collections.emptyList();
        }
        if (list instanceof List)
            return (List<E>)list;

        return (List<E>)Collections.singletonList(list);
    }

    /**
     * Simple utility method to test if List has at least 1 entry.
     *
     * @param list a LazyList, {@link List} or {@link Object}
     * @return true if not-null and is not empty
     */
    public static boolean hasEntry(Object list)
    {
        if (list == null)
            return false;
        if (list instanceof List)
            return !((List<?>)list).isEmpty();
        return true;
    }

    /**
     * Simple utility method to test if List is empty
     *
     * @param list a LazyList, {@link List} or {@link Object}
     * @return true if null or is empty
     */
    public static boolean isEmpty(Object list)
    {
        if (list == null)
            return true;
        if (list instanceof List)
            return ((List<?>)list).isEmpty();
        return false;
    }

    public static String[] toStringArray(Object list)
    {
        if (list == null)
            return __EMPTY_STRING_ARRAY;

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            String[] a = new String[l.size()];
            for (int i = l.size(); i-- > 0; )
            {
                Object o = l.get(i);
                if (o != null)
                    a[i] = o.toString();
            }
            return a;
        }

        return new String[]{list.toString()};
    }

    /**
     * Convert a lazylist to an array
     *
     * @param list The list to convert
     * @param clazz The class of the array, which may be a primitive type
     * @return array of the lazylist entries passed in
     */
    public static Object toArray(Object list, Class<?> clazz)
    {
        if (list == null)
            return Array.newInstance(clazz, 0);

        if (list instanceof List)
        {
            List<?> l = (List<?>)list;
            if (clazz.isPrimitive())
            {
                Object a = Array.newInstance(clazz, l.size());
                for (int i = 0; i < l.size(); i++)
                {
                    Array.set(a, i, l.get(i));
                }
                return a;
            }
            return l.toArray((Object[])Array.newInstance(clazz, l.size()));
        }

        Object a = Array.newInstance(clazz, 1);
        Array.set(a, 0, list);
        return a;
    }

    /**
     * The size of a lazy List
     *
     * @param list A LazyList returned from LazyList.add(Object) or null
     * @return the size of the list.
     */
    public static int size(Object list)
    {
        if (list == null)
            return 0;
        if (list instanceof List)
            return ((List<?>)list).size();
        return 1;
    }

    /**
     * Get item from the list
     *
     * @param list A LazyList returned from LazyList.add(Object) or null
     * @param i int index
     * @param <E> the list entry type
     * @return the item from the list.
     */
    @SuppressWarnings("unchecked")
    public static <E> E get(Object list, int i)
    {
        if (list == null)
            throw new IndexOutOfBoundsException();

        if (list instanceof List)
            return (E)((List<?>)list).get(i);

        if (i == 0)
            return (E)list;

        throw new IndexOutOfBoundsException();
    }

    public static boolean contains(Object list, Object item)
    {
        if (list == null)
            return false;

        if (list instanceof List)
            return ((List<?>)list).contains(item);

        return list.equals(item);
    }

    public static Object clone(Object list)
    {
        if (list == null)
            return null;
        if (list instanceof List)
            return new ArrayList<Object>((List<?>)list);
        return list;
    }

    public static String toString(Object list)
    {
        if (list == null)
            return "[]";
        if (list instanceof List)
            return list.toString();
        return "[" + list + "]";
    }

    @SuppressWarnings("unchecked")
    public static <E> Iterator<E> iterator(Object list)
    {
        if (list == null)
        {
            List<E> empty = Collections.emptyList();
            return empty.iterator();
        }
        if (list instanceof List)
        {
            return ((List<E>)list).iterator();
        }
        List<E> l = getList(list);
        return l.iterator();
    }

    @SuppressWarnings("unchecked")
    public static <E> ListIterator<E> listIterator(Object list)
    {
        if (list == null)
        {
            List<E> empty = Collections.emptyList();
            return empty.listIterator();
        }
        if (list instanceof List)
            return ((List<E>)list).listIterator();

        List<E> l = getList(list);
        return l.listIterator();
    }
}

