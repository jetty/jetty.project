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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AbstractHandlerContainerTest
{
    public class ParentContainer extends AbstractHandlerContainer
    {
        private List<Handler> _children = new ArrayList<>();
        
        @Override
        public Handler[] getHandlers()
        {
            return _children.toArray(new Handler[] {});
        }
        
        public void addHandler(Handler h)
        {
            _children.add(h);
        }
        
        @Override
        protected void expandChildren(List<Handler> list, Class<?> byClass)
        {
            Handler[] handlers = getHandlers();
            if (handlers != null)
                for (Handler h : handlers)
                {
                    expandHandler(h, list, byClass);
                }
        }
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            //noop
        }
    }

    public class HandlerA extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            //noop
        }
    }

    public class HandlerB extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            //noop
        }
    }
    
    /**
     * This test was moved from ServletContextHandlerTest.
     * It cannot be done with a ServletContextHandler as the parent, because
     * the parent of a ServletContextHandler can only be org.eclipse.jetty.server containers,
     * whereas this test needs an org.eclipse.jetty.ee9.nested container.
     * @throws Exception
     */
    @Test
    public void testFindContainer() throws Exception
    {
        HandlerCollection collection = new HandlerCollection();
        ParentContainer parent = new ParentContainer();
        collection.addHandler(parent);
        HandlerA a = new HandlerA();
        HandlerB b = new HandlerB();
        parent.addHandler(a);
        parent.addHandler(b);
        

        assertEquals(parent, AbstractHandlerContainer.findContainerOf(collection, ParentContainer.class, a));
        assertEquals(parent, AbstractHandlerContainer.findContainerOf(collection, ParentContainer.class, b));
    }
}
