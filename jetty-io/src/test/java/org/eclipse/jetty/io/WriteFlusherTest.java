package org.eclipse.jetty.io;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertFalse;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WriteFlusherTest
{
    ByteArrayEndPoint _endp;
    final AtomicBoolean _flushIncomplete = new AtomicBoolean(false);
    WriteFlusher _flusher;
    final String _context = new String("Context");
    
    @Before
    public void before()
    {
        _endp = new ByteArrayEndPoint(new byte[]{},10);
        _flushIncomplete.set(false);
        _flusher = new WriteFlusher(_endp)
        {            
            @Override
            protected void onIncompleteFlushed()
            {
                _flushIncomplete.set(true);
            }
        };
    }
    
    @After
    public void after()
    {
        
    }
    
    @Test
    public void testCompleteNoBlocking() throws Exception
    {
        _endp.setGrowOutput(true);
        
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
        assertEquals(_context,callback.get());
        assertEquals("How now brown cow!",_endp.takeOutputString());
    }
    
    @Test
    public void testClosedNoBlocking() throws Exception
    {
        _endp.close();
        
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("CLOSED"));
        }
        assertEquals("",_endp.takeOutputString());
    }
    

    @Test
    public void testCompleteBlocking() throws Exception
    {        
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));
        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());
        
        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br",_endp.takeOutputString());
        _flusher.completeWrite();
        assertTrue(callback.isDone());
        assertEquals(_context,callback.get());
        assertEquals("own cow!",_endp.takeOutputString());
        assertFalse(_flushIncomplete.get());
    }
    
    @Test
    public void testCloseWhileBlocking() throws Exception
    {
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());
        
        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br",_endp.takeOutputString());
        _endp.close();
        _flusher.completeWrite();
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("CLOSED"));
        }
        assertEquals("",_endp.takeOutputString());
    }

    @Test
    public void testFailWhileBlocking() throws Exception
    {
        FutureCallback<String> callback = new FutureCallback<>();
        _flusher.write(_context,callback,BufferUtil.toBuffer("How "),BufferUtil.toBuffer("now "),BufferUtil.toBuffer("brown "),BufferUtil.toBuffer("cow!"));

        assertFalse(callback.isDone());
        assertFalse(callback.isCancelled());
        
        assertTrue(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get(10,TimeUnit.MILLISECONDS));
            Assert.fail();
        }
        catch (TimeoutException to)
        {
            _flushIncomplete.set(false);
        }

        assertEquals("How now br",_endp.takeOutputString());
        _flusher.failed(new IOException("Failure"));
        _flusher.completeWrite();
        assertTrue(callback.isDone());
        assertFalse(_flushIncomplete.get());
        try
        {
            assertEquals(_context,callback.get());
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof IOException);
            Assert.assertThat(cause.getMessage(),Matchers.containsString("Failure"));
        }
        assertEquals("",_endp.takeOutputString());
    }
    
}
