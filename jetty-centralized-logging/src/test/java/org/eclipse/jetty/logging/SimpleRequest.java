// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;

/**
 * Utility class for performing simple requests.
 */
public class SimpleRequest
{
    private static final boolean CONN_TRACE = false;

    /**
     * Issue an HTTP GET to the server, on the path specified.
     * 
     * @param jetty
     * @param path
     * @throws IOException
     */
    public static String get(XmlConfiguredJetty jetty, String path) throws IOException
    {
        URI fullUri = jetty.getServerURI().resolve(path);
        return get(fullUri);
    }

    /**
     * Issue an HTTP GET to the server, on the path specified.
     * 
     * @param jetty
     * @param path
     * @throws IOException
     */
    public static String get(Server server, String path) throws IOException
    {
        // Find the active server port.
        int serverPort = (-1);
        Connector connectors[] = server.getConnectors();
        for (int i = 0; i < connectors.length; i++)
        {
            Connector connector = connectors[i];
            if (connector.getLocalPort() > 0)
            {
                serverPort = connector.getLocalPort();
                break;
            }
        }

        StringBuffer uri = new StringBuffer();
        uri.append("http://");
        uri.append(InetAddress.getLocalHost().getHostAddress());
        uri.append(":").append(serverPort);

        URI fullUri = URI.create(uri.toString()).resolve(path);
        return get(fullUri);
    }

    private static String get(URI fullUri) throws IOException
    {
        System.out.println("GET: " + fullUri.toASCIIString());

        trace("Opening Connection ...");
        HttpURLConnection conn = (HttpURLConnection)fullUri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        conn.setAllowUserInteraction(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        trace("Connecting ...");
        conn.connect();
        trace("Connected.");

        InputStream in = null;
        InputStreamReader reader = null;
        try
        {
            trace("Getting InputStream ...");
            in = conn.getInputStream();
            trace("Got InputStream.");
            reader = new InputStreamReader(in);

            trace("Reading InputStream to String ...");
            String response = IO.toString(reader);

            trace("Checking Response Code ...");
            if (conn.getResponseCode() != 200)
            {
                System.out.printf("Got HTTP Response %d, expecting %d.%n",conn.getResponseCode(),200);
                System.out.println(response);
            }
            else if ((response != null) && (response.trim().length() > 0))
            {
                System.out.println(response);
            }

            trace("Returning response.");
            return response;
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }

    private static void trace(String format, Object... args)
    {
        if (CONN_TRACE)
        {
            System.out.printf(format,args);
            System.out.println();
        }
    }
}
