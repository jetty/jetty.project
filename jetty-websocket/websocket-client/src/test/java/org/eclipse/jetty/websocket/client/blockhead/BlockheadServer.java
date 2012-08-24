//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.blockhead;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.protocol.AcceptHash;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A overly simplistic websocket server used during testing.
 * <p>
 * This is not meant to be performant or accurate. In fact, having the server misbehave is a useful trait during testing.
 */
public class BlockheadServer
{
    public static class ServerConnection
    {
        private final Socket socket;
        private OutputStream out;
        private InputStream in;

        public ServerConnection(Socket socket)
        {
            this.socket = socket;
        }

        public void close() throws IOException
        {
            this.socket.close();
        }

        public void echoMessage()
        {
            // TODO Auto-generated method stub

        }

        public void flush() throws IOException
        {
            getOutputStream().flush();
        }

        public InputStream getInputStream() throws IOException
        {
            if (in == null)
            {
                in = socket.getInputStream();
            }
            return in;
        }

        private OutputStream getOutputStream() throws IOException
        {
            if (out == null)
            {
                out = socket.getOutputStream();
            }
            return out;
        }

        public String readRequest() throws IOException
        {
            LOG.debug("Reading client request");
            StringBuilder request = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(getInputStream()));
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                if (line.length() == 0)
                {
                    break;
                }
                request.append(line).append("\r\n");
                LOG.debug("read line: {}",line);
            }

            LOG.debug("Client Request:{}{}","\n",request);
            return request.toString();
        }

        public void respond(String rawstr) throws IOException
        {
            LOG.debug("respond(){}{}","\n",rawstr);
            getOutputStream().write(rawstr.getBytes());
            flush();
        }

        public void setSoTimeout(int ms) throws SocketException
        {
            socket.setSoTimeout(ms);
        }

        public void upgrade() throws IOException
        {
            String key = "not sent";
            BufferedReader in = new BufferedReader(new InputStreamReader(getInputStream()));
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                if (line.length() == 0)
                {
                    break;
                }
                if (line.startsWith("Sec-WebSocket-Key:"))
                {
                    key = line.substring(18).trim();
                }
            }

            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ");
            resp.append(AcceptHash.hashKey(key));
            resp.append("\r\n");
            resp.append("\r\n");

            write(resp.toString().getBytes());
        }

        private void write(byte[] bytes) throws IOException
        {
            getOutputStream().write(bytes);
        }

        public void write(byte[] buf, int offset, int length) throws IOException
        {
            getOutputStream().write(buf,offset,length);
        }

        public void write(int b) throws IOException
        {
            getOutputStream().write(b);
        }

        public void write(WebSocketFrame frame)
        {
            // TODO Auto-generated method stub

        }
    }

    private static final Logger LOG = Log.getLogger(BlockheadServer.class);
    private ServerSocket serverSocket;
    private URI wsUri;

    public ServerConnection accept() throws IOException
    {
        LOG.debug(".accept()");
        assertIsStarted();
        Socket socket = serverSocket.accept();
        return new ServerConnection(socket);
    }

    private void assertIsStarted()
    {
        Assert.assertThat("ServerSocket",serverSocket,notNullValue());
        Assert.assertThat("ServerSocket.isBound",serverSocket.isBound(),is(true));
        Assert.assertThat("ServerSocket.isClosed",serverSocket.isClosed(),is(false));

        Assert.assertThat("WsUri",wsUri,notNullValue());
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public void respondToClient(Socket connection, String serverResponse) throws IOException
    {
        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader buf = null;
        OutputStream out = null;
        try
        {
            in = connection.getInputStream();
            isr = new InputStreamReader(in);
            buf = new BufferedReader(isr);
            String line;
            while ((line = buf.readLine()) != null)
            {
                // System.err.println(line);
                if (line.length() == 0)
                {
                    // Got the "\r\n" line.
                    break;
                }
            }

            // System.out.println("[Server-Out] " + serverResponse);
            out = connection.getOutputStream();
            out.write(serverResponse.getBytes());
            out.flush();
        }
        finally
        {
            IO.close(buf);
            IO.close(isr);
            IO.close(in);
            IO.close(out);
        }
    }

    public void start() throws IOException
    {
        serverSocket = new ServerSocket();
        InetAddress addr = InetAddress.getByName("localhost");
        InetSocketAddress endpoint = new InetSocketAddress(addr,0);
        serverSocket.bind(endpoint);
        int port = serverSocket.getLocalPort();
        String uri = String.format("ws://%s:%d/",addr.getHostAddress(),port);
        wsUri = URI.create(uri);
        LOG.debug("Server Started on {} -> {}",endpoint,wsUri);
    }

    public void stop()
    {
        LOG.debug("Stopping Server");
        try
        {
            serverSocket.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
}
