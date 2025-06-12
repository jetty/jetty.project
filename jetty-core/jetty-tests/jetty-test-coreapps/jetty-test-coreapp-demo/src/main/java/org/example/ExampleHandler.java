//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;

/**
 * An example Handler that is used for testing "core" webapp deployment.
 */
public class ExampleHandler extends Handler.Abstract
{
    private final List<String> messages = new ArrayList<>();

    public ExampleHandler()
    {
        ClassLoader cl = this.getClass().getClassLoader();

        try
        {
            List<URL> urls = Collections.list(cl.getResources("org/example/example.properties"));
            for (URL url : urls)
            {
                Properties props = new Properties();
                try (InputStream in = url.openStream())
                {
                    props.load(in);
                    String msg = props.getProperty("message");
                    if (msg != null)
                        messages.add(msg);
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        Server server = request.getConnectionMetaData().getConnector().getServer();
        StringBuilder body = new StringBuilder();
        body.append("Server.info=").append(server.getServerInfo());
        body.append("\nrequest.uri=").append(request.getHttpURI().toURI().toASCIIString());
        body.append("\nmessages.size=").append(messages.size());
        for (int i = 0; i < messages.size(); i++)
        {
            body.append("\nmessage[").append(i).append("]=").append(messages.get(i));
        }
        Content.Sink.write(response, true, body.toString(), callback);
        return true;
    }
}
