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

package org.eclipse.jetty.websocket.core.chat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;

/**

 */
public class ChatWebSocketClient implements FrameHandler
{
    private static Logger LOG = Log.getLogger(ChatWebSocketClient.class);

    public static void main(String[] args)
    {
        String hostname = "localhost";
        int port = 8888;

        if (args.length > 0)
            hostname = args[0];

        if (args.length > 1)
            port = Integer.parseInt(args[1]);

        ChatWebSocketClient client = null;
        try
        {
            String userAgent = "ChatWebsocketClient/0.9";
            client = new ChatWebSocketClient(hostname, port, userAgent);

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            
            String line = in.readLine();
            while(line!=null)
            {
                client.chat(line);
                line = in.readLine();
            }
            
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
        finally
        {
        }
    }


    private URI baseWebsocketUri;
    private WebSocketCoreClient client;
    private CoreSession channel;
    private String name;

    public ChatWebSocketClient(String hostname, int port, String userAgent) throws Exception
    {
        this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
        this.client = new WebSocketCoreClient();
       
        this.client.getPolicy().setMaxBinaryMessageSize(20 * 1024 * 1024);
        this.client.getPolicy().setMaxTextMessageSize(20 * 1024 * 1024);
        // this.client.getExtensionFactory().register("permessage-deflate",PerMessageDeflateExtension.class);
        this.client.start();
        
        
        URI wsUri = baseWebsocketUri.resolve("/chat");
        
        WebSocketCoreClientUpgradeRequest request = new WebSocketCoreClientUpgradeRequest(client, wsUri) 
        {
            @Override
            public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response)
            {
                return ChatWebSocketClient.this;
            }
        };
        request.setSubProtocols("chat");
        
        Future<FrameHandler.CoreSession> response = client.connect(request);

        response.get(5, TimeUnit.SECONDS);
                
    }

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        LOG.info("onOpen {}",coreSession);
        this.channel = coreSession;
    }

    @Override
    public void onReceiveFrame(Frame frame, Callback callback)
    {
        System.out.println(BufferUtil.toString(frame.getPayload()));
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        Callback callback = new Callback()
        {
            @Override
            public void succeeded()
            {
                LOG.info("closed {}", closeStatus);
            }

            @Override
            public void failed(Throwable x)
            {
                LOG.warn(x);
            }

        };

        channel.close(callback);
        this.channel = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        Callback callback = new Callback()
        {
            @Override
            public void succeeded()
            {
                LOG.info("error");
            }

            @Override
            public void failed(Throwable x)
            {
                LOG.warn(x);
            }
        };

        channel.close(callback);
        this.channel = null;
    }


    private static final Pattern COMMAND_PATTERN = Pattern.compile("/([^\\s]+)\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE);

    private void chat(String line)
    {
        if(line.startsWith("/"))
        {
            Matcher matcher = COMMAND_PATTERN.matcher(line);
            if (matcher.matches())
            {
                String command = matcher.group(1);
                String value = matcher.group(2);

                if ("name".equalsIgnoreCase(command))
                {
                    if (value != null && value.length() > 0)
                    {
                        name = value;
                        LOG.info("name changed: " + name);
                    }
                }

                return;
            }
        }

        LOG.info("sending {}...",line);
        Frame frame = new Frame(OpCode.TEXT);
        frame.setFin(true);
        frame.setPayload(name + ": " + line);

        Callback callback = new Callback()
        {
            @Override
            public void succeeded()
            {
                LOG.info("message sent");
            }

            @Override
            public void failed(Throwable x)
            {
                LOG.warn(x);
            }
            
        };

        channel.sendFrame(frame,callback,BatchMode.AUTO);
    }
}
