//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package com.acme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

// Simple asynchronous Chat room.
// This does not handle duplicate usernames or multiple frames/tabs from the same browser
// Some code is duplicated for clarity.
@SuppressWarnings("serial")
public class ChatServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(ChatServlet.class);

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
            LOG.debug("resume request");
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

        LOG.debug("doPost called. join={},message={},username={}", join, message, username);
        if (username == null)
        {
            LOG.debug("no parameter user set, sending 503");
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
                LOG.debug("Queue size: {}", member._queue.size());
                if (!member._queue.isEmpty())
                {
                    sendSingleMessage(response, member);
                }
                else
                {
                    LOG.debug("starting async");
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
            LOG.debug("user: {} in room: {} doesn't exist. Creating new user.", username, room);
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
            LOG.debug("room: {} doesn't exist. Creating new room.", path);
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
        byte[] bytes = buf.toString().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private void sendMessageToAllMembers(String message, String username, Map<String, Member> room)
    {
        LOG.debug("Sending message: {} from: {}", message, username);
        for (Member m : room.values())
        {
            synchronized (m)
            {
                m._queue.add(username); // from
                m._queue.add(message);  // chat

                // wakeup member if polling
                AsyncContext async = m._async.get();
                LOG.debug("Async found: {}", async);
                if (async != null & m._async.compareAndSet(async, null))
                {
                    LOG.debug("dispatch");
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
