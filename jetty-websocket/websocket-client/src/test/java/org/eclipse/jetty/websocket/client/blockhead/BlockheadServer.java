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

package org.eclipse.jetty.websocket.client.blockhead;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.junit.Assert;

/**
 * A overly simplistic websocket server used during testing.
 * <p>
 * This is not meant to be performant or accurate. In fact, having the server misbehave is a useful trait during testing.
 */
public class BlockheadServer
{
    public static class ServerConnection implements IncomingFrames, OutgoingFrames
    {
        private final int BUFFER_SIZE = 8192;
        private final Socket socket;
        private final ByteBufferPool bufferPool;
        private final WebSocketPolicy policy;
        private final IncomingFramesCapture incomingFrames;
        private final Parser parser;
        private final Generator generator;
        private final AtomicInteger parseCount;
        private final WebSocketExtensionFactory extensionRegistry;

        /** Set to true to disable timeouts (for debugging reasons) */
        private boolean debug = false;
        private OutputStream out;
        private InputStream in;

        private OutgoingFrames outgoing = this;

        public ServerConnection(Socket socket)
        {
            this.socket = socket;
            this.incomingFrames = new IncomingFramesCapture();
            this.policy = WebSocketPolicy.newServerPolicy();
            this.bufferPool = new MappedByteBufferPool(BUFFER_SIZE);
            this.parser = new Parser(policy,bufferPool);
            this.parseCount = new AtomicInteger(0);
            this.generator = new Generator(policy,bufferPool,false);
            this.extensionRegistry = new WebSocketExtensionFactory(policy,bufferPool);
        }

        public void close() throws IOException
        {
            write(new WebSocketFrame(OpCode.CLOSE));
            flush();
            disconnect();
        }

