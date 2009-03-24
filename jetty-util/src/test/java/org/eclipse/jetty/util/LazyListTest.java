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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import junit.framework.TestCase;

/**
 * 
 *
 */
public class LazyListTest extends TestCase
{

    /**
     * Constructor for LazyListTest.
     * @param arg0
     */
    public LazyListTest(String arg0)
    {
        super(arg0);
    }

    /*
     * Test for Object add(Object, Object)
     */
    public void testAddObjectObject()
    {
        Object list=null;
        assertEquals(0,LazyList.size(list));
        
        list=LazyList.add(list, "a");
        assertEquals(1,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        
        list=LazyList.add(list, "b");
        assertEquals(2,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        assertEquals("b",LazyList.get(list,1));

        list=null;
        list=LazyList.add(list, null);
        assertEquals(1,LazyList.size(list));
        assertEquals(null,LazyList.get(list,0));
        
        list="a";
        list=LazyList.add(list, null);
        assertEquals(2,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        assertEquals(null,LazyList.get(list,1));
        
        list=LazyList.add(list, null);
        assertEquals(3,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        assertEquals(null,LazyList.get(list,1));
        assertEquals(null,LazyList.get(list,2)); 

        list=LazyList.add(null,list);
        assertEquals(1,LazyList.size(list));
        Object l = LazyList.get(list,0);
        assertTrue(l instanceof List);
    }

    /*
     * Test for Object add(Object, int, Object)
     */
    public void testAddObjectintObject()
    {
        Object list=null;
        list=LazyList.add(list,0,"c");
        list=LazyList.add(list,0,"a");
        list=LazyList.add(list,1,"b");
        list=LazyList.add(list,3,"d");
        
        assertEquals(4,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        assertEquals("b",LazyList.get(list,1));
        assertEquals("c",LazyList.get(list,2));
        assertEquals("d",LazyList.get(list,3));
        
        list=LazyList.add(null, 0, null);
        assertTrue(list instanceof List);
        
        list=LazyList.add(null, 0, new ArrayList());
        assertTrue(list instanceof List);
    }


    public void testAddCollection()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");

        Object list=null;
        list=LazyList.addCollection(list,l);
        list=LazyList.addCollection(list,l);
        
        assertEquals(4,LazyList.size(list));
        assertEquals("a",LazyList.get(list,0));
        assertEquals("b",LazyList.get(list,1));
        assertEquals("a",LazyList.get(list,2));
        assertEquals("b",LazyList.get(list,3));
    }

    public void testEnsureSize()
    {
        assertTrue(LazyList.ensureSize(null,10)!=null);
        
        assertTrue(LazyList.ensureSize("a",10) instanceof ArrayList);

        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        assertTrue(LazyList.ensureSize(l,10)!=l);
        assertTrue(LazyList.ensureSize(l,1)==l);   
    }

    /*
     * Test for Object remove(Object, Object)
     */
    public void testRemoveObjectObject()
    {
        Object list=null;
        
        assertTrue(LazyList.remove(null,"a")==null);
        
        list=LazyList.add(list,"a");
        assertEquals("a",LazyList.remove(list,"z"));
        assertTrue(LazyList.remove(list,"a")==null);
        
        list=LazyList.add(list,"b");
        list=LazyList.remove(list,"b");
        list=LazyList.add(list,"b");
        list=LazyList.add(list,"c");
        list=LazyList.add(list,"d");
        list=LazyList.add(list,"e");
        list=LazyList.remove(list,"a");
        list=LazyList.remove(list,"d");
        list=LazyList.remove(list,"e");
        
        assertEquals(2,LazyList.size(list));
        assertEquals("b",LazyList.get(list,0));
        assertEquals("c",LazyList.get(list,1));

        list=LazyList.remove(list,"b");
        list=LazyList.remove(list,"c");
        assertEquals(null,list);
    }

    /*
     * Test for Object remove(Object, int)
     */
    public void testRemoveObjectint()
    {
        Object list=null;
        assertTrue(LazyList.remove(list,0)==null);
        
        list=LazyList.add(list,"a");
        assertEquals("a",LazyList.remove(list,1));
        assertTrue(LazyList.remove(list,0)==null);
        
        list=LazyList.add(list,"b");
        list=LazyList.remove(list,1);
        list=LazyList.add(list,"b");
        list=LazyList.add(list,"c");
        list=LazyList.add(list,"d");
        list=LazyList.add(list,"e");
        list=LazyList.remove(list,0);
        list=LazyList.remove(list,2);
        list=LazyList.remove(list,2);
        
        assertEquals(2,LazyList.size(list));
        assertEquals("b",LazyList.get(list,0));
        assertEquals("c",LazyList.get(list,1));

        list=LazyList.remove(list,0);
        list=LazyList.remove(list,0);
        assertEquals(null,list);
    }

    /*
     * Test for List getList(Object)
     */
    public void testGetListObject()
    {
        assertEquals(0,LazyList.getList(null).size());
        assertEquals(1,LazyList.getList("a").size());

        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        assertEquals(2,LazyList.getList(l).size());   
    }

    /*
     * Test for List getList(Object, boolean)
     */
    public void testGetListObjectboolean()
    {
        assertEquals(0,LazyList.getList(null,false).size());
        assertEquals(null,LazyList.getList(null,true));
    }

    public void testToStringArray()
    {
        assertEquals(0,LazyList.toStringArray(null).length);
        
        assertEquals(1,LazyList.toStringArray("a").length);
        assertEquals("a",LazyList.toStringArray("a")[0]);
        
        ArrayList l=new ArrayList();
        l.add("a");
        l.add(null);
        l.add(new Integer(2));
        String[] a=LazyList.toStringArray(l);
        
        assertEquals(3,a.length);
        assertEquals("a",a[0]);
        assertEquals(null,a[1]);
        assertEquals("2",a[2]);
        
    }

    public void testSize()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        
        assertEquals(0,LazyList.size(null));
        assertEquals(0,LazyList.size(new ArrayList()));
        assertEquals(1,LazyList.size("a"));
        assertEquals(2,LazyList.size(l));
    }

    public void testGet()
    {
        testAddObjectObject();
        
        assertEquals("a",LazyList.get("a",0));
        
        try{
            LazyList.get(null,0);
            assertTrue(false);
        }
        catch(IndexOutOfBoundsException e)
        {
            assertTrue(true);
        }
        
        try{
            LazyList.get("a",1);
            assertTrue(false);
        }
        catch(IndexOutOfBoundsException e)
        {
            assertTrue(true);
        }
    }

    public void testContains()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        
        assertFalse(LazyList.contains(null,"z"));
        assertFalse(LazyList.contains("a","z"));
        assertFalse(LazyList.contains(l,"z"));
        
        assertTrue(LazyList.contains("a","a"));
        assertTrue(LazyList.contains(l,"b"));
        
    }



    public void testIterator()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        
        assertFalse(LazyList.iterator(null).hasNext());
        
        Iterator i=LazyList.iterator("a");
        assertTrue(i.hasNext());
        assertEquals("a",i.next());
        assertFalse(i.hasNext());
        
        i=LazyList.iterator(l);
        assertTrue(i.hasNext());
        assertEquals("a",i.next());
        assertTrue(i.hasNext());
        assertEquals("b",i.next());
        assertFalse(i.hasNext());
    }

    public void testListIterator()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        
        assertFalse(LazyList.listIterator(null).hasNext());
        
        ListIterator i=LazyList.listIterator("a");
        assertTrue(i.hasNext());
        assertFalse(i.hasPrevious());
        assertEquals("a",i.next());
        assertFalse(i.hasNext());
        assertTrue(i.hasPrevious());
        assertEquals("a",i.previous());
        
        i=LazyList.listIterator(l);
        assertTrue(i.hasNext());
        assertFalse(i.hasPrevious());
        assertEquals("a",i.next());
        assertTrue(i.hasNext());
        assertTrue(i.hasPrevious());
        assertEquals("b",i.next());
        assertFalse(i.hasNext());
        assertTrue(i.hasPrevious());
        assertEquals("b",i.previous());
        assertEquals("a",i.previous());
    }
    
    public void testCloneToString()
    {
        ArrayList l=new ArrayList();
        l.add("a");
        l.add("b");
        
        assertEquals("[]",LazyList.toString(LazyList.clone(null)));
        assertEquals("[a]",LazyList.toString(LazyList.clone("a")));
        assertEquals("[a, b]",LazyList.toString(LazyList.clone(l)));
    }

}
