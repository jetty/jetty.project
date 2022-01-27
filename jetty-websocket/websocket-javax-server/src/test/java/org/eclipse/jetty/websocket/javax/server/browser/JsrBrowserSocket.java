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

package org.eclipse.jetty.websocket.javax.server.browser;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/", subprotocols = {"tool"}, configurator = JsrBrowserConfigurator.class)
public class JsrBrowserSocket
{
    private static class WriteMany implements Runnable
    {
        private Async remote;
        private int size;
        private int count;

        public WriteMany(Async remote, int size, int count)
        {
            this.remote = remote;
            this.size = size;
            this.count = count;
        }

        @Override
        public void run()
        {
            char[] letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-|{}[]():".toCharArray();
            int lettersLen = letters.length;
            char[] randomText = new char[size];
            Random rand = new Random(42);
            String msg;

            for (int n = 0; n < count; n++)
            {
                // create random text
                for (int i = 0; i < size; i++)
                {
                    randomText[i] = letters[rand.nextInt(lettersLen)];
                }
                msg = String.format("ManyThreads [%s]", String.valueOf(randomText));
                remote.sendText(msg);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(JsrBrowserSocket.class);
    private Session session;
    private Async remote;
    private String userAgent;
    private String requestedExtensions;

    @OnOpen
    public void onOpen(Session session)
    {
        LOG.info("Open: {}", session);
        this.session = session;
        this.remote = session.getAsyncRemote();
        this.userAgent = (String)session.getUserProperties().get("userAgent");
        this.requestedExtensions = (String)session.getUserProperties().get("requestedExtensions");
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        LOG.info("Close: {}: {}", close.getCloseCode(), close.getReasonPhrase());
        this.session = null;
    }

    @OnMessage
    public void onMessage(String message)
    {
        LOG.info("onTextMessage({})", message);

        int idx = message.indexOf(':');
        if (idx > 0)
        {
            String key = message.substring(0, idx).toLowerCase(Locale.ENGLISH);
            String val = message.substring(idx + 1);
            switch (key)
            {
                case "info":
                {
                    writeMessage("Using javax.websocket");
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

                    Set<Session> openSessions = session.getOpenSessions();
                    writeMessage("OpenSessions.size() = " + openSessions.size());
                    int i = 0;
                    for (Session open : openSessions)
                    {
                        writeMessage("  OpenSession[%d] = %s", i++, open);
                    }
                    break;
                }
                case "many":
                {
                    String[] parts = StringUtil.csvSplit(val);
                    int size = Integer.parseInt(parts[0]);
                    int count = Integer.parseInt(parts[1]);

                    writeManyAsync(size, count);
                    break;
                }
                case "manythreads":
                {
                    String[] parts = StringUtil.csvSplit(val);
                    int threadCount = Integer.parseInt(parts[0]);
                    int size = Integer.parseInt(parts[1]);
                    int count = Integer.parseInt(parts[2]);

                    Thread[] threads = new Thread[threadCount];

                    // Setup threads
                    for (int n = 0; n < threadCount; n++)
                    {
                        threads[n] = new Thread(new WriteMany(remote, size, count), "WriteMany[" + n + "]");
                    }

                    // Execute threads
                    for (Thread thread : threads)
                    {
                        thread.start();
                    }

                    // Drop out of this thread
                    break;
                }
                case "time":
                {
                    Calendar now = Calendar.getInstance();
                    DateFormat sdf = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.FULL, SimpleDateFormat.FULL);
                    writeMessage("Server time: %s", sdf.format(now.getTime()));
                    break;
                }
                default:
                {
                    writeMessage("key[%s] val[%s]", key, val);
                }
            }
        }
        else
        {
            // Not parameterized, echo it back
            writeMessage(message);
        }
    }

    private void writeManyAsync(int size, int count)
    {
        char[] letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-|{}[]():".toCharArray();
        int lettersLen = letters.length;
        char[] randomText = new char[size];
        Random rand = new Random(42);

        for (int n = 0; n < count; n++)
        {
            // create random text
            for (int i = 0; i < size; i++)
            {
                randomText[i] = letters[rand.nextInt(lettersLen)];
            }
            writeMessage("Many [%s]", String.valueOf(randomText));
        }
    }

    private void writeMessage(String message)
    {
        if (this.session == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not connected");
            return;
        }

        if (session.isOpen() == false)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not onOpen");
            return;
        }

        // Async write
        remote.sendText(message);
    }

    private void writeMessage(String format, Object... args)
    {
        writeMessage(String.format(format, args));
    }
}
