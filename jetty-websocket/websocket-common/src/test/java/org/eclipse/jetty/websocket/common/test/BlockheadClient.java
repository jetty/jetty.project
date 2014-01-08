//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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
public class BlockheadClient implements IncomingFrames, OutgoingFrames, ConnectionStateListener, Closeable
{
    private static final String REQUEST_HASH_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final int BUFFER_SIZE = 8192;
    private static final Logger LOG = Log.getLogger(BlockheadClient.class);
    /** Set to true to disable timeouts (for debugging reasons) */
    private boolean debug = false;
    private final URI destHttpURI;
    private final URI destWebsocketURI;
    private final ByteBufferPool bufferPool;
    private final Generator generator;
    private final Parser parser;
    private final IncomingFramesCapture incomingFrames;
    private final WebSocketExtensionFactory extensionFactory;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int version = 13; // default to RFC-6455
    private String protocols;
    private List<String> extensions = new ArrayList<>();
    private List<String> headers = new ArrayList<>();
    private byte[] clientmask = new byte[]
    { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };
    private int timeout = 1000;
    private AtomicInteger parseCount;
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

        this.bufferPool = new MappedByteBufferPool(8192);
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy,bufferPool);
        this.parseCount = new AtomicInteger(0);

        this.incomingFrames = new IncomingFramesCapture();

        this.extensionFactory = new WebSocketExtensionFactory(policy,bufferPool);
        this.ioState = new IOState();
        this.ioState.addListener(this);
    }

    public void addExtensions(String xtension)
    {
        this.extensions.add(xtension);
    }

    public void addHeader(String header)
    {
        this.headers.add(header);
    }

    public boolean awaitDisconnect(long timeout, TimeUnit unit) throws InterruptedException
    {
        return disconnectedLatch.await(timeout,unit);
    }

    public void clearCaptured()
    {
        this.incomingFrames.clear();
    }

    public void clearExtensions()
    {
        extensions.clear();
    }

    public void close()
    {
        LOG.debug("close()");
        close(-1,null);
    }

    public void close(int statusCode, String message)
    {
        CloseInfo close = new CloseInfo(statusCode,message);

        ioState.onCloseLocal(close);

        if (!ioState.isClosed())
        {
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
        }
    }

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

        // Start with default routing
        extensionStack.setNextIncoming(this); // the websocket layer
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

    public IOState getIOState()
    {
        return ioState;
    }

    public String getProtocols()
    {
        return protocols;
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

    /**
     * Errors received (after extensions)
     */
    @Override
    public void incomingError(Throwable e)
    {
        incomingFrames.incomingError(e);
    }

    /**
     * Frames received (after extensions)
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        LOG.debug("incoming({})",frame);
        int count = parseCount.incrementAndGet();
        if ((count % 10) == 0)
        {
            LOG.info("Client parsed {} frames",count);
        }

        if (frame.getOpCode() == OpCode.CLOSE)
        {
            CloseInfo close = new CloseInfo(frame);
            ioState.onCloseRemote(close);
        }

        WebSocketFrame copy = WebSocketFrame.copy(frame);
        incomingFrames.incomingFrame(copy);
    }

    public boolean isConnected()
    {
        return (socket != null) && (socket.isConnected());
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        switch (state)
        {
            case CLOSED:
                // Per Spec, client should not initiate disconnect on its own
                // this.disconnect();
                break;
            case CLOSING:
                if (ioState.wasRemoteCloseInitiated())
                {
                    CloseInfo close = ioState.getCloseInfo();
                    close(close.getStatusCode(),close.getReason());
                }
                break;
            default:
                /* do nothing */
                break;
        }
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
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

    public int read(ByteBuffer buf) throws IOException
    {
        if (eof)
        {
            throw new EOFException("Hit EOF");
        }

        if ((remainingBuffer != null) && (remainingBuffer.remaining() > 0))
        {
            return BufferUtil.put(remainingBuffer,buf);
        }

        int len = -1;
        int b;
        while ((in.available() > 0) && (buf.remaining() > 0))
        {
            b = in.read();
            if (b == (-1))
            {
                eof = true;
                break;
            }
            buf.put((byte)b);
            len++;
        }

        return len;
    }

    public IncomingFramesCapture readFrames(int expectedCount, TimeUnit timeoutUnit, int timeoutDuration) throws IOException, TimeoutException
    {
        LOG.debug("Read: waiting for {} frame(s) from server",expectedCount);

        ByteBuffer buf = bufferPool.acquire(BUFFER_SIZE,false);
        BufferUtil.clearToFill(buf);
        try
        {
            long msDur = TimeUnit.MILLISECONDS.convert(timeoutDuration,timeoutUnit);
            long now = System.currentTimeMillis();
            long expireOn = now + msDur;
            LOG.debug("Now: {} - expireOn: {} ({} ms)",now,expireOn,msDur);

            long iter = 0;

            int len = 0;
            while (incomingFrames.size() < expectedCount)
            {
                BufferUtil.clearToFill(buf);
                len = read(buf);
                if (len > 0)
                {
                    BufferUtil.flipToFlush(buf,0);
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Read {} bytes: {}",len,BufferUtil.toDetailString(buf));
                    }
                    parser.parse(buf);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        iter++;
                        if ((iter % 10000000) == 0)
                        {
                            LOG.debug("10,000,000 reads of zero length");
                            iter = 0;
                        }
                    }
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

    public HttpResponse readResponseHeader() throws IOException
    {
        HttpResponse response = new HttpResponse();
        HttpResponseHeaderParser parser = new HttpResponseHeaderParser(response);

        ByteBuffer buf = BufferUtil.allocate(512);

        do
        {
            BufferUtil.flipToFill(buf);
            read(buf);
            BufferUtil.flipToFlush(buf,0);
        }
        while (parser.parse(buf) == null);

        remainingBuffer = response.getRemainingBuffer();

        return response;
    }

    public void sendStandardRequest() throws IOException
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
        writeRaw(req.toString());
    }

    public void setConnectionValue(String connectionValue)
    {
        this.connectionValue = connectionValue;
    }

    public void setDebug(boolean flag)
    {
        this.debug = flag;
    }

    public void setProtocols(String protocols)
    {
        this.protocols = protocols;
    }

    public void setTimeout(TimeUnit unit, int duration)
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

    public void write(WebSocketFrame frame) throws IOException
    {
        if (!ioState.isOpen())
        {
            return;
        }
        LOG.debug("write(Frame->{}) to {}",frame,outgoing);
        if (LOG.isDebugEnabled())
        {
            frame.setMask(new byte[]
            { 0x00, 0x00, 0x00, 0x00 });
        }
        else
        {
            frame.setMask(clientmask);
        }
        extensionStack.outgoingFrame(frame,null);
    }

    public void writeRaw(ByteBuffer buf) throws IOException
    {
        LOG.debug("write(ByteBuffer) {}",BufferUtil.toDetailString(buf));
        BufferUtil.writeTo(buf,out);
    }

    public void writeRaw(ByteBuffer buf, int numBytes) throws IOException
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        out.write(arr);
    }

    public void writeRaw(String str) throws IOException
    {
        LOG.debug("write((String)[{}]){}{})",str.length(),'\n',str);
        out.write(str.getBytes(StandardCharsets.ISO_8859_1));
    }

    public void writeRawSlowly(ByteBuffer buf, int segmentSize) throws IOException
    {
        while (buf.remaining() > 0)
        {
            writeRaw(buf,segmentSize);
            flush();
        }
    }
}
