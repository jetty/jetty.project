//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.ssl;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Rule;

public abstract class SslBytesTest
{
    @Rule
    public TestTracker tracker = new TestTracker();

    protected final Logger logger = Log.getLogger(getClass());

    public static class TLSRecord
    {
        private final Type type;
        private final byte[] bytes;

        public TLSRecord(Type type, byte[] bytes)
        {
            this.type = type;
            this.bytes = bytes;
        }

        public Type getType()
        {
            return type;
        }

        public byte[] getBytes()
        {
            return bytes;
        }

        @Override
        public String toString()
        {
            return "TLSRecord [" + type + "] " + bytes.length + " bytes";
        }

        public enum Type
        {
            CHANGE_CIPHER_SPEC(20), ALERT(21), HANDSHAKE(22), APPLICATION(23);

            private int code;

            Type(int code)
            {
                this.code = code;
                Mapper.codes.put(this.code, this);
            }

            public static Type from(int code)
            {
                Type result = Mapper.codes.get(code);
                if (result == null)
                    throw new IllegalArgumentException("Invalid TLSRecord.Type " + code);
                return result;
            }

            private static class Mapper
            {
                private static final Map<Integer, Type> codes = new HashMap<>();
            }
        }
    }

    public class SimpleProxy implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ExecutorService threadPool;
        private final int proxyPort;
        private final String serverHost;
        private final int serverPort;
        private volatile ServerSocket serverSocket;
        private volatile Socket server;
        private volatile Socket client;

        public SimpleProxy(ExecutorService threadPool, String serverHost, int serverPort)
        {
            this(threadPool, 0, serverHost, serverPort);
        }

        public SimpleProxy(ExecutorService threadPool, int proxyPort, String serverHost, int serverPort)
        {
            this.threadPool = threadPool;
            this.proxyPort = proxyPort;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
        }

        public void start() throws Exception
        {
            serverSocket = new ServerSocket(proxyPort);
            Thread acceptor = new Thread(this);
            acceptor.start();
            server = new Socket(serverHost, serverPort);
        }

        public void stop() throws Exception
        {
            server.close();
            if (client != null) // some tests only run on linux, those won't create a client on other OS
                client.close();
            serverSocket.close();
        }

        public void run()
        {
            try
            {
                client = serverSocket.accept();
                latch.countDown();
            }
            catch (IOException x)
            {
                x.printStackTrace();
            }
        }

        public int getPort()
        {
            return serverSocket.getLocalPort();
        }

        public TLSRecord readFromClient() throws IOException
        {
            TLSRecord record = read(client);
            logger.debug("C --> P {}", record);
            return record;
        }

        private TLSRecord read(Socket socket) throws IOException
        {
            InputStream input = socket.getInputStream();
            int first = -2;
            while (true)
            {
                try
                {
                    socket.setSoTimeout(500);
                    first = input.read();
                    break;
                }
                catch (SocketTimeoutException x)
                {
                    if (Thread.currentThread().isInterrupted())
                        break;
                }
            }
            if (first == -2)
                throw new InterruptedIOException();
            else if (first == -1)
                return null;

            if (first >= 0x80)
            {
                // SSLv2 Record
                int hiLength = first & 0x3F;
                int loLength = input.read();
                int length = (hiLength << 8) + loLength;
                byte[] bytes = new byte[2 + length];
                bytes[0] = (byte)first;
                bytes[1] = (byte)loLength;
                return read(TLSRecord.Type.HANDSHAKE, input, bytes, 2, length);
            }
            else
            {
                // TLS Record
                int major = input.read();
                int minor = input.read();
                int hiLength = input.read();
                int loLength = input.read();
                int length = (hiLength << 8) + loLength;
                byte[] bytes = new byte[1 + 2 + 2 + length];
                bytes[0] = (byte)first;
                bytes[1] = (byte)major;
                bytes[2] = (byte)minor;
                bytes[3] = (byte)hiLength;
                bytes[4] = (byte)loLength;
                return read(TLSRecord.Type.from(first), input, bytes, 5, length);
            }
        }

