//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.browser;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class BrowserSocket
{
    private static final Logger LOG = Log.getLogger(BrowserSocket.class);
    private WebSocketConnection connection;
    private final String userAgent;
    private final String requestedExtensions;

    public BrowserSocket(String ua, String reqExts)
    {
        this.userAgent = ua;
        this.requestedExtensions = reqExts;
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        this.connection = conn;
    }

    @OnWebSocketClose
    public void onDisconnect(int statusCode, String reason)
    {
        this.connection = null;
        LOG.info("Closed [{}, {}]",statusCode,reason);
    }

    @OnWebSocketMessage
    public void onTextMessage(String message)
    {
        LOG.info("onTextMessage({})",message);

        int idx = message.indexOf(':');
        if (idx > 0)
        {
            String key = message.substring(0,idx).toLowerCase(Locale.ENGLISH);
            String val = message.substring(idx + 1);
            switch (key)
            {
                case "info":
                {
                    if (StringUtil.isBlank(userAgent))
                    {
                        writeMessage("Client has no User-Agent");
                    }
                    else
                    {
                        writeMessage("Client User-Agent: " + this.userAgent);
                    }

                    if (StringUtil.isBlank(requestedExtensions))
                    {
                        writeMessage("Client requested no Sec-WebSocket-Extensions");
                    }
                    else
                    {
                        writeMessage("Client Sec-WebSocket-Extensions: " + this.requestedExtensions);
                    }
                    break;
                }
                case "time":
                {
                    Calendar now = Calendar.getInstance();
                    DateFormat sdf = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.FULL,SimpleDateFormat.FULL);
                    writeMessage("Server time: %s",sdf.format(now.getTime()));
                    break;
                }
                default:
                {
                    writeMessage("key[%s] val[%s]",key,val);
                }
            }
        }
        else
        {
            // echo it
            writeMessage(message);
        }
    }

    private void writeMessage(String message)
    {
        if (this.connection == null)
        {
            LOG.debug("Not connected");
            return;
        }

        if (connection.isOpen() == false)
        {
            LOG.debug("Not open");
            return;
        }

        connection.write(message);
    }

    private void writeMessage(String format, Object... args)
    {
        writeMessage(String.format(format,args));
    }
}
