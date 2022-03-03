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

package org.example;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Simple asynchronous Chat room.
// This does not handle duplicate usernames or multiple frames/tabs from the same browser
// Some code is duplicated for clarity.
@SuppressWarnings("serial")
public class ChatServlet extends HttpServlet
{
    private long asyncTimeout = 10000;

    @Override
    public void init()
    {
        String parameter = getServletConfig().getInitParameter("asyncTimeout");
        if (parameter != null)
            asyncTimeout = Long.parseLong(parameter);
    }

    // inner class to hold message queue for each chat room member
    class Member implements AsyncListener
    {
        final String _name;
        final AtomicReference<AsyncContext> _async = new AtomicReference<>();
        final Queue<String> _queue = new LinkedList<>();

        Member(String name)
        {
            _name = name;
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            getServletContext().log("resume request");
            AsyncContext async = _async.get();
            if (async != null && _async.compareAndSet(async, null))
            {
                HttpServletResponse response = (HttpServletResponse)async.getResponse();
                response.setContentType("text/json;charset=utf-8");
                response.getOutputStream().write("{action:\"poll\"}".getBytes());
                async.complete();
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }
    }

    Map<String, Map<String, Member>> _rooms = new HashMap<>();

    // Handle Ajax calls from browser
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        // Ajax calls are form encoded
        boolean join = Boolean.parseBoolean(request.getParameter("join"));
        String message = request.getParameter("message");
        String username = request.getParameter("user");

        getServletContext().log("doPost called. join=" + join + " message=" + message + " username=" + username);
        if (username == null)
        {
            getServletContext().log("no parameter user set, sending 503");
            response.sendError(503, "user==null");
            return;
        }

        Map<String, Member> room = getRoom(request.getPathInfo());
        Member member = getMember(username, room);

        if (message != null)
        {
            sendMessageToAllMembers(message, username, room);
        }
        // If a message is set, we only want to enter poll mode if the user is a new user. This is necessary to avoid
        // two parallel requests per user (one is already in async wait and the new one). Sending a message will
        // dispatch to an existing poll request if necessary and the client will issue a new request to receive the
        // next message or long poll again.
        if (message == null || join)
        {
            synchronized (member)
            {
                getServletContext().log("Queue size: " + member._queue.size());
                if (!member._queue.isEmpty())
                {
                    sendSingleMessage(response, member);
                }
                else
                {
                    getServletContext().log("starting async");
                    AsyncContext async = request.startAsync();
                    async.setTimeout(asyncTimeout);
                    async.addListener(member);
                    member._async.set(async);
                }
            }
        }
    }

    private Member getMember(String username, Map<String, Member> room)
    {
        Member member = room.get(username);
        if (member == null)
        {
            getServletContext().log("user: " + username + " in room: " + room + " doesn't exist. Creating new user.");
            member = new Member(username);
            room.put(username, member);
        }
        return member;
    }

    private Map<String, Member> getRoom(String path)
    {
        Map<String, Member> room = _rooms.get(path);
        if (room == null)
        {
            getServletContext().log("room: " + path + " doesn't exist. Creating new room.");
            room = new HashMap<>();
            _rooms.put(path, room);
        }
        return room;
    }

    private void sendSingleMessage(HttpServletResponse response, Member member) throws IOException
    {
        response.setContentType("text/json;charset=utf-8");
        StringBuilder buf = new StringBuilder();

        buf.append("{\"from\":\"");
        buf.append(member._queue.poll());
        buf.append("\",");

        String returnMessage = member._queue.poll();
        int quote = returnMessage.indexOf('"');
        while (quote >= 0)
        {
            returnMessage = returnMessage.substring(0, quote) + '\\' + returnMessage.substring(quote);
            quote = returnMessage.indexOf('"', quote + 2);
        }
        buf.append("\"chat\":\"");
        buf.append(returnMessage);
        buf.append("\"}");
        byte[] bytes = buf.toString().getBytes("utf-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private void sendMessageToAllMembers(String message, String username, Map<String, Member> room)
    {
        for (Member m : room.values())
        {
            synchronized (m)
            {
                m._queue.add(username); // from
                m._queue.add(message);  // chat

                // wakeup member if polling
                AsyncContext async = m._async.get();
                if (async != null & m._async.compareAndSet(async, null))
                {
                    async.dispatch();
                }
            }
        }
    }

    // Serve the HTML with embedded CSS and Javascript.
    // This should be static content and should use real JS libraries.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getParameter("action") != null)
            doPost(request, response);
        else
            getServletContext().getNamedDispatcher("default").forward(request, response);
    }
}
