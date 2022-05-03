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

package org.eclipse.jetty.ee9.test.support.rawhttp;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

/**
 * Testing utility for performing RAW HTTP request/response.
 */
public class HttpTesting
{
    private boolean debug = false;
    private HttpSocket httpSocket;
    private InetAddress serverHost;
    private int serverPort;
    private int timeoutMillis = 5000;

    public static List<HttpTester.Response> getParts(String boundary, HttpTester.Response response) throws IOException
    {
        // TODO This method appears to be broken in how it uses the HttpParser
        // Should use MultiPartInputStreamParser ??

        List<HttpTester.Response> parts = new ArrayList<HttpTester.Response>();

        BufferedReader buf = new BufferedReader(new StringReader(response.getContent()));
        String line;
        String startBoundary = "--" + boundary;
        String endBoundary = "--" + boundary + "--";

        StringBuffer partBuff = null;
        boolean parsingHeader = true;
        boolean previousBodyLine = false;

        while ((line = buf.readLine()) != null)
        {
            if (line.equals(startBoundary))
            {
                // end of multipart, start a new one.
                if (partBuff != null)
                {
                    HttpTester.Response part = HttpTester.parseResponse(partBuff.toString());
                    parts.add(part);
                }
                partBuff = new StringBuffer();
                parsingHeader = true;
                previousBodyLine = false;
                continue;
            }

            if (line.equals(endBoundary))
            {
                if (partBuff != null)
                {
                    HttpTester.Response part = HttpTester.parseResponse(partBuff.toString());
                    parts.add(part);
                }
                break;
            }

            if (parsingHeader)
            {
                if (line.equals(""))
                {
                    parsingHeader = false;
                    continue;
                }

                partBuff.append(line);
            }
            else
            {
                if (previousBodyLine)
                {
                    partBuff.append("\n");
                }
                partBuff.append(line);
                previousBodyLine = true;
            }
        }

        return parts;
    }

    public static List<HttpTester.Response> readResponses(String string) throws IOException
    {
        List<HttpTester.Response> list = new ArrayList<>();

        ByteBuffer buffer = BufferUtil.toBuffer(string);
        while (BufferUtil.hasContent(buffer))
        {
            HttpTester.Response response = HttpTester.parseResponse(buffer);
            if (response == null)
                break;
            list.add(response);
        }
        return list;
    }

    public HttpTesting(HttpSocket httpSocket, InetAddress host, int port)
    {
        this.httpSocket = httpSocket;
        this.serverHost = host;
        this.serverPort = port;
    }

    public HttpTesting(HttpSocket socket, int port) throws UnknownHostException
    {
        this(socket, InetAddress.getLocalHost(), port);
    }

    public HttpTesting(HttpSocket socket, String host, int port) throws UnknownHostException
    {
        this(socket, InetAddress.getByName(host), port);
    }

    public void close(Socket sock)
    {
        if (sock != null)
        {
            try
            {
                sock.close();
            }
            catch (IOException e)
            {
                System.err.println("Unable to close socket: " + sock);
                e.printStackTrace(System.err);
            }
        }
    }

    // @checkstyle-disable-check : MethodName
    private void DEBUG(String msg)
    {
        if (debug)
        {
            System.out.println(msg);
        }
    }
    // @checkstyle-enable-check : MethodName

    public void enableDebug()
    {
        this.debug = true;
    }

    public int getTimeoutMillis()
    {
        return timeoutMillis;
    }

    /**
     * Open a socket.
     *
     * @return the open socket.
     */
    public Socket open() throws IOException
    {
        Socket sock = httpSocket.connect(serverHost, serverPort);
        sock.setSoTimeout(timeoutMillis);
        return sock;
    }

    /**
     * Read a response from a socket.
     *
     * @param sock the socket to read from.
     * @return the response object
     */
    public HttpTester.Response read(Socket sock) throws IOException
    {
        return HttpTester.parseResponse(readRaw(sock));
    }

