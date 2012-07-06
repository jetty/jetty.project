package org.eclipse.jetty.websocket.client.blockhead;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.protocol.AcceptHash;

/**
 * A overly simplistic websocket server used during testing.
 * <p>
 * This is not meant to be performant or accurate. In fact, having the server misbehave is a useful trait during testing.
 */
public class BlockheadServer
{
    public static class ServerConnection
    {

        public void close()
        {
            // TODO Auto-generated method stub

        }

        public void flush()
        {
            // TODO Auto-generated method stub

        }

        public InputStream getInputStream()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public void respond(String rawstr)
        {
            // TODO Auto-generated method stub

        }

        public void setSoTimeout(int ms)
        {
            // TODO Auto-generated method stub

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

        private void write(byte[] bytes)
        {
            // TODO Auto-generated method stub

        }

        public void write(byte[] buf, int offset, int length)
        {
            // TODO Auto-generated method stub

        }

        public void write(int b)
        {
            // TODO Auto-generated method stub

        }
    }

    public ServerConnection accept()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void accept(Socket connection) throws IOException
    {
        String key = "not sent";
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
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

        connection.getOutputStream().write(resp.toString().getBytes());
    }

    public void close()
    {
        // TODO Auto-generated method stub

    }

    public int getPort()
    {
        // TODO Auto-generated method stub
        return -1;
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

    public void start()
    {
        // TODO Auto-generated method stub

    }
}
