//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.*;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HandlerTest
{

    @Test
    public void testWrapperSetServer()
    {
        Server s=new Server();
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        a.setHandler(b);
        b.setHandler(c);
        
        a.setServer(s);
        assertThat(b.getServer(),equalTo(s));
        assertThat(c.getServer(),equalTo(s));
    }

    @Test
    public void testWrapperServerSet()
    {
        Server s=new Server();
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        a.setServer(s);
        b.setHandler(c);
        a.setHandler(b);
        
        assertThat(b.getServer(),equalTo(s));
        assertThat(c.getServer(),equalTo(s));
    }

    @Test
    public void testWrapperThisLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        
        try
        {
            a.setHandler(a);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testWrapperSimpleLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        
        a.setHandler(b);
        
        try
        {
            b.setHandler(a);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testWrapperDeepLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        
        a.setHandler(b);
        b.setHandler(c);
        
        try
        {
            c.setHandler(a);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testWrapperChainLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        
        a.setHandler(b);
        c.setHandler(a);
        
        try
        {
            b.setHandler(c);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }


    @Test
    public void testCollectionSetServer()
    {
        Server s=new Server();
        HandlerCollection a = new HandlerCollection();
        HandlerCollection b = new HandlerCollection();
        HandlerCollection b1 = new HandlerCollection();
        HandlerCollection b2 = new HandlerCollection();
        HandlerCollection c = new HandlerCollection();
        HandlerCollection c1 = new HandlerCollection();
        HandlerCollection c2 = new HandlerCollection();
        
        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1,b2});
        c.setHandlers(new Handler[]{c1,c2});
        a.setServer(s);
        
        assertThat(b.getServer(),equalTo(s));
        assertThat(c.getServer(),equalTo(s));
        assertThat(b1.getServer(),equalTo(s));
        assertThat(b2.getServer(),equalTo(s));
        assertThat(c1.getServer(),equalTo(s));
        assertThat(c2.getServer(),equalTo(s));
    }

    @Test
    public void testCollectionServerSet()
    {
        Server s=new Server();
        HandlerCollection a = new HandlerCollection();
        HandlerCollection b = new HandlerCollection();
        HandlerCollection b1 = new HandlerCollection();
        HandlerCollection b2 = new HandlerCollection();
        HandlerCollection c = new HandlerCollection();
        HandlerCollection c1 = new HandlerCollection();
        HandlerCollection c2 = new HandlerCollection();
        
        a.setServer(s);
        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1,b2});
        c.setHandlers(new Handler[]{c1,c2});
        
        assertThat(b.getServer(),equalTo(s));
        assertThat(c.getServer(),equalTo(s));
        assertThat(b1.getServer(),equalTo(s));
        assertThat(b2.getServer(),equalTo(s));
        assertThat(c1.getServer(),equalTo(s));
        assertThat(c2.getServer(),equalTo(s));
    }
    
    @Test
    public void testCollectionThisLoop()
    {
        HandlerCollection a = new HandlerCollection();
        
        try
        {
            a.addHandler(a);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testCollectionDeepLoop()
    {
        HandlerCollection a = new HandlerCollection();
        HandlerCollection b = new HandlerCollection();
        HandlerCollection b1 = new HandlerCollection();
        HandlerCollection b2 = new HandlerCollection();
        HandlerCollection c = new HandlerCollection();
        HandlerCollection c1 = new HandlerCollection();
        HandlerCollection c2 = new HandlerCollection();
        
        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1,b2});
        c.setHandlers(new Handler[]{c1,c2});

        try
        {
            b2.addHandler(a);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testCollectionChainLoop()
    {
        HandlerCollection a = new HandlerCollection();
        HandlerCollection b = new HandlerCollection();
        HandlerCollection b1 = new HandlerCollection();
        HandlerCollection b2 = new HandlerCollection();
        HandlerCollection c = new HandlerCollection();
        HandlerCollection c1 = new HandlerCollection();
        HandlerCollection c2 = new HandlerCollection();
        
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1,b2});
        c.setHandlers(new Handler[]{c1,c2});
        b2.addHandler(a);

        try
        {
            a.addHandler(b);
            fail();
        }
        catch(IllegalStateException e)
        {
            assertThat(e.getMessage(),containsString("loop"));
        }
    }
    
    @Test
    public void testInsertWrapperTail()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        
        a.insertHandler(b);
        assertThat(a.getHandler(),equalTo(b));
        assertThat(b.getHandler(),nullValue());
    }
    
    @Test
    public void testInsertWrapper()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        
        a.insertHandler(c);
        a.insertHandler(b);
        assertThat(a.getHandler(),equalTo(b));
        assertThat(b.getHandler(),equalTo(c));
        assertThat(c.getHandler(),nullValue());
    }
    
    @Test
    public void testInsertWrapperChain()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        HandlerWrapper d = new HandlerWrapper();
        
        a.insertHandler(d);
        b.insertHandler(c);
        a.insertHandler(b);
        assertThat(a.getHandler(),equalTo(b));
        assertThat(b.getHandler(),equalTo(c));
        assertThat(c.getHandler(),equalTo(d));
        assertThat(d.getHandler(),nullValue());
    }
    
    @Test
    public void testInsertWrapperBadChain()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        HandlerWrapper d = new HandlerWrapper();
        
        a.insertHandler(d);
        b.insertHandler(c);
        c.setHandler(new AbstractHandler()
        {   
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {                
            }
        });
        
        try
        {
            a.insertHandler(b);
            fail();
        }
        catch(IllegalArgumentException e)
        {
            assertThat(e.getMessage(),containsString("bad tail"));
        }
    }
}
