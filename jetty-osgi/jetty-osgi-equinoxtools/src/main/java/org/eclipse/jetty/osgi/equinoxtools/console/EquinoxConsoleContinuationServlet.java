// ========================================================================
// Copyright (c) 2006-2011 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.osgi.equinoxtools.console;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.osgi.equinoxtools.WebEquinoxToolsActivator;
import org.eclipse.jetty.osgi.equinoxtools.console.WebConsoleWriterOutputStream.OnFlushListener;
import org.eclipse.osgi.framework.console.ConsoleSession;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Async servlet with jetty continuations to interact with the equinox console.
 * Ported from jetty's example 'ChatServlet'
 */
public class EquinoxConsoleContinuationServlet extends HttpServlet implements OnFlushListener
{

    private static final long serialVersionUID = 1L;
    private Map<String,ConsoleUser> _consoleUsers = new HashMap<String, ConsoleUser>();
    private WebConsoleSession _consoleSession;
    private EquinoxChattingSupport _support;
    
    /**
     * @param consoleSession
     */
    public EquinoxConsoleContinuationServlet()
    {
        
    }
    /**
     * @param consoleSession
     */
    public EquinoxConsoleContinuationServlet(WebConsoleSession consoleSession, EquinoxChattingSupport support)
    {
        _consoleSession = consoleSession;
        _support = support;
    }
    @Override
    public void init() throws ServletException
    {
        if (_consoleSession == null)
        {
            _consoleSession = new WebConsoleSession();
            WebEquinoxToolsActivator.getContext().registerService(ConsoleSession.class.getName(), _consoleSession, null);
        }
        if (_support == null)
        {
            _support = new EquinoxChattingSupport(_consoleSession);
        }
        _consoleSession.addOnFlushListener(this);
    }
    @Override
    public void destroy()
    {
        _consoleSession.removeOnFlushListener(this);
    }

    // Serve the HTML with embedded CSS and Javascript.
    // This should be static content and should use real JS libraries.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getParameter("action")!=null)
            doPost(request,response);
        else
            response.sendRedirect("index.html");
    }

    // Handle Ajax calls from browser
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {   
        // Ajax calls are form encoded
        String action = request.getParameter("action");
        String message = request.getParameter("message");
        String username = request.getParameter("user");

        if (action.equals("join"))
            join(request,response,username);
        else if (action.equals("poll"))
            poll(request,response,username);
        else if (action.equals("chat"))
            chat(request,response,username,message);
    }

    private synchronized void join(HttpServletRequest request,HttpServletResponse response,String username)
    throws IOException
    {
        ConsoleUser member = new ConsoleUser(username);
        _consoleUsers.put(username,member); 
        response.setContentType("text/json;charset=utf-8");
        PrintWriter out=response.getWriter();
        out.print("{action:\"join\"}");
    }

    private synchronized void poll(HttpServletRequest request,HttpServletResponse response,String username)
    throws IOException
    {
        ConsoleUser member = _consoleUsers.get(username);
        if (member==null)
        {
            response.sendError(503);
            return;
        }

        synchronized(member)
        {
            if (member.getMessageQueue().size()>0)
            {
                // Send one chat message
                response.setContentType("text/json;charset=utf-8");
                StringBuilder buf=new StringBuilder();

                buf.append("{\"action\":\"poll\",");
                buf.append("\"from\":\"");
                buf.append(member.getMessageQueue().poll());
                buf.append("\",");

                String message = member.getMessageQueue().poll();
                int quote=message.indexOf('"');
                while (quote>=0)
                {
                    message=message.substring(0,quote)+'\\'+message.substring(quote);
                    quote=message.indexOf('"',quote+2);
                }
                buf.append("\"chat\":\"");
                buf.append(message);
                buf.append("\"}");
                byte[] bytes = buf.toString().getBytes("utf-8");
                response.setContentLength(bytes.length);
                response.getOutputStream().write(bytes);
            }
            else 
            {
                Continuation continuation = ContinuationSupport.getContinuation(request);
                if (continuation.isInitial()) 
                {
                    // No chat in queue, so suspend and wait for timeout or chat
                    continuation.setTimeout(20000);
                    continuation.suspend();
                    member.setContinuation(continuation);
                }
                else
                {
                    // Timeout so send empty response
                    response.setContentType("text/json;charset=utf-8");
                    PrintWriter out=response.getWriter();
                    out.print("{action:\"poll\"}");
                }
            }
        }
    }

    private synchronized void chat(HttpServletRequest request,HttpServletResponse response,String username,String message)
    throws IOException
    {
        if (!message.endsWith("has joined!"))
        {
            _consoleSession.processCommand(message, false);
        }
        // Post chat to all members
        onFlush();

        response.setContentType("text/json;charset=utf-8");
        PrintWriter out=response.getWriter();
        out.print("{action:\"chat\"}");  
    }

    /**
     * Called right after the flush method on the output stream has been executed.
     */
    public void onFlush()
    {
        Queue<String> pendingConsoleOutputMessages = _support.processConsoleOutput(true, this);
        for (ConsoleUser m:_consoleUsers.values())
        {
            synchronized (m)
            {
//                m.getMessageQueue().add("osgi>"); // from
//                m.getMessageQueue().add("something was printed");  // chat
                m.getMessageQueue().addAll(pendingConsoleOutputMessages);
                
                // wakeup member if polling
                if (m.getContinuation()!=null)
                {
                    m.getContinuation().resume();
                    m.setContinuation(null);
                }
            }
        }
    }
    
    class ConsoleUser
    {
        private String _name;
        private Continuation _continuation;
        private Queue<String> _queue = new LinkedList<String>();
        
        public ConsoleUser(String name)
        {
            _name = name;
        }
        
        public String getName() 
        {
            return _name;
        }
        
        public void setContinuation(Continuation continuation)
        {
            _continuation = continuation;
        }
        
        public Continuation getContinuation()
        {
            return _continuation;
        }
        public Queue<String> getMessageQueue()
        {
            return _queue;
        }
        
    }
}
