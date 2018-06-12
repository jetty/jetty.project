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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;


public class MultiExceptionTest
{
    @Test
    public void testEmpty() throws Exception
    {
        MultiException me = new MultiException();

        assertEquals(0,me.size());
        me.ifExceptionThrow();
        me.ifExceptionThrowMulti();
        me.ifExceptionThrowRuntime();
        me.ifExceptionThrowSuppressed();

        assertEquals("Stack trace should not be filled out", 0, me.getStackTrace().length);
    }

    @Test
    public void testOne() throws Exception
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        me.add(io);

        assertEquals(1,me.size());

        try
        {
            me.ifExceptionThrow();
            assertTrue(false);
        }
        catch(IOException e)
        {
            assertTrue(e==io);
        }

        try
        {
            me.ifExceptionThrowMulti();
            assertTrue(false);
        }
        catch(MultiException e)
        {
            assertTrue(e instanceof MultiException);
        }

        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(e.getCause()==io);
        }
        
        try
        {
            me.ifExceptionThrowSuppressed();
            assertTrue(false);
        }
        catch(IOException e)
        {
            assertTrue(e==io);
        }

        me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        me.add(run);

        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(run==e);
        }
        
        assertEquals("Stack trace should not be filled out", 0, me.getStackTrace().length);
    }

    private MultiException multiExceptionWithIoRt() {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);
        assertEquals(2,me.size());
        
        assertEquals("Stack trace should not be filled out", 0, me.getStackTrace().length);
        return me;
    }
    
    private MultiException multiExceptionWithRtIo() {
        MultiException me = new MultiException();
        RuntimeException run = new RuntimeException("one");
        IOException io = new IOException("two");
        me.add(run);
        me.add(io);
        assertEquals(2,me.size());
        
        assertEquals("Stack trace should not be filled out", 0, me.getStackTrace().length);
        return me;
    }
    
    @Test
    public void testTwo() throws Exception
    {
        MultiException me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrow();
            assertTrue(false);
        }
        catch(MultiException e)
        {
            assertTrue(e instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrowMulti();
            assertTrue(false);
        }
        catch(MultiException e)
        {
            assertTrue(e instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithIoRt();
        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(e.getCause() instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }

        me = multiExceptionWithRtIo();
        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(e.getCause() instanceof MultiException);
            assertTrue(e.getStackTrace().length > 0);
        }
        
        me = multiExceptionWithRtIo();
        try
        {
            me.ifExceptionThrowSuppressed();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertNull(e.getCause());
            assertEquals(1,e.getSuppressed().length,1);
            assertEquals(IOException.class,e.getSuppressed()[0].getClass());
        }
    }
    
    @Test
    public void testCause() throws Exception
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("two");
        me.add(io);
        me.add(run);

        
        try {
            me.ifExceptionThrow();
        } catch (MultiException e) {
            assertEquals(io,e.getCause());
            assertEquals(2,e.size());
        }
        
    }
}
