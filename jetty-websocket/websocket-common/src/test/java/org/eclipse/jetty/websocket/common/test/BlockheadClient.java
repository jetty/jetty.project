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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;
import org.eclipse.jetty.websocket.common.io.http.HttpResponseHeaderParser;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.junit.Assert;

/**
 * A simple websocket client for performing unit tests with.
 * <p>
 * This client will use {@link HttpURLConnection} and {@link HttpsURLConnection} with standard blocking calls to perform websocket requests.
 * <p>
 * This client is <u>NOT</u> intended to be performant or follow the websocket spec religiously. In fact, being able to deviate from the websocket spec at will
 * is desired for this client to operate properly for the unit testing within this module.
 * <p>
 * The BlockheadClient should never validate frames or bytes being sent for validity, against any sort of spec, or even sanity. It should, however be honest
 * with regards to basic IO behavior, a write should work as expected, a read should work as expected, but <u>what</u> byte it sends or reads is not within its
 * scope.
 */
public class BlockheadClient implements OutgoingFrames, ConnectionStateListener, AutoCloseable, IBlockheadClient
{
    private class FrameReadingThread extends Thread implements Runnable, IncomingFrames
    {
        public long totalBytes = 0;
        public long totalReadOps = 0;
        public long totalParseOps = 0;

        public EventQueue<WebSocketFrame> frames = new EventQueue<>();
        public EventQueue<Throwable> errors = new EventQueue<>();

        @Override
        public void run()
        {
            LOG.debug("Reading frames from server");

            byte buf[] = new byte[BUFFER_SIZE];
            try
            {
                if ((remainingBuffer != null) && (remainingBuffer.remaining() > 0))
                {
                    LOG.debug("Reading bytes received during response header parse: {}",BufferUtil.toDetailString(remainingBuffer));
                    totalBytes += remainingBuffer.remaining();
                    totalReadOps++;
                    parser.parse(remainingBuffer);
                }

                int len = 0;
                int available = 0;
                while (!eof)
                {
                    available = in.available();
                    len = in.read(buf,0,Math.min(available,buf.length));
                    totalReadOps++;
                    if (len < 0)
                    {
                        eof = true;
                        break;
                    }
                    else if (len > 0)
                    {
                        totalBytes += len;
                        ByteBuffer bbuf = ByteBuffer.wrap(buf,0,len);
                        if (LOG.isDebugEnabled())
                        {
                            LOG.debug("Read {} bytes: {}",len,BufferUtil.toDetailString(bbuf));
                        }
                        totalParseOps++;
                        parser.parse(bbuf);
                    }
                }
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
        }

        @Override
        public String toString()
        {
            StringBuilder str = new StringBuilder();
            str.append("FrameReadingThread[");
            str.append(",frames=" + frames.size());
            str.append(",errors=" + errors.size());
            str.append(String.format(",totalBytes=%,d",totalBytes));
            str.append(String.format(",totalReadOps=%,d",totalReadOps));
            str.append(String.format(",totalParseOps=%,d",totalParseOps));
            str.append("]");
            return str.toString();
        }

        @Override
        public synchronized void incomingError(Throwable t)
        {
            this.errors.add(t);
        }

        @Override
        public synchronized void incomingFrame(Frame frame)
        {
            this.frames.add(WebSocketFrame.copy(frame));
        }

        public synchronized void clear()
        {
            this.frames.clear();
            this.errors.clear();
        }
    }

    private static final String REQUEST_HASH_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Logger LOG = Log.getLogger(BlockheadClient.class);
    private final URI destHttpURI;
    private final URI destWebsocketURI;
    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;

    private final WebSocketExtensionFactory extensionFactory;
    private FrameReadingThread frameReader;

    private ExecutorService executor;
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int version = 13; // default to RFC-6455
    private String protocols;
    private List<String> extensions = new ArrayList<>();
    private List<String> headers = new ArrayList<>();
    private byte[] clientmask = new byte[] { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };
    private int timeout = 1000;
    private OutgoingFrames outgoing = this;
    private boolean eof = false;
    private ExtensionStack extensionStack;
    private IOState ioState;
    private CountDownLatch disconnectedLatch = new CountDownLatch(1);
    private ByteBuffer remainingBuffer;

    private String connectionValue = "Upgrade";

    public BlockheadClient(URI destWebsocketURI) throws URISyntaxException
    {
        this(WebSocketPolicy.newClientPolicy(),destWebsocketURI);
    }