        private TLSRecord read(TLSRecord.Type type, InputStream input, byte[] bytes, int offset, int length) throws IOException
        {
            while (length > 0)
            {
                int read = input.read(bytes, offset, length);
                if (read < 0)
                    throw new EOFException();
                offset += read;
                length -= read;
            }
            return new TLSRecord(type, bytes);
        }

        public void flushToServer(TLSRecord record) throws Exception
        {
            flushToServer(record, 100);
        }

        public void flushToServer(TLSRecord record, long sleep) throws Exception
        {
            if (record == null)
            {
                server.shutdownOutput();
                if (client.isOutputShutdown())
                {
                    client.close();
                    server.close();
                }
            }
            else
            {
                flush(sleep, server, record.getBytes());
            }
        }

        public void flushToServer(long sleep, byte... bytes) throws Exception
        {
            flush(sleep, server, bytes);
        }

        private void flush(long sleep, Socket socket, byte... bytes) throws Exception
        {
            OutputStream output = socket.getOutputStream();
            output.write(bytes);
            output.flush();
            if (sleep > 0)
                TimeUnit.MILLISECONDS.sleep(sleep);
        }

        public TLSRecord readFromServer() throws IOException
        {
            TLSRecord record = read(server);
            logger.debug("P <-- S {}", record);
            return record;
        }

        public void flushToClient(TLSRecord record) throws Exception
        {
            if (record == null)
            {
                client.shutdownOutput();
                if (server.isOutputShutdown())
                {
                    server.close();
                    client.close();
                }
            }
            else
            {
                flush(0, client, record.getBytes());
            }
        }

        public SslBytesTest.SimpleProxy.AutomaticFlow startAutomaticFlow() throws InterruptedException
        {
            final CountDownLatch startLatch = new CountDownLatch(2);
            final CountDownLatch stopLatch = new CountDownLatch(2);
            Future<Object> clientToServer = threadPool.submit(() ->
            {
                startLatch.countDown();
                logger.debug("Automatic flow C --> S started");
                try
                {
                    while (true)
                    {
                        flushToServer(readFromClient(), 0);
                    }
                }
                catch (InterruptedIOException x)
                {
                    return null;
                }
                finally
                {
                    stopLatch.countDown();
                    logger.debug("Automatic flow C --> S finished");
                }
            });
            Future<Object> serverToClient = threadPool.submit(() ->
            {
                startLatch.countDown();
                logger.debug("Automatic flow C <-- S started");
                try
                {
                    while (true)
                    {
                        flushToClient(readFromServer());
                    }
                }
                catch (InterruptedIOException x)
                {
                    return null;
                }
                finally
                {
                    stopLatch.countDown();
                    logger.debug("Automatic flow C <-- S finished");
                }
            });
            Assert.assertTrue(startLatch.await(5, TimeUnit.SECONDS));
            return new SslBytesServerTest.SimpleProxy.AutomaticFlow(stopLatch, clientToServer, serverToClient);
        }

        public boolean awaitClient(int time, TimeUnit unit) throws InterruptedException
        {
            return latch.await(time, unit);
        }

        public void sendRSTToServer() throws IOException
        {
            // Calling setSoLinger(true, 0) causes close()
            // to send a RST instead of a FIN, causing an
            // exception to be thrown on the other end
            server.setSoLinger(true, 0);
            server.close();
        }

        public class AutomaticFlow
        {
            private final CountDownLatch stopLatch;
            private final Future<Object> clientToServer;
            private final Future<Object> serverToClient;

            public AutomaticFlow(CountDownLatch stopLatch, Future<Object> clientToServer, Future<Object> serverToClient)
            {
                this.stopLatch = stopLatch;
                this.clientToServer = clientToServer;
                this.serverToClient = serverToClient;
            }

            public boolean stop(long time, TimeUnit unit) throws InterruptedException
            {
                clientToServer.cancel(true);
                serverToClient.cancel(true);
                return stopLatch.await(time, unit);
            }
        }
    }
}
