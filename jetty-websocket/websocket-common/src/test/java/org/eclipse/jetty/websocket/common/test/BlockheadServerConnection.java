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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.api.extensions.Frame.Type;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.junit.Assert;

public class BlockheadServerConnection implements IncomingFrames, OutgoingFrames, Runnable, IBlockheadServerConnection
{
    private static final Logger LOG = Log.getLogger(BlockheadServerConnection.class);
    
    private final int BUFFER_SIZE = 8192;
    private final Socket socket;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final IncomingFramesCapture incomingFrames;
    private final Parser parser;
    private final Generator generator;
    private final AtomicInteger parseCount;
    private final WebSocketExtensionFactory extensionRegistry;
    private final AtomicBoolean echoing = new AtomicBoolean(false);
    private Thread echoThread;

    /** Set to true to disable timeouts (for debugging reasons) */
    private boolean debug = false;
    private OutputStream out;
    private InputStream in;

    private Map<String, String> extraResponseHeaders = new HashMap<>();
    private OutgoingFrames outgoing = this;

    public BlockheadServerConnection(Socket socket)
    {
        this.socket = socket;
        this.incomingFrames = new IncomingFramesCapture();
        this.policy = WebSocketPolicy.newServerPolicy();
        this.policy.setMaxBinaryMessageSize(100000);
        this.policy.setMaxTextMessageSize(100000);
        // This is a blockhead server connection, no point tracking leaks on this object.
        this.bufferPool = new MappedByteBufferPool(BUFFER_SIZE);
        this.parser = new Parser(policy,bufferPool);
        this.parseCount = new AtomicInteger(0);
        this.generator = new Generator(policy,bufferPool,false);
        this.extensionRegistry = new WebSocketExtensionFactory(new SimpleContainerScope(policy,bufferPool));
    }