    public BlockheadClient(WebSocketPolicy policy, URI destWebsocketURI) throws URISyntaxException
    {
        Assert.assertThat("Websocket URI scheme",destWebsocketURI.getScheme(),anyOf(is("ws"),is("wss")));
        this.destWebsocketURI = destWebsocketURI;
        if (destWebsocketURI.getScheme().equals("wss"))
        {
            throw new RuntimeException("Sorry, BlockheadClient does not support SSL");
        }
        this.destHttpURI = WSURI.toHttp(destWebsocketURI);

        LOG.debug("WebSocket URI: {}",destWebsocketURI);
        LOG.debug("     HTTP URI: {}",destHttpURI);

        // This is a blockhead client, no point tracking leaks on this object.
        this.bufferPool = new MappedByteBufferPool(8192);
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy,bufferPool);

        this.extensionFactory = new WebSocketExtensionFactory(new SimpleContainerScope(policy,bufferPool));
        this.ioState = new IOState();
        this.ioState.addListener(this);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#addExtensions(java.lang.String)
     */
    @Override
    public void addExtensions(String xtension)
    {
        this.extensions.add(xtension);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#addHeader(java.lang.String)
     */
    @Override
    public void addHeader(String header)
    {
        this.headers.add(header);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#awaitDisconnect(long, java.util.concurrent.TimeUnit)
     */
    @Override
    public boolean awaitDisconnect(long timeout, TimeUnit unit) throws InterruptedException
    {
        return disconnectedLatch.await(timeout,unit);
    }

    public void clearCaptured()
    {
        frameReader.clear();
    }

    public void clearExtensions()
    {
        extensions.clear();
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#close()
     */
    @Override
    public void close()
    {
        LOG.debug("close()");
        close(-1,null);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#close(int, java.lang.String)
     */
    @Override
    public void close(int statusCode, String message)
    {
        LOG.debug("close({},{})",statusCode,message);
        CloseInfo close = new CloseInfo(statusCode,message);

        if (!ioState.isClosed())
        {
            ioState.onCloseLocal(close);
        }
        else
        {
            LOG.debug("Not issuing close. ioState = {}",ioState);
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#connect()
     */
    @Override
    public void connect() throws IOException
    {
        InetAddress destAddr = InetAddress.getByName(destHttpURI.getHost());
        int port = destHttpURI.getPort();

        SocketAddress endpoint = new InetSocketAddress(destAddr,port);

        socket = new Socket();
        socket.setSoTimeout(timeout);
        socket.connect(endpoint);

        out = socket.getOutputStream();
        in = socket.getInputStream();
    }

    public void disconnect()
    {
        LOG.debug("disconnect");
        IO.close(in);
        IO.close(out);
        disconnectedLatch.countDown();
        if (frameReader != null)
        {
            frameReader.interrupt();
        }
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

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#expectServerDisconnect()
     */
    @Override
    public void expectServerDisconnect()
    {
        if (eof)
        {
            return;
        }

        try
        {
            int len = in.read();
            if (len == (-1))
            {
                // we are disconnected
                eof = true;
                return;
            }

            Assert.assertThat("Expecting no data and proper socket disconnect (issued from server)",len,is(-1));
        }
        catch (SocketTimeoutException e)
        {
            LOG.warn(e);
            Assert.fail("Expected a server initiated disconnect, instead the read timed out");
        }
        catch (IOException e)
        {
            // acceptable path
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#expectUpgradeResponse()
     */
    @Override
    public HttpResponse expectUpgradeResponse() throws IOException
    {
        HttpResponse response = readResponseHeader();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Response Header: {}{}",'\n',response);
        }

        Assert.assertThat("Response Status Code",response.getStatusCode(),is(101));
        Assert.assertThat("Response Status Reason",response.getStatusReason(),is("Switching Protocols"));
        Assert.assertThat("Response Header[Upgrade]",response.getHeader("Upgrade"),is("WebSocket"));
        Assert.assertThat("Response Header[Connection]",response.getHeader("Connection"),is("Upgrade"));

        // Validate the Sec-WebSocket-Accept
        String acceptKey = response.getHeader("Sec-WebSocket-Accept");
        Assert.assertThat("Response Header[Sec-WebSocket-Accept Exists]",acceptKey,notNullValue());

        String reqKey = REQUEST_HASH_KEY;
        String expectedHash = AcceptHash.hashKey(reqKey);

        Assert.assertThat("Valid Sec-WebSocket-Accept Hash?",acceptKey,is(expectedHash));

        // collect extensions configured in response header
        List<ExtensionConfig> configs = getExtensionConfigs(response);
        extensionStack = new ExtensionStack(this.extensionFactory);
        extensionStack.negotiate(configs);

        // Setup Frame Reader
        this.frameReader = new FrameReadingThread();
        this.frameReader.start();

        // Start with default routing
        extensionStack.setNextIncoming(frameReader); // the websocket layer
        extensionStack.setNextOutgoing(outgoing); // the network layer

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

        // configure parser
        parser.setIncomingFramesHandler(extensionStack);
        ioState.onOpened();

        LOG.debug("outgoing = {}",outgoing);
        LOG.debug("incoming = {}",extensionStack);

        return response;
    }

    public void flush() throws IOException
    {
        out.flush();
    }

    public String getConnectionValue()
    {
        return connectionValue;
    }

    public ExecutorService getExecutor()
    {
        if (executor == null)
        {
            executor = Executors.newCachedThreadPool();
        }
        return executor;
    }

    private List<ExtensionConfig> getExtensionConfigs(HttpResponse response)
    {
        List<ExtensionConfig> configs = new ArrayList<>();

        String econf = response.getHeader("Sec-WebSocket-Extensions");
        if (econf != null)
        {
            LOG.debug("Found Extension Response: {}",econf);
            ExtensionConfig config = ExtensionConfig.parse(econf);
            configs.add(config);
        }
        return configs;
    }

    public List<String> getExtensions()
    {
        return extensions;
    }

    public URI getHttpURI()
    {
        return destHttpURI;
    }

    public InetSocketAddress getLocalSocketAddress()
    {
        return (InetSocketAddress)socket.getLocalSocketAddress();
    }

    public IOState getIOState()
    {
        return ioState;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#getProtocols()
     */
    @Override
    public String getProtocols()
    {
        return protocols;
    }
    

    public InetSocketAddress getRemoteSocketAddress()
    {
        return (InetSocketAddress)socket.getRemoteSocketAddress();
    }

    public String getRequestHost()
    {
        if (destHttpURI.getPort() > 0)
        {
            return String.format("%s:%d",destHttpURI.getHost(),destHttpURI.getPort());
        }
        else
        {
            return destHttpURI.getHost();
        }
    }

    public String getRequestPath()
    {
        StringBuilder path = new StringBuilder();
        path.append(destHttpURI.getPath());
        if (StringUtil.isNotBlank(destHttpURI.getQuery()))
        {
            path.append('?').append(destHttpURI.getQuery());
        }
        return path.toString();
    }

    public String getRequestWebSocketKey()
    {
        return REQUEST_HASH_KEY;
    }

    public String getRequestWebSocketOrigin()
    {
        return destWebsocketURI.toASCIIString();
    }

    public int getVersion()
    {
        return version;
    }

    public URI getWebsocketURI()
    {
        return destWebsocketURI;
    }

    public boolean isConnected()
    {
        return (socket != null) && (socket.isConnected());
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        LOG.debug("CLIENT onConnectionStateChange() - {}",state);
        switch (state)
        {
            case CLOSED:
                // Per Spec, client should not initiate disconnect on its own
                // this.disconnect();
                break;
            case CLOSING:
                CloseInfo close = ioState.getCloseInfo();

                WebSocketFrame frame = close.asFrame();
                LOG.debug("Issuing: {}",frame);
                try
                {
                    write(frame);
                }
                catch (IOException e)
                {
                    LOG.debug(e);
                }
                break;
            default:
                /* do nothing */
                break;
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
            BufferUtil.writeTo(frame.getPayload(),out);
            out.flush();
            if (callback != null)
            {
                callback.writeSuccess();
            }
        }
        catch (IOException e)
        {
            if (callback != null)
            {
                callback.writeFailed(e);
            }
        }
        finally
        {
            bufferPool.release(headerBuf);
        }

        if (frame.getOpCode() == OpCode.CLOSE)
        {
            disconnect();
        }
    }

    public EventQueue<WebSocketFrame> readFrames(int expectedFrameCount, int timeoutDuration, TimeUnit timeoutUnit) throws Exception
    {
        frameReader.frames.awaitEventCount(expectedFrameCount,timeoutDuration,timeoutUnit);
        return frameReader.frames;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#readResponseHeader()
     */
    @Override
    public HttpResponse readResponseHeader() throws IOException
    {
        HttpResponse response = new HttpResponse();
        HttpResponseHeaderParser respParser = new HttpResponseHeaderParser(response);

        byte buf[] = new byte[512];

        while (!eof)
        {
            int available = in.available();
            int len = in.read(buf,0,Math.min(available,buf.length));
            if (len < 0)
            {
                eof = true;
                break;
            }
            else if (len > 0)
            {
                ByteBuffer bbuf = ByteBuffer.wrap(buf,0,len);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Read {} bytes: {}",len,BufferUtil.toDetailString(bbuf));
                }
                if (respParser.parse(bbuf) != null)
                {
                    break;
                }
            }
        }

        remainingBuffer = response.getRemainingBuffer();

        return response;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#sendStandardRequest()
     */
    @Override
    public void sendStandardRequest() throws IOException
    {
        StringBuilder req = generateUpgradeRequest();
        writeRaw(req.toString());
    }

    public StringBuilder generateUpgradeRequest()
    {
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(getRequestPath()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(getRequestHost()).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("User-Agent: BlockheadClient/JettyTests\r\n");
        req.append("Connection: ").append(connectionValue).append("\r\n");
        for (String header : headers)
        {
            req.append(header);
        }
        req.append("Sec-WebSocket-Key: ").append(getRequestWebSocketKey()).append("\r\n");
        req.append("Sec-WebSocket-Origin: ").append(getRequestWebSocketOrigin()).append("\r\n");
        if (StringUtil.isNotBlank(protocols))
        {
            req.append("Sec-WebSocket-Protocol: ").append(protocols).append("\r\n");
        }

        for (String xtension : extensions)
        {
            req.append("Sec-WebSocket-Extensions: ").append(xtension).append("\r\n");
        }
        req.append("Sec-WebSocket-Version: ").append(version).append("\r\n");
        req.append("\r\n");
        return req;
    }

    public void setConnectionValue(String connectionValue)
    {
        this.connectionValue = connectionValue;
    }

    public void setExecutor(ExecutorService executor)
    {
        this.executor = executor;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#setProtocols(java.lang.String)
     */
    @Override
    public void setProtocols(String protocols)
    {
        this.protocols = protocols;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#setTimeout(int, java.util.concurrent.TimeUnit)
     */
    @Override
    public void setTimeout(int duration, TimeUnit unit)
    {
        this.timeout = (int)TimeUnit.MILLISECONDS.convert(duration,unit);
    }

    public void setVersion(int version)
    {
        this.version = version;
    }

    public void skipTo(String string) throws IOException
    {
        int state = 0;

        while (true)
        {
            int b = in.read();
            if (b < 0)
            {
                throw new EOFException();
            }

            if (b == string.charAt(state))
            {
                state++;
                if (state == string.length())
                {
                    break;
                }
            }
            else
            {
                state = 0;
            }
        }
    }

    public void sleep(TimeUnit unit, int duration) throws InterruptedException
    {
        LOG.info("Sleeping for {} {}",duration,unit);
        unit.sleep(duration);
        LOG.info("Waking up from sleep");
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#write(org.eclipse.jetty.websocket.common.WebSocketFrame)
     */
    @Override
    public void write(WebSocketFrame frame) throws IOException
    {
        if (!ioState.isOpen())
        {
            LOG.debug("IO Not Open / Not Writing: {}",frame);
            return;
        }
        LOG.debug("write(Frame->{}) to {}",frame,outgoing);
        if (LOG.isDebugEnabled())
        {
            frame.setMask(new byte[] { 0x00, 0x00, 0x00, 0x00 });
        }
        else
        {
            frame.setMask(clientmask);
        }
        extensionStack.outgoingFrame(frame,null,BatchMode.OFF);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#writeRaw(java.nio.ByteBuffer)
     */
    @Override
    public void writeRaw(ByteBuffer buf) throws IOException
    {
        LOG.debug("write(ByteBuffer) {}",BufferUtil.toDetailString(buf));
        BufferUtil.writeTo(buf,out);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#writeRaw(java.nio.ByteBuffer, int)
     */
    @Override
    public void writeRaw(ByteBuffer buf, int numBytes) throws IOException
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        out.write(arr);
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#writeRaw(java.lang.String)
     */
    @Override
    public void writeRaw(String str) throws IOException
    {
        LOG.debug("write((String)[{}]){}{})",str.length(),'\n',str);
        out.write(str.getBytes(StandardCharsets.ISO_8859_1));
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.websocket.common.test.IBlockheadClient#writeRawSlowly(java.nio.ByteBuffer, int)
     */
    @Override
    public void writeRawSlowly(ByteBuffer buf, int segmentSize) throws IOException
    {
        while (buf.remaining() > 0)
        {
            writeRaw(buf,segmentSize);
            flush();
        }
    }
}
