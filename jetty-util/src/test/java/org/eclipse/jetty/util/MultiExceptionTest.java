package org.eclipse.jetty.util;

import java.io.IOException;

import junit.framework.TestCase;

public class MultiExceptionTest extends TestCase
{
    public void testEmpty() throws Exception
    {
        MultiException me = new MultiException();

        assertEquals(0,me.size());
        me.ifExceptionThrow();
        me.ifExceptionThrowMulti();
        me.ifExceptionThrowRuntime();
    }
    
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
            assertTrue(e==me);
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
    }
    
    public void testTwo() throws Exception
    {
        MultiException me = new MultiException();
        IOException io = new IOException("one");
        RuntimeException run = new RuntimeException("one");
        me.add(io);
        me.add(run);
        
        assertEquals(2,me.size());
        
        try
        {
            me.ifExceptionThrow();
            assertTrue(false);
        }
        catch(MultiException e)
        {
            assertTrue(e==me);
        }
        
        try
        {
            me.ifExceptionThrowMulti();
            assertTrue(false);
        }
        catch(MultiException e)
        {
            assertTrue(e==me);
        }
        
        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(e.getCause()==me);
        }
        
        me = new MultiException();
        me.add(run);
        me.add(run);

        try
        {
            me.ifExceptionThrowRuntime();
            assertTrue(false);
        }
        catch(RuntimeException e)
        {
            assertTrue(e.getCause()==me);
        }
    }
}
