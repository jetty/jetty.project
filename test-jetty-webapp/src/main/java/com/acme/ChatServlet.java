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
        if (room==null)
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

        response.setContentType("text/json;charset=utf-8");
        PrintWriter out=response.getWriter();
        out.print("{action:\"chat\"}");  
    }

    
    // Serve the HTML with embedded CSS and Javascript.
    // This should be static content and should use real JS libraries.
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (!request.getRequestURI().endsWith("/"))
        {
            response.sendRedirect(request.getRequestURI()+"/");
            return;
        }
        if (request.getParameter("action")!=null)
        {
            doPost(request,response);
            return;
        }
        
        response.setContentType("text/html");
        PrintWriter out=response.getWriter();
        out.println("<html><head>");
        out.println("    <title>async chat</title>");
        out.println("    <script type='text/javascript'>");
        out.println("      function $() { return document.getElementById(arguments[0]); }");
        out.println("      function $F() { return document.getElementById(arguments[0]).value; }");
        out.println("      function getKeyCode(ev) { if (window.event) return window.event.keyCode; return ev.keyCode; } ");
        out.println("      function xhr(method,uri,body,handler) {");
        out.println("        var req=(window.XMLHttpRequest)?new XMLHttpRequest():new ActiveXObject('Microsoft.XMLHTTP');");
        out.println("        req.onreadystatechange=function() { if (req.readyState==4 && handler) { eval('var o='+req.responseText);handler(o);} }");
        out.println("        req.open(method,uri,true);");
        out.println("        req.setRequestHeader('Content-Type','application/x-www-form-urlencoded');");
        out.println("        req.send(body);");
        out.println("      };");
        out.println("      function send(action,user,message,handler){");
        out.println("        if (message) message=message.replace('%','%25').replace('&','%26').replace('=','%3D');");
        out.println("        if (user) user=user.replace('%','%25').replace('&','%26').replace('=','%3D');");
        out.println("        xhr('POST','chat','action='+action+'&user='+user+'&message='+message,handler);");
        out.println("      };");
        out.println("      ");
        out.println("      var room = {");
        out.println("        join: function(name) {");
        out.println("          this._username=name;");
        out.println("          $('join').className='hidden';");
        out.println("          $('joined').className='';");
        out.println("          $('phrase').focus();");
        out.println("          send('join', room._username,null);");
        out.println("          send('chat', room._username,'has joined!');");
        out.println("          send('poll', room._username,null, room._poll);");
        out.println("        },");
        out.println("        chat: function(text) {");
        out.println("          if (text != null && text.length>0 )");
        out.println("              send('chat',room._username,text);");
        out.println("        },");
        out.println("        _poll: function(m) {");
        out.println("          //console.debug(m);");
        out.println("          if (m.chat){");
        out.println("            var chat=document.getElementById('chat');");
        out.println("            var spanFrom = document.createElement('span');");
        out.println("            spanFrom.className='from';");
        out.println("            spanFrom.innerHTML=m.from+':&nbsp;';");
        out.println("            var spanText = document.createElement('span');");
        out.println("            spanText.className='text';");
        out.println("            spanText.innerHTML=m.chat;");
        out.println("            var lineBreak = document.createElement('br');");
        out.println("            chat.appendChild(spanFrom);");
        out.println("            chat.appendChild(spanText);");
        out.println("            chat.appendChild(lineBreak);");
        out.println("            chat.scrollTop = chat.scrollHeight - chat.clientHeight;   ");
        out.println("          }");
        out.println("          if (m.action=='poll')");
        out.println("            send('poll', room._username,null, room._poll);");
        out.println("        },");
        out.println("        _end:''");
        out.println("      };");
        out.println("    </script>");
        out.println("    <style type='text/css'>");
        out.println("    div { border: 0px solid black; }");
        out.println("    div#chat { clear: both; width: 40em; height: 20ex; overflow: auto; background-color: #f0f0f0; padding: 4px; border: 1px solid black; }");
        out.println("    div#input { clear: both; width: 40em; padding: 4px; background-color: #e0e0e0; border: 1px solid black; border-top: 0px }");
        out.println("    input#phrase { width:30em; background-color: #e0f0f0; }");
        out.println("    input#username { width:14em; background-color: #e0f0f0; }");
        out.println("    div.hidden { display: none; }");
        out.println("    span.from { font-weight: bold; }");
        out.println("    span.alert { font-style: italic; }");
        out.println("    </style>");
        out.println("</head><body>");
        out.println("<div id='chat'></div>");
        out.println("<div id='input'>");
        out.println("  <div id='join' >");
        out.println("    Username:&nbsp;<input id='username' type='text'/><input id='joinB' class='button' type='submit' name='join' value='Join'/>");
        out.println("  </div>");
        out.println("  <div id='joined' class='hidden'>");
        out.println("    Chat:&nbsp;<input id='phrase' type='text'></input>");
        out.println("    <input id='sendB' class='button' type='submit' name='join' value='Send'/>");
        out.println("  </div>");
        out.println("</div>");
        out.println("<script type='text/javascript'>");
        out.println("$('username').setAttribute('autocomplete','OFF');");
        out.println("$('username').onkeyup = function(ev) { var keyc=getKeyCode(ev); if (keyc==13 || keyc==10) { room.join($F('username')); return false; } return true; } ;        ");
        out.println("$('joinB').onclick = function(event) { room.join($F('username')); return false; };");
        out.println("$('phrase').setAttribute('autocomplete','OFF');");
        out.println("$('phrase').onkeyup = function(ev) {   var keyc=getKeyCode(ev); if (keyc==13 || keyc==10) { room.chat($F('phrase')); $('phrase').value=''; return false; } return true; };");
        out.println("$('sendB').onclick = function(event) { room.chat($F('phrase')); $('phrase').value=''; return false; };");
        out.println("</script>");
        out.println("</body></html>");
    }
    
}
