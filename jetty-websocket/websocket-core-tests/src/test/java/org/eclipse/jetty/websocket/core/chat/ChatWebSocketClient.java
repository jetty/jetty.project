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

package org.eclipse.jetty.websocket.core.chat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.util.AutoDemandingMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatWebSocketClient
{
    private static final Logger LOG = LoggerFactory.getLogger(ChatWebSocketClient.class);

    private final URI baseWebsocketUri;
    private final WebSocketCoreClient client;
    private final AutoDemandingMessageHandler handler;
    private String name = String.format("unknown@%x", ThreadLocalRandom.current().nextInt());

    public ChatWebSocketClient(String hostname, int port) throws Exception
    {
        this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
        this.client = new WebSocketCoreClient();
        this.client.start();

        URI wsUri = baseWebsocketUri.resolve("/chat");
        handler = messageHandlerFrom(this::onText, null);
        CoreClientUpgradeRequest request = CoreClientUpgradeRequest.from(client, wsUri, handler);
        request.setSubProtocols("chat");
        client.connect(request).get(5, TimeUnit.SECONDS);
        handler.sendText("[" + name + ": has joined the room]", Callback.NOOP, false);
    }

    public void onText(String message)
    {
        System.out.println(message);
    }

    private static final Pattern COMMAND_PATTERN = Pattern.compile("/([^\\s]+)(\\s+([^\\s]+))?", Pattern.CASE_INSENSITIVE);

    private void chat(String line)
    {
        if (line.startsWith("/"))
        {
            Matcher matcher = COMMAND_PATTERN.matcher(line);
            if (matcher.matches())
            {
                String command = matcher.group(1);
                String value = (matcher.groupCount() > 2) ? matcher.group(3) : null;

                switch (command)
                {
                    case "name":
                        if (value != null && value.length() > 0)
                        {
                            value = value.trim();
                            handler.sendText("[" + value + ": changed name from " + name + "]", Callback.NOOP, false);
                            name = value;
                            LOG.debug("name changed: " + name);
                        }
                        break;

                    case "exit":
                        handler.sendText("[" + name + ": has left the " +
                            ("elvis".equalsIgnoreCase(name) ? "building!]" : "room]"), Callback.NOOP, false);
                        handler.getCoreSession().close(Callback.from(() -> System.exit(0), x ->
                        {
                            x.printStackTrace();
                            System.exit(1);
                        }));
                        break;
                }

                return;
            }
        }
        LOG.debug("sending {}...", line);

        TestMessageHandler.sendText(handler, Callback.from(() -> LOG.debug("message sent"), (cause) -> LOG.warn("message send failure", cause)), false, name, ": ", line);
    }

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
            client = new ChatWebSocketClient(hostname, port);

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

            System.err.println("Type to chat, or:\n  /name <name> - to set member name\n  /exit - to exit\n");
            String line = in.readLine();
            while (line != null)
            {
                line = line.trim();
                if (line.length() > 0)
                    client.chat(line);
                line = in.readLine();
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }

    public static AutoDemandingMessageHandler messageHandlerFrom(Consumer<String> onText, Consumer<ByteBuffer> onBinary)
    {
        return new AutoDemandingMessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
                if (onText == null)
                {
                    super.onText(message, callback);
                    return;
                }

                try
                {
                    onText.accept(message);
                    callback.succeeded();
                }
                catch (Throwable th)
                {
                    callback.failed(th);
                }
            }

            @Override
            protected void onBinary(ByteBuffer message, Callback callback)
            {
                if (onBinary == null)
                {
                    super.onBinary(message, callback);
                    return;
                }

                try
                {
                    onBinary.accept(message);
                    callback.succeeded();
                }
                catch (Throwable th)
                {
                    callback.failed(th);
                }
            }
        };
    }
}
