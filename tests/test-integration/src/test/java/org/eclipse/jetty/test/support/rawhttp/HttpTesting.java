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

package org.eclipse.jetty.test.support.rawhttp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import org.eclipse.jetty.test.support.StringUtil;
import org.eclipse.jetty.util.IO;

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

    public HttpTesting(HttpSocket httpSocket, InetAddress host, int port)
    {
        this.httpSocket = httpSocket;
        this.serverHost = host;
        this.serverPort = port;
    }

    public HttpTesting(HttpSocket socket, int port) throws UnknownHostException
    {
        this(socket,InetAddress.getLocalHost(),port);
    }

    public HttpTesting(HttpSocket socket, String host, int port) throws UnknownHostException
    {
        this(socket,InetAddress.getByName(host),port);
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

    private void DEBUG(String msg)
    {
        if (debug)
        {
            System.out.println(msg);
        }
    }

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
     * @throws IOException
     */
    public Socket open() throws IOException
    {
        Socket sock = httpSocket.connect(serverHost,serverPort);
        sock.setSoTimeout(timeoutMillis);
        return sock;
    }

    /**
     * Read a response from a socket.
     * 
     * @param sock
     *            the socket to read from.
     * @return the response object
     * @throws IOException
     */
    public HttpResponseTester read(Socket sock) throws IOException
    {
        HttpResponseTester response = new HttpResponseTester();
        response.parse(readRaw(sock));
        return response;
    }

    /**
     * Read any available response from a socket.
     * 
     * @param sock
     *            the socket to read from.
     * @return the response object
     * @throws IOException
     */
    public HttpResponseTester readAvailable(Socket sock) throws IOException
    {
        HttpResponseTester response = new HttpResponseTester();
        String rawResponse = readRawAvailable(sock);
        if (StringUtil.isBlank(rawResponse))
        {
            return null;
        }
        response.parse(rawResponse);
        return response;
    }

    /**
     * Read the raw response from the socket.
     * 
     * @param sock
     * @return
     * @throws IOException
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
     * Read the raw response from the socket, reading whatever is available. Any SocketTimeoutException is consumed and just stops the reading.
     * 
     * @param sock
     * @return
     * @throws IOException
     */
    public String readRawAvailable(Socket sock) throws IOException
    {
        sock.setSoTimeout(timeoutMillis);
        // Collect response

        StringWriter writer = new StringWriter();
        InputStreamReader reader = new InputStreamReader(sock.getInputStream());

        try
        {
            IO.copy(reader,writer);
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
     * @param request
     *            the request
     * @return the response
     * @throws IOException
     */
    public HttpResponseTester request(CharSequence rawRequest) throws IOException
    {
        Socket sock = open();
        try
        {
            send(sock,rawRequest);
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
     * @param request
     *            the request
     * @return the response
     * @throws IOException
     */
    public HttpResponseTester request(HttpRequestTester request) throws IOException
    {
        String rawRequest = request.generate();
        return request(rawRequest);
    }

    /**
     * Initiate multiple raw HTTP requests, parse the responses.
     * 
     * @param rawRequests
     *            the raw HTTP requests.
     * @return the responses.
     * @throws IOException
     */
    public List<HttpResponseTester> requests(CharSequence rawRequests) throws IOException
    {
        Socket sock = open();
        try
        {
            send(sock,rawRequests);

            // Collect response
            String rawResponses = IO.toString(sock.getInputStream());
            DEBUG("--raw-response--\n" + rawResponses);
            return HttpResponseTester.parseMulti(rawResponses);
        }
        finally
        {
            close(sock);
        }
    }

    /**
     * Initiate a multiple HTTP requests, parse the responses
     * 
     * @param requests
     *            the request objects.
     * @return the response objects.
     * @throws IOException
     */
    public List<HttpResponseTester> requests(List<HttpRequestTester> requests) throws IOException
    {
        StringBuffer rawRequest = new StringBuffer();
        for (HttpRequestTester request : requests)
        {
            rawRequest.append(request.generate());
        }

        return requests(rawRequest);
    }

    /**
     * Send a data (as request) to open socket.
     * 
     * @param sock
     *            the socket to send the request to
     * @param rawData
     *            the raw data to send.
     * @throws IOException
     */
    public void send(Socket sock, CharSequence rawData) throws IOException
    {
        sock.setSoTimeout(timeoutMillis);

        DEBUG("--raw-request--\n" + rawData.toString());
        InputStream in = new ByteArrayInputStream(rawData.toString().getBytes());

        // Send request
        IO.copy(in,sock.getOutputStream());
    }

    public void setTimeoutMillis(int timeoutMillis)
    {
        this.timeoutMillis = timeoutMillis;
    }
}
