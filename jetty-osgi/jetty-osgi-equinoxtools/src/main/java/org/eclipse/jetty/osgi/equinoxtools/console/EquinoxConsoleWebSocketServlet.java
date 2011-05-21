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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.osgi.equinoxtools.WebEquinoxToolsActivator;
import org.eclipse.jetty.osgi.equinoxtools.console.WebConsoleWriterOutputStream.OnFlushListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;
import org.eclipse.osgi.framework.console.ConsoleSession;

/**
 * Websocket version of the Chat with equinox.
 * Ported from jetty's example 'WebSocketChatServlet'
 */
public class EquinoxConsoleWebSocketServlet extends WebSocketServlet implements OnFlushListener
{
    private final Set<ChatWebSocket> _members = new CopyOnWriteArraySet<ChatWebSocket>();
    private static final long serialVersionUID = 1L;
    private WebConsoleSession _consoleSession;
    private EquinoxChattingSupport _support;

    public EquinoxConsoleWebSocketServlet()
    {
        
    }
    public EquinoxConsoleWebSocketServlet(WebConsoleSession consoleSession, EquinoxChattingSupport support)
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
        super.init();
        _consoleSession.addOnFlushListener(this);
    }
    
    @Override
    public void destroy()
    {
        _consoleSession.removeOnFlushListener(this);
    }

    
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
        throws javax.servlet.ServletException ,IOException 
    {
        //getServletContext().getNamedDispatcher("default").forward(request,response);
        response.sendRedirect(request.getContextPath() + request.getServletPath()
                + (request.getPathInfo() != null ? request.getPathInfo() : "") +  "/index.html");
    };
    
    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        return new ChatWebSocket();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ChatWebSocket implements WebSocket.OnTextMessage
    {
        Connection _connection;
        String _username;
        
        public void onOpen(Connection connection)
        {
            // Log.info(this+" onConnect");
            _connection=connection;
            _members.add(this);
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
            // Log.info(this+" onMessage: "+TypeUtil.toHexString(data,offset,length));
        }

        public void onMessage(String data)
        {
            Log.info("onMessage: {}",data);
            if (data.indexOf("disconnect")>=0)
                _connection.disconnect();
            else
            {
                if (!data.endsWith(":has joined!"))
                {
                    if (_username != null)
                    {
                        if (data.startsWith(_username + ":"))
                        {
                            data = data.substring(_username.length()+1);
                        }
                        else
                        {
                            //we should not be here?
                        }
                    }
                    _consoleSession.processCommand(data, false);
                }
                else
                {
                    _username = data.substring(0, data.length()-":has joined!".length());
                }
                // Log.info(this+" onMessage: "+data);
                onFlush();
            }
        }

        public void onClose(int code, String message)
        {
            // Log.info(this+" onDisconnect");
            _members.remove(this);
        }

    }
    
    
    /**
     * Called right after the flush method on the output stream has been executed.
     */
    public void onFlush()
    {
        Queue<String> pendingConsoleOutputMessages = _support.processConsoleOutput(false, this);
        for (ChatWebSocket member : _members)
        {
            try
            {
                for (String line : pendingConsoleOutputMessages)
                {
                    member._connection.sendMessage(line);
                }
            }
            catch(IOException e)
            {
                Log.warn(e);
            }
        }
    }    

}