    public List<HttpTester.Response> readResponses(Socket sock) throws IOException
    {
        List<HttpTester.Response> list = new ArrayList<>();
        String r = readRaw(sock);
        ByteBuffer buffer = BufferUtil.toBuffer(r);
        while (BufferUtil.hasContent(buffer))
        {
            HttpTester.Response response = HttpTester.parseResponse(buffer);
            if (response == null)
                break;
            list.add(response);
        }
        return list;
    }

    /**
     * Read any available response from a socket.
     *
     * @param sock the socket to read from.
     * @return the response object
     */
    public HttpTester.Response readAvailable(Socket sock) throws IOException
    {

        String rawResponse = readRawAvailable(sock);
        if (StringUtil.isBlank(rawResponse))
        {
            return null;
        }
        return HttpTester.parseResponse(rawResponse);
    }

    /**
     * Read the raw response from the socket.
     *
     * @return all of the the data from the socket as a String
     */
    public String readRaw(Socket sock) throws IOException
    {
        sock.setSoTimeout(timeoutMillis);
        // Collect response
        String rawResponse = IO.toString(sock.getInputStream());
        DEBUG("--raw-response--\n" + rawResponse);
        return rawResponse;
    }

    /**
     * Read the raw response from the socket, reading whatever is available.
     * Any {@link SocketTimeoutException} is consumed and just stops the reading.
     *
     * @return the raw data from the socket in string form, reading whatever is available.
     * a {@link SocketTimeoutException} will result in the read stopping.
     */
    public String readRawAvailable(Socket sock) throws IOException
    {
        sock.setSoTimeout(timeoutMillis);
        // Collect response

        StringWriter writer = new StringWriter();
        InputStreamReader reader = new InputStreamReader(sock.getInputStream());

        try
        {
            IO.copy(reader, writer);
        }
        catch (SocketTimeoutException e)
        {
            /* ignore */
        }

        String rawResponse = writer.toString();
        DEBUG("--raw-response--\n" + rawResponse);
        return rawResponse;
    }

    /**
     * Initiate a standard HTTP request, parse the response.
     *
     * Note: not for HTTPS requests.
     *
     * @param rawRequest the request
     * @return the response
     */
    public HttpTester.Response request(CharSequence rawRequest) throws IOException
    {
        Socket sock = open();
        try
        {
            send(sock, rawRequest);
            return read(sock);
        }
        finally
        {
            close(sock);
        }
    }

    /**
     * Initiate a standard HTTP request, parse the response.
     *
     * Note: not for HTTPS requests.
     *
     * @param request the request
     * @return the response
     */
    public HttpTester.Response request(HttpTester.Request request) throws IOException
    {
        ByteBuffer byteBuff = request.generate();
        return request(BufferUtil.toString(byteBuff));
    }

    /**
     * Initiate multiple raw HTTP requests, parse the responses.
     *
     * @param rawRequests the raw HTTP requests.
     * @return the responses.
     */
    public List<HttpTester.Response> requests(CharSequence rawRequests) throws IOException
    {
        Socket sock = open();
        try
        {
            send(sock, rawRequests);

            // Collect response
            String rawResponses = IO.toString(sock.getInputStream());
            DEBUG("--raw-response--\n" + rawResponses);
            return readResponses(rawResponses);
        }
        finally
        {
            close(sock);
        }
    }

    /**
     * Send a data (as request) to open socket.
     *
     * @param sock the socket to send the request to
     * @param rawData the raw data to send.
     */
    public void send(Socket sock, CharSequence rawData) throws IOException
    {
        sock.setSoTimeout(timeoutMillis);

        DEBUG("--raw-request--\n" + rawData.toString());
        InputStream in = new ByteArrayInputStream(rawData.toString().getBytes());

        // Send request
        IO.copy(in, sock.getOutputStream());
    }

    public void setTimeoutMillis(int timeoutMillis)
    {
        this.timeoutMillis = timeoutMillis;
    }
}
