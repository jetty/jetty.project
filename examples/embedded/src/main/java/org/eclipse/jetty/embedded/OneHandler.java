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

package org.eclipse.jetty.embedded;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.statistic.OnOffStatistic;

public class OneHandler
{
    public static void main( String[] args ) throws Exception
    {
        Server server = new Server(8080);
        server.setHandler(new MyHandler());

        server.getConnectors()[0].addBean(new MyListener());
        
        server.start();
        server.join();
    }
    
    public static class MyHandler extends AbstractHandler
    {
        @Override
        public void handle( String target,
                            Request baseRequest,
                            HttpServletRequest request,
                            HttpServletResponse response ) throws IOException,
                                                          ServletException
        {
            System.err.println("HANDLE "+target);

            baseRequest.setHandled(true);
            response.setContentType("text/html; charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);

            PrintWriter out = response.getWriter();

            out.println("<h1>Hello</h1>");
            
            if (request.getParameter("l")!=null)
            {
                int lines = Integer.parseInt(request.getParameter("l"));
                while(lines-->0)
                    out.println(lines+ ": Now is the winter of or discontent. How now brown cow. The quick brown fox jumped over the lazy dog!");
            }
        }
    }

    
    
    private static class MyListener implements ManagedSelector.Listener, Container.InheritedListener
    {
        private final ConcurrentMap<ManagedSelector, List<Long>> selections = new ConcurrentHashMap<>();
        private final ConcurrentMap<SelectionKey, OnOffStatistic> writeblocked = new ConcurrentHashMap<>();
        long lastSelectedEnd=-1;
        long lastSelecting=-1;

        @Override
        public void onSelecting(ManagedSelector selector)
        {
            long now = System.nanoTime();
            if (lastSelectedEnd!=-1)
            {
                long sinceSelected = TimeUnit.NANOSECONDS.toMillis(now-lastSelectedEnd);
                if (sinceSelected>100)
                    System.err.printf("onSelecting: sinceSelected %d>100ms%n",sinceSelected);
            }
            lastSelectedEnd = -1;
            lastSelecting = now;
        }
        
        @Override
        public void onSelectedBegin(ManagedSelector selector)
        {
            long now = System.nanoTime();
            if (lastSelecting!=-1)
            {
                long selecting = TimeUnit.NANOSECONDS.toMillis(now-lastSelecting);
                if (selecting>1000)
                    System.err.printf("onSelected: selecting %d>1000ms%n",selecting);
            }
            lastSelecting = -1;
            ArrayList<Long> times = new ArrayList<>();
            times.add(System.nanoTime());
            selections.put(selector, times);
        }

        @Override
        public void onUpdatedKey(ManagedSelector managedSelector, SelectionKey key)
        {
            OnOffStatistic blocked = writeblocked.get(key);
            if ((key.interestOps()&SelectionKey.OP_WRITE)!=0)
            {
                if (blocked==null)
                {
                    blocked=new OnOffStatistic(true);
                    writeblocked.putIfAbsent(key,blocked);
                }
                else
                    blocked.record(true);
            }
            else if (blocked!=null && blocked.record(false))
            {
                if (blocked.getLastOn(TimeUnit.MILLISECONDS)>200)
                {
                    System.err.printf("WRITE BLOCKED>200ms: %s %s%n",blocked,key.attachment());
                }
            }
        }
        
        @Override
        public void onClosed(ManagedSelector selector, SelectionKey key)
        {
            writeblocked.remove(key);
        }

        @Override
        public void onSelectedKey(ManagedSelector selector, SelectionKey key)
        {
            selections.get(selector).add(System.nanoTime());
        }

        @Override
        public void onSelectedEnd(ManagedSelector selector)
        {
            List<Long> times = selections.remove(selector);
            if (times.size()>1)
            {
                long total = TimeUnit.NANOSECONDS.toMillis(times.get(times.size()-1) - times.get(0));
                if (total>200)
                    System.err.println("onSelectedEnd: >200ms : " + times.stream()
                    .map(time -> TimeUnit.NANOSECONDS.toMillis(time - times.get(0)))
                    .map(String::valueOf)
                    .skip(1)
                    .collect(Collectors.joining(",")));
            }
            lastSelectedEnd = System.nanoTime();
        }

        @Override
        public void beanAdded(Container parent, Object child)
        {
        }

        @Override
        public void beanRemoved(Container parent, Object child)
        {
        }
    }

}
