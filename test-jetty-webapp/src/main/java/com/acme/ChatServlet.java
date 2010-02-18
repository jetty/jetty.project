// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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
package com.acme;

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


// Simple asynchronous Chat room.
// This does not handle duplicate usernames or multiple frames/tabs from the same browser
// Some code is duplicated for clarity.
public class ChatServlet extends HttpServlet
{
    
    // inner class to hold message queue for each chat room member
    class Member
    {
        String _name;
        Continuation _continuation;
        Queue<String> _queue = new LinkedList<String>();
    }

    Map<String,Map<String,Member>> _rooms = new HashMap<String,Map<String, Member>>();
    
    
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
        Member member = new Member();
        member._name=username;
        Map<String,Member> room=_rooms.get(request.getPathInfo());
        if (room==null)
        {
            room=new HashMap<String,Member>();
            _rooms.put(request.getPathInfo(),room);
        }
        room.put(username,member); 
        response.setContentType("text/json;charset=utf-8");
        PrintWriter out=response.getWriter();
        out.print("{action:\"join\"}");
    }

    private synchronized void poll(HttpServletRequest request,HttpServletResponse response,String username)
    throws IOException
    {
        Map<String,Member> room=_rooms.get(request.getPathInfo());
        if (room==null)
        {
            response.sendError(503);
            return;
        }
        Member member = room.get(username);
        if (member==null)
        {
            response.sendError(503);
            return;
        }

        synchronized(member)
        {
            if (member._queue.size()>0)
            {
                // Send one chat message
                response.setContentType("text/json;charset=utf-8");
                StringBuilder buf=new StringBuilder();

                buf.append("{\"action\":\"poll\",");
                buf.append("\"from\":\"");
                buf.append(member._queue.poll());
                buf.append("\",");

                String message = member._queue.poll();
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
                Continuation continuation = ContinuationSupport.getContinuation(request,response);
                if (continuation.isInitial()) 
                {
                    // No chat in queue, so suspend and wait for timeout or chat
                    continuation.suspend();
                    member._continuation=continuation;
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
        Map<String,Member> room=_rooms.get(request.getPathInfo());
        if (room!=null)
        {
            // Post chat to all members
            for (Member m:room.values())
            {
                synchronized (m)
                {
                    m._queue.add(username); // from
                    m._queue.add(message);  // chat

                    // wakeup member if polling
                    if (m._continuation!=null)
                    {
                        m._continuation.resume();
                        m._continuation=null;
                    }
                }
            }
        }

        response.setContentType("text/json;charset=utf-8");
        PrintWriter out=response.getWriter();
        out.print("{action:\"chat\"}");  
    }
    
    // Serve the HTML with embedded CSS and Javascript.
    // This should be static content and should use real JS libraries.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getParameter("action")!=null)
            doPost(request,response);
        else
            getServletContext().getNamedDispatcher("default").forward(request,response);
    }
    
}
