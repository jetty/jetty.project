package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Request;

public class ScopedHandlerTest extends TestCase
{
    StringBuilder _history=new StringBuilder();

    public void testSingle()
        throws Exception
    {
        _history.setLength(0);
        TestHandler handler0 = new TestHandler("0");
        handler0.start();
        handler0.handle("target",null,null,null);
        handler0.stop();
        String history=_history.toString();
        System.err.println(history);
        assertEquals(">S0>W0<W0<S0",history);
    }

    public void testSimpleDouble()
    throws Exception
    {
        _history.setLength(0);
        TestHandler handler0 = new TestHandler("0");
        TestHandler handler1 = new TestHandler("1");
        handler0.setHandler(handler1);
        handler0.start();
        handler0.handle("target",null,null,null);
        handler0.stop();
        String history=_history.toString();
        System.err.println(history);
        assertEquals(">S0>S1>W0>W1<W1<W0<S1<S0",history);
    }

    public void testSimpleTriple()
    throws Exception
    {
        _history.setLength(0);
        TestHandler handler0 = new TestHandler("0");
        TestHandler handler1 = new TestHandler("1");
        TestHandler handler2 = new TestHandler("2");
        handler0.setHandler(handler1);
        handler1.setHandler(handler2);
        handler0.start();
        handler0.handle("target",null,null,null);
        handler0.stop();
        String history=_history.toString();
        System.err.println(history);
        assertEquals(">S0>S1>S2>W0>W1>W2<W2<W1<W0<S2<S1<S0",history);
    }

    public void testDouble()
    throws Exception
    {
        _history.setLength(0);
        TestHandler handler0 = new TestHandler("0");
        OtherHandler handlerA = new OtherHandler("A");
        TestHandler handler1 = new TestHandler("1");
        OtherHandler handlerB = new OtherHandler("B");
        handler0.setHandler(handlerA);
        handlerA.setHandler(handler1);
        handler1.setHandler(handlerB);
        handler0.start();
        handler0.handle("target",null,null,null);
        handler0.stop();
        String history=_history.toString();
        System.err.println(history);
        assertEquals(">S0>S1>W0>HA>W1>HB<HB<W1<HA<W0<S1<S0",history);
    }

    public void testTriple()
    throws Exception
    {
        _history.setLength(0);
        TestHandler handler0 = new TestHandler("0");
        OtherHandler handlerA = new OtherHandler("A");
        TestHandler handler1 = new TestHandler("1");
        OtherHandler handlerB = new OtherHandler("B");
        TestHandler handler2 = new TestHandler("2");
        OtherHandler handlerC = new OtherHandler("C");
        handler0.setHandler(handlerA);
        handlerA.setHandler(handler1);
        handler1.setHandler(handlerB);
        handlerB.setHandler(handler2);
        handler2.setHandler(handlerC);
        handler0.start();
        handler0.handle("target",null,null,null);
        handler0.stop();
        String history=_history.toString();
        System.err.println(history);
        assertEquals(">S0>S1>S2>W0>HA>W1>HB>W2>HC<HC<W2<HB<W1<HA<W0<S2<S1<S0",history);
    }
    
    private class TestHandler extends ScopedHandler
    {
        String _name;
        TestHandler(String name)
        {
            _name=name;
        }
        
        @Override
        public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">S").append(_name);
                super.nextScope(target,baseRequest,request, response);
            }
            finally
            {
                _history.append("<S").append(_name);
            }
        }
        
        @Override
        public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">W").append(_name);
                super.nextHandle(target,baseRequest,request,response);
            }
            finally
            {
                _history.append("<W").append(_name);
            }
        }

    }
    
    private class OtherHandler extends HandlerWrapper
    {
        String _name;
        
        OtherHandler(String name)
        {
            _name=name;
        }
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">H").append(_name);
                super.handle(target,baseRequest,request, response);
            }
            finally
            {
                _history.append("<H").append(_name);
            }
        }
    }
}
