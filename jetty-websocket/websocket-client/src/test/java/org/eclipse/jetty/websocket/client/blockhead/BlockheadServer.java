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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.Extension;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.io.IncomingFrames;
import org.eclipse.jetty.websocket.core.io.OutgoingFrames;
import org.eclipse.jetty.websocket.core.protocol.AcceptHash;
import org.eclipse.jetty.websocket.core.protocol.CloseInfo;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.core.protocol.Generator;
import org.eclipse.jetty.websocket.core.protocol.OpCode;
import org.eclipse.jetty.websocket.core.protocol.Parser;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;
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
        private final WebSocketExtensionRegistry extensionRegistry;

        /** Set to true to disable timeouts (for debugging reasons) */
        private boolean debug = false;
        private OutputStream out;
        private InputStream in;

        private IncomingFrames incoming = this;
        private OutgoingFrames outgoing = this;

        public ServerConnection(Socket socket)
        {
            this.socket = socket;
            this.incomingFrames = new IncomingFramesCapture();
            this.policy = WebSocketPolicy.newServerPolicy();
            this.bufferPool = new MappedByteBufferPool(BUFFER_SIZE);
            this.parser = new Parser(policy);
            this.parseCount = new AtomicInteger(0);
            this.generator = new Generator(policy,bufferPool,false);
            this.extensionRegistry = new WebSocketExtensionRegistry(policy,bufferPool);
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
            for (WebSocketFrame frame : cap.getFrames())
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
        public void incoming(WebSocketException e)
        {
            incomingFrames.incoming(e);
        }

        @Override
        public void incoming(WebSocketFrame frame)
        {
            LOG.debug("incoming({})",frame);
            int count = parseCount.incrementAndGet();
            if ((count % 10) == 0)
            {
                LOG.info("Server parsed {} frames",count);
            }
            WebSocketFrame copy = new WebSocketFrame(frame);
            incomingFrames.incoming(copy);
        }

        @Override
        public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
        {
            ByteBuffer buf = generator.generate(frame);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("writing out: {}",BufferUtil.toDetailString(buf));
            }
            BufferUtil.writeTo(buf,out);
            out.flush();

            if (frame.getOpCode() == OpCode.CLOSE)
            {
                disconnect();
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

            // Init extensions
            List<Extension> extensions = new ArrayList<>();
            for (ExtensionConfig config : extensionConfigs)
            {
                Extension ext = extensionRegistry.newInstance(config);
                extensions.add(ext);
            }

            // Start with default routing
            incoming = this;
            outgoing = this;

            // Connect extensions
            if (!extensions.isEmpty())
            {
                generator.configureFromExtensions(extensions);

                Iterator<Extension> extIter;
                // Connect outgoings
                extIter = extensions.iterator();
                while (extIter.hasNext())
                {
                    Extension ext = extIter.next();
                    ext.setNextOutgoingFrames(outgoing);
                    outgoing = ext;
                }

                // Connect incomings
                Collections.reverse(extensions);
                extIter = extensions.iterator();
                while (extIter.hasNext())
                {
                    Extension ext = extIter.next();
                    ext.setNextIncomingFrames(incoming);
                    incoming = ext;
                }
            }

            // Configure Parser
            parser.setIncomingFramesHandler(incoming);

            // Setup Response
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ");
            resp.append(AcceptHash.hashKey(key)).append("\r\n");
            if (!extensions.isEmpty())
            {
                // Respond to used extensions
                resp.append("Sec-WebSocket-Extensions: ");
                boolean delim = false;
                for (Extension ext : extensions)
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

        public void write(int b) throws IOException
        {
            getOutputStream().write(b);
        }

        public void write(WebSocketFrame frame) throws IOException
        {
            LOG.debug("write(Frame->{}) to {}",frame,outgoing);
            outgoing.output(null,null,frame);
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
