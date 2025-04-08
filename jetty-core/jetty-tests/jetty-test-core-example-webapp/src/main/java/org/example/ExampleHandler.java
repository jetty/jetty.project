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
    private final String message;

    public ExampleHandler()
    {
        Properties props = new Properties();
        URL url = this.getClass().getResource("example.properties");
        if (url != null)
        {
            try (InputStream in = url.openStream())
            {
                props.load(in);
                this.message = props.getProperty("message");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        else
        {
            this.message = "Unable to find example.properties";
        }
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        Server server = request.getConnectionMetaData().getConnector().getServer();
        String body = """
            Server.info=%s
            request.uri=%s
            message=%s
            """.formatted(
            server.getServerInfo(),
            request.getHttpURI().toURI().toASCIIString(),
            message);
        Content.Sink.write(response, true, body, callback);
        return true;
    }
}
