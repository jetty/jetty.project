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

package org.eclipse.jetty.ee9.websocket.server.browser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

import org.eclipse.jetty.ee9.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket
public class BrowserSocket
{
    private static class WriteMany implements Runnable
    {
        private RemoteEndpoint remote;
        private int size;
        private int count;

        public WriteMany(RemoteEndpoint remote, int size, int count)
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
                remote.sendString(msg, null);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BrowserSocket.class);

    private Session session;
    private final String userAgent;
    private final String requestedExtensions;

    public BrowserSocket(String ua, String reqExts)
    {
        this.userAgent = ua;
        this.requestedExtensions = reqExts;
    }

    @OnWebSocketConnect
    public void onConnect(Session session)
    {
        LOG.info("Connect [{}]", session);
        this.session = session;
    }

    @OnWebSocketClose
    public void onDisconnect(int statusCode, String reason)
    {
        this.session = null;
        LOG.info("Closed [{}, {}]", statusCode, reason);
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        this.session = null;
        LOG.warn("Error", cause);
    }

    @OnWebSocketMessage
    public void onTextMessage(String message)
    {
        if (message.length() > 300)
        {
            int len = message.length();
            LOG.info("onTextMessage({} ... {}) size:{}", message.substring(0, 15), message.substring(len - 15, len).replaceAll("[\r\n]*", ""), len);
        }
        else
        {
            LOG.info("onTextMessage({})", message);
        }

        // Is multi-line?
        if (message.contains("\n"))
        {
            // echo back exactly
            writeMessage(message);
            return;
        }

        // Is resource lookup?
        if (message.charAt(0) == '@')
        {
            String name = message.substring(1);
            URL url = Loader.getResource(name);
            if (url == null)
            {
                writeMessage("Unable to find resource: " + name);
                return;
            }
            try (InputStream in = url.openStream())
            {
                String data = IO.toString(in);
                writeMessage(data);
            }
            catch (IOException e)
            {
                writeMessage("Unable to read resource: " + name);
                LOG.warn("Unable to read resource: {}", name, e);
            }
            return;
        }

        // Is parameterized?
        int idx = message.indexOf(':');
        if (idx > 0)
        {
            String key = message.substring(0, idx).toLowerCase(Locale.ENGLISH);
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
                        writeMessage("Client requested Sec-WebSocket-Extensions: " + this.requestedExtensions);
                        writeMessage("Negotiated Sec-WebSocket-Extensions: " + session.getUpgradeResponse().getHeader("Sec-WebSocket-Extensions"));
                    }

                    break;
                }
                case "ping":
                {
                    try
                    {
                        LOG.info("PING!");
                        this.session.getRemote().sendPing(BufferUtil.toBuffer("ping from server"));
                    }
                    catch (IOException e)
                    {
                        LOG.warn("Unable to send ping", e);
                    }
                    break;
                }
                case "many":
                {
                    String[] parts = val.split(",");
                    int size = Integer.parseInt(parts[0]);
                    int count = Integer.parseInt(parts[1]);

                    writeManyAsync(size, count);
                    break;
                }
                case "manythreads":
                {
                    String[] parts = val.split(",");
                    int threadCount = Integer.parseInt(parts[0]);
                    int size = Integer.parseInt(parts[1]);
                    int count = Integer.parseInt(parts[2]);

                    Thread[] threads = new Thread[threadCount];

                    // Setup threads
                    for (int n = 0; n < threadCount; n++)
                    {
                        threads[n] = new Thread(new WriteMany(session.getRemote(), size, count), "WriteMany[" + n + "]");
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
                case "dump":
                {
                    String dump = (session instanceof Dumpable) ? ((Dumpable)session).dump() : session.toString();
                    System.err.println(dump);
                    Arrays.stream(dump.split("\n")).forEach(d -> writeMessage("Dump: %s", d));
                    break;
                }
                default:
                {
                    writeMessage("key[%s] val[%s]", key, val);
                }
            }
            return;
        }

        // Not parameterized, echo it back as-is
        writeMessage(message);
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
            LOG.debug("Not connected");
            return;
        }

        if (!session.isOpen())
        {
            LOG.debug("Not open");
            return;
        }

        // Async write
        session.getRemote().sendString(message, null);
    }

    private void writeMessage(String format, Object... args)
    {
        writeMessage(String.format(format, args));
    }
}