    /**
     * Add an extra header for the upgrade response (from the server). No extra work is done to ensure the key and value are sane for http.
     * @param rawkey the raw key
     * @param rawvalue the raw value
     */
    public void addResponseHeader(String rawkey, String rawvalue)
    {
        extraResponseHeaders.put(rawkey,rawvalue);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection#close()
     */
    @Override
    public void close() throws IOException
    {
        write(new CloseFrame());
        flush();
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection#close(int)
     */
    @Override
    public void close(int statusCode) throws IOException
    {
        CloseInfo close = new CloseInfo(statusCode);
        write(close.asFrame());
        flush();
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

    public void echoMessage(int expectedFrames, int timeoutDuration, TimeUnit timeoutUnit) throws IOException, TimeoutException
    {
        LOG.debug("Echo Frames [expecting {}]",expectedFrames);
        IncomingFramesCapture cap = readFrames(expectedFrames,timeoutDuration,timeoutUnit);
        // now echo them back.
        for (Frame frame : cap.getFrames())
        {
            write(WebSocketFrame.copy(frame).setMasked(false));
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
    public void incomingError(Throwable e)
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
        incomingFrames.incomingFrame(WebSocketFrame.copy(frame));

        if (frame.getOpCode() == OpCode.CLOSE)
        {
            CloseInfo close = new CloseInfo(frame);
            LOG.debug("Close frame: {}",close);
        }

        Type type = frame.getType();
        if (echoing.get() && (type.isData() || type.isContinuation()))
        {
            try
            {
                write(WebSocketFrame.copy(frame).setMasked(false));
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        ByteBuffer headerBuf = generator.generateHeaderBytes(frame);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("writing out: {}",BufferUtil.toDetailString(headerBuf));
        }

        try
        {
            BufferUtil.writeTo(headerBuf,out);
            if (frame.hasPayload())
                BufferUtil.writeTo(frame.getPayload(),out);
            out.flush();
            if (callback != null)
            {
                callback.writeSuccess();
            }

            if (frame.getOpCode() == OpCode.CLOSE)
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

    public List<ExtensionConfig> parseExtensions(List<String> requestLines)
    {
        List<ExtensionConfig> extensionConfigs = new ArrayList<>();
        
        List<String> hits = regexFind(requestLines, "^Sec-WebSocket-Extensions: (.*)$");

        for (String econf : hits)
        {
            // found extensions
            ExtensionConfig config = ExtensionConfig.parse(econf);
            extensionConfigs.add(config);
        }

        return extensionConfigs;
    }

    public String parseWebSocketKey(List<String> requestLines)
    {
        List<String> hits = regexFind(requestLines,"^Sec-WebSocket-Key: (.*)$");
        if (hits.size() <= 0)
        {
            return null;
        }
        
        Assert.assertThat("Number of Sec-WebSocket-Key headers", hits.size(), is(1));
        
        String key = hits.get(0);
        return key;
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

    public IncomingFramesCapture readFrames(int expectedCount, int timeoutDuration, TimeUnit timeoutUnit) throws IOException, TimeoutException
    {
        LOG.debug("Read: waiting for {} frame(s) from client",expectedCount);
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

    public List<String> readRequestLines() throws IOException
    {
        LOG.debug("Reading client request header");
        List<String> lines = new ArrayList<>();

        BufferedReader in = new BufferedReader(new InputStreamReader(getInputStream()));
        for (String line = in.readLine(); line != null; line = in.readLine())
        {
            if (line.length() == 0)
            {
                break;
            }
            lines.add(line);
        }

        return lines;
    }

    public List<String> regexFind(List<String> lines, String pattern)
    {
        List<String> hits = new ArrayList<>();

        Pattern patKey = Pattern.compile(pattern,Pattern.CASE_INSENSITIVE);

        Matcher mat;
        for (String line : lines)
        {
            mat = patKey.matcher(line);
            if (mat.matches())
            {
                if (mat.groupCount() >= 1)
                {
                    hits.add(mat.group(1));
                }
                else
                {
                    hits.add(mat.group(0));
                }
            }
        }

        return hits;
    }

    public void respond(String rawstr) throws IOException
    {
        LOG.debug("respond(){}{}","\n",rawstr);
        getOutputStream().write(rawstr.getBytes());
        flush();
    }

    @Override
    public void run()
    {
        LOG.debug("Entering echo thread");

        ByteBuffer buf = bufferPool.acquire(BUFFER_SIZE,false);
        BufferUtil.clearToFill(buf);
        long readBytes = 0;
        try
        {
            while (echoing.get())
            {
                BufferUtil.clearToFill(buf);
                long len = read(buf);
                if (len > 0)
                {
                    readBytes += len;
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
            }
        }
        catch (IOException e)
        {
            LOG.debug("Exception during echo loop",e);
        }
        finally
        {
            LOG.debug("Read {} bytes",readBytes);
            bufferPool.release(buf);
        }
    }

    public void setSoTimeout(int ms) throws SocketException
    {
        socket.setSoTimeout(ms);
    }

    public void startEcho()
    {
        if (echoThread != null)
        {
            throw new IllegalStateException("Echo thread already declared!");
        }
        echoThread = new Thread(this,"BlockheadServer/Echo");
        echoing.set(true);
        echoThread.start();
    }

    public void stopEcho()
    {
        echoing.set(false);
    }

    public List<String> upgrade() throws IOException
    {
        List<String> requestLines = readRequestLines();
        List<ExtensionConfig> extensionConfigs = parseExtensions(requestLines);
        String key = parseWebSocketKey(requestLines);

        LOG.debug("Client Request Extensions: {}",extensionConfigs);
        LOG.debug("Client Request Key: {}",key);

        Assert.assertThat("Request: Sec-WebSocket-Key",key,notNullValue());

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
        resp.append("Connection: upgrade\r\n");
        resp.append("Content-Length: 0\r\n");
        resp.append("Sec-WebSocket-Accept: ");
        resp.append(AcceptHash.hashKey(key)).append("\r\n");
        if (extensionStack.hasNegotiatedExtensions())
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
        if (extraResponseHeaders.size() > 0)
        {
            for (Map.Entry<String, String> xheader : extraResponseHeaders.entrySet())
            {
                resp.append(xheader.getKey());
                resp.append(": ");
                resp.append(xheader.getValue());
                resp.append("\r\n");
            }
        }
        resp.append("\r\n");
        
        // Write Response
        LOG.debug("Response: {}",resp.toString());
        write(resp.toString().getBytes());
        return requestLines;
    }

    private void write(byte[] bytes) throws IOException
    {
        getOutputStream().write(bytes);
    }

    public void write(byte[] buf, int offset, int length) throws IOException
    {
        getOutputStream().write(buf,offset,length);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection#write(org.eclipse.jetty.websocket.api.extensions.Frame)
     */
    @Override
    public void write(Frame frame) throws IOException
    {
        LOG.debug("write(Frame->{}) to {}",frame,outgoing);
        outgoing.outgoingFrame(frame,null,BatchMode.OFF);
    }

    public void write(int b) throws IOException
    {
        getOutputStream().write(b);
    }

    public void write(ByteBuffer buf) throws IOException
    {
        byte arr[] = BufferUtil.toArray(buf);
        if ((arr != null) && (arr.length > 0))
        {
            getOutputStream().write(arr);
        }
    }
}