        public void close(int statusCode) throws IOException
        {
            CloseInfo close = new CloseInfo(statusCode);
            write(close.asFrame());
            flush();
            disconnect();
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

        public void echoMessage(int expectedFrames, TimeUnit timeoutUnit, int timeoutDuration) throws IOException, TimeoutException
        {
            LOG.debug("Echo Frames [expecting {}]",expectedFrames);
            IncomingFramesCapture cap = readFrames(expectedFrames,timeoutUnit,timeoutDuration);
            // now echo them back.
            for (Frame frame : cap.getFrames())
            {
                write(frame);
            }
        }

        public void flush() throws IOException
        {
            getOutputStream().flush();
        }

        public ByteBufferPool getBufferPool()
        {
            return bufferPool;
        }

        public IncomingFramesCapture getIncomingFrames()
        {
            return incomingFrames;
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

        public Parser getParser()
        {
            return parser;
        }

        public WebSocketPolicy getPolicy()
        {
            return policy;
        }

        @Override
        public void incomingError(WebSocketException e)
        {
            incomingFrames.incomingError(e);
        }

        @Override
        public void incomingFrame(Frame frame)
        {
            LOG.debug("incoming({})",frame);
            int count = parseCount.incrementAndGet();
            if ((count % 10) == 0)
            {
                LOG.info("Server parsed {} frames",count);
            }
            WebSocketFrame copy = new WebSocketFrame(frame);
            incomingFrames.incomingFrame(copy);
        }

        @Override
        public void outgoingFrame(Frame frame, WriteCallback callback)
        {
            ByteBuffer buf = generator.generate(frame);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("writing out: {}",BufferUtil.toDetailString(buf));
            }

            try
            {
                BufferUtil.writeTo(buf,out);
                out.flush();
                if (callback != null)
                {
                    callback.writeSuccess();
                }

                if (frame.getType().getOpCode() == OpCode.CLOSE)
                {
                    disconnect();
                }
            }
            catch (Throwable t)
            {
                if (callback != null)
                {
                    callback.writeFailed(t);
                }
            }
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

        public IncomingFramesCapture readFrames(int expectedCount, TimeUnit timeoutUnit, int timeoutDuration) throws IOException, TimeoutException
        {
            LOG.debug("Read: waiting for {} frame(s) from server",expectedCount);
            int startCount = incomingFrames.size();

            ByteBuffer buf = bufferPool.acquire(BUFFER_SIZE,false);
            BufferUtil.clearToFill(buf);
            try
            {
                long msDur = TimeUnit.MILLISECONDS.convert(timeoutDuration,timeoutUnit);
                long now = System.currentTimeMillis();
                long expireOn = now + msDur;
                LOG.debug("Now: {} - expireOn: {} ({} ms)",now,expireOn,msDur);

                int len = 0;
                while (incomingFrames.size() < (startCount + expectedCount))
                {
                    BufferUtil.clearToFill(buf);
                    len = read(buf);
                    if (len > 0)
                    {
                        LOG.debug("Read {} bytes",len);
                        BufferUtil.flipToFlush(buf,0);
                        parser.parse(buf);
                    }
                    try
                    {
                        TimeUnit.MILLISECONDS.sleep(20);
                    }
                    catch (InterruptedException gnore)
                    {
                        /* ignore */
                    }
                    if (!debug && (System.currentTimeMillis() > expireOn))
                    {
                        incomingFrames.dump();
                        throw new TimeoutException(String.format("Timeout reading all %d expected frames. (managed to only read %d frame(s))",expectedCount,
                                incomingFrames.size()));
                    }
                }
            }
            finally
            {
                bufferPool.release(buf);
            }

            return incomingFrames;
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
            List<ExtensionConfig> extensionConfigs = new ArrayList<>();

            Pattern patExts = Pattern.compile("^Sec-WebSocket-Extensions: (.*)$",Pattern.CASE_INSENSITIVE);
            Pattern patKey = Pattern.compile("^Sec-WebSocket-Key: (.*)$",Pattern.CASE_INSENSITIVE);

            Matcher mat;
            String key = "not sent";
            BufferedReader in = new BufferedReader(new InputStreamReader(getInputStream()));
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                if (line.length() == 0)
                {
                    break;
                }

                // Check for extensions
                mat = patExts.matcher(line);
                if (mat.matches())
                {
                    // found extensions
                    String econf = mat.group(1);
                    ExtensionConfig config = ExtensionConfig.parse(econf);
                    extensionConfigs.add(config);
                    continue;
                }

                // Check for Key
                mat = patKey.matcher(line);
                if (mat.matches())
                {
                    key = mat.group(1);
                }
            }

            // collect extensions configured in response header
            ExtensionStack extensionStack = new ExtensionStack(extensionRegistry);
            extensionStack.negotiate(extensionConfigs);

            // Start with default routing
            extensionStack.setNextIncoming(this);
            extensionStack.setNextOutgoing(this);

            // Configure Parser / Generator
            extensionStack.configure(parser);
            extensionStack.configure(generator);

            // Start Stack
            try
            {
                extensionStack.start();
            }
            catch (Exception e)
            {
                throw new IOException("Unable to start Extension Stack");
            }

            // Configure Parser
            parser.setIncomingFramesHandler(extensionStack);

            // Setup Response
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ");
            resp.append(AcceptHash.hashKey(key)).append("\r\n");
            if (!extensionStack.hasNegotiatedExtensions())
            {
                // Respond to used extensions
                resp.append("Sec-WebSocket-Extensions: ");
                boolean delim = false;
                for (ExtensionConfig ext : extensionStack.getNegotiatedExtensions())
                {
                    if (delim)
                    {
                        resp.append(", ");
                    }
                    resp.append(ext.getParameterizedName());
                    delim = true;
                }
                resp.append("\r\n");
            }
            resp.append("\r\n");

            // Write Response
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

        public void write(Frame frame) throws IOException
        {
            LOG.debug("write(Frame->{}) to {}",frame,outgoing);
            outgoing.outgoingFrame(frame,null);
        }

        public void write(int b) throws IOException
        {
            getOutputStream().write(b);
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
        InetAddress addr = InetAddress.getByName("localhost");
        serverSocket = new ServerSocket();
        InetSocketAddress endpoint = new InetSocketAddress(addr,0);
        serverSocket.bind(endpoint,1);
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
