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

package org.eclipse.jetty.websocket.dummy;

import static org.hamcrest.Matchers.*;

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
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocketConnectionRFC6455;
import org.junit.Assert;

/**
 * Simple ServerSocket server used to test oddball server scenarios encountered in the real world.
 */
public class DummyServer
{
    public static class ServerConnection
    {
        private static final Logger LOG = Log.getLogger(ServerConnection.class);
        private final Socket socket;
        private InputStream in;
        private OutputStream out;

        public ServerConnection(Socket socket)
        {
            this.socket = socket;
        }
        
        public int read(ByteBuffer buf) throws IOException
        {
            int len = 0;
            while ((in.available() > 0) && (buf.remaining() > 0))
            {
                buf.put((byte)in.read());
                len++;
            }
            return len;
        }

        public void disconnect()
        {
            LOG.debug("disconnect");
            IO.close(in);
            IO.close(out);
            if (socket != null)
            {
                try
                {
                    socket.close();
                }
                catch (IOException ignore)
                {
                    /* ignore */
                }
            }
        }

        public InputStream getInputStream() throws IOException
        {
            if (in == null)
            {
                in = socket.getInputStream();
            }
            return in;
        }

        public OutputStream getOutputStream() throws IOException
        {
            if (out == null)
            {
                out = socket.getOutputStream();
            }
            return out;
        }

        public void flush() throws IOException
        {
            LOG.debug("flush()");
            getOutputStream().flush();
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

        public void upgrade(Map<String, String> extraResponseHeaders) throws IOException
        {
            @SuppressWarnings("unused")
            Pattern patExts = Pattern.compile("^Sec-WebSocket-Extensions: (.*)$",Pattern.CASE_INSENSITIVE);
            Pattern patKey = Pattern.compile("^Sec-WebSocket-Key: (.*)$",Pattern.CASE_INSENSITIVE);

            LOG.debug("(Upgrade) Reading HTTP Request");
            Matcher mat;
            String key = "not sent";
            BufferedReader in = new BufferedReader(new InputStreamReader(getInputStream()));
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                if (line.length() == 0)
                {
                    break;
                }

                // TODO: Check for extensions
                // mat = patExts.matcher(line);
                // if (mat.matches())

                // Check for Key
                mat = patKey.matcher(line);
                if (mat.matches())
                {
                    key = mat.group(1);
                }
            }

            LOG.debug("(Upgrade) Writing HTTP Response");
            // TODO: handle extensions?

            // Setup Response
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Upgrade\r\n");
            resp.append("Upgrade: websocket\r\n");
            resp.append("Connection: upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ");
            resp.append(WebSocketConnectionRFC6455.hashKey(key)).append("\r\n");
            // extra response headers.
            if (extraResponseHeaders != null)
            {
                for (Map.Entry<String,String> header : extraResponseHeaders.entrySet())
                {
                    resp.append(header.getKey());
                    resp.append(": ");
                    resp.append(header.getValue());
                    resp.append("\r\n");
                }
            }
            resp.append("\r\n");

            // Write Response
            getOutputStream().write(resp.toString().getBytes());
            flush();
        }

        public void write(byte[] bytes) throws IOException
        {
            LOG.debug("Writing {} bytes", bytes.length);
            getOutputStream().write(bytes);
        }

        public void write(byte[] buf, int offset, int length) throws IOException
        {
            LOG.debug("Writing bytes[{}], offset={}, length={}", buf.length, offset, length);
            getOutputStream().write(buf,offset,length);
        }

        public void write(int b) throws IOException
        {
            LOG.debug("Writing int={}", b);
            getOutputStream().write(b);
        }
    }

    private static final Logger LOG = Log.getLogger(DummyServer.class);
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
