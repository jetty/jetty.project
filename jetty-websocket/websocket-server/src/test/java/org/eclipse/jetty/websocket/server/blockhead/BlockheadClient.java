// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server.blockhead;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
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

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
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
public class BlockheadClient implements IncomingFrames, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(BlockheadClient.class);
    /** Set to true to disable timeouts (for debugging reasons) */
    private boolean debug = false;
    private final URI destHttpURI;
    private final URI destWebsocketURI;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final Generator generator;
    private final Parser parser;
    private final IncomingFramesCapture incomingFrames;
    private final WebSocketExtensionRegistry extensionRegistry;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int version = 13; // default to RFC-6455
    private String protocols;
    private List<String> extensions = new ArrayList<>();
    private byte[] clientmask = new byte[]
    { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };
    private int timeout = 1000;
    private AtomicInteger parseCount;
    private IncomingFrames incoming = this;
    private OutgoingFrames outgoing = this;

    public BlockheadClient(URI destWebsocketURI) throws URISyntaxException
    {
        this(WebSocketPolicy.newClientPolicy(),destWebsocketURI);
    }

    public BlockheadClient(WebSocketPolicy policy, URI destWebsocketURI) throws URISyntaxException
    {
        Assert.assertThat("Websocket URI scheme",destWebsocketURI.getScheme(),anyOf(is("ws"),is("wss")));
        this.destWebsocketURI = destWebsocketURI;
        String scheme = "http";
        if (destWebsocketURI.getScheme().equals("wss"))
        {
            scheme = "https";
        }
        this.destHttpURI = new URI(scheme,destWebsocketURI.getSchemeSpecificPart(),destWebsocketURI.getFragment());

        this.policy = policy;
        this.bufferPool = new StandardByteBufferPool(policy.getBufferSize());
        this.generator = new Generator(policy,bufferPool);
        this.parser = new Parser(policy);
        this.parseCount = new AtomicInteger(0);

        this.incomingFrames = new IncomingFramesCapture();

        this.extensionRegistry = new WebSocketExtensionRegistry(policy,bufferPool);
    }

    public void addExtensions(String xtension)
    {
        this.extensions.add(xtension);
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
        try
        {
            CloseInfo close = new CloseInfo(statusCode,message);
            WebSocketFrame frame = close.asFrame();
            LOG.debug("Issuing: {}",frame);
            write(frame);
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
    }

    public void connect() throws IOException
    {
        InetAddress destAddr = InetAddress.getByName(destHttpURI.getHost());
        int port = destHttpURI.getPort();
        socket = new Socket(destAddr,port);

        out = socket.getOutputStream();
        socket.setSoTimeout(timeout);
        in = socket.getInputStream();
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

    public String expectUpgradeResponse() throws IOException
    {
        String respHeader = readResponseHeader();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Response Header: {}{}",'\n',respHeader);
        }

        Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 101 Switching Protocols"));
        Assert.assertThat("Response Header Upgrade",respHeader,containsString("Upgrade: WebSocket\r\n"));
        Assert.assertThat("Response Header Connection",respHeader,containsString("Connection: Upgrade\r\n"));

        // collect extensions configured in response header
        List<Extension> extensions = getExtensions(respHeader);

        // Start with default routing
        incoming = this;
        outgoing = this;

        // Connect extensions
        if (extensions != null)
        {
            Iterator<Extension> extIter;
            // Connect outgoings
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextOutgoingFrames(outgoing);
                outgoing = ext;

                // Handle RSV reservations
                if (ext.useRsv1())
                {
                    generator.setRsv1InUse(true);
                }
                if (ext.useRsv2())
                {
                    generator.setRsv2InUse(true);
                }
                if (ext.useRsv3())
                {
                    generator.setRsv3InUse(true);
                }
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

        // configure parser
        parser.setIncomingFramesHandler(incoming);

        return respHeader;
    }

    public void flush() throws IOException
    {
        out.flush();
    }

    public List<String> getExtensions()
    {
        return extensions;
    }

    private List<Extension> getExtensions(String respHeader)
    {
        List<Extension> extensions = new ArrayList<>();

        Pattern expat = Pattern.compile("Sec-WebSocket-Extensions: (.*)\r",Pattern.CASE_INSENSITIVE);
        Matcher mat = expat.matcher(respHeader);
        int offset = 0;
        while (mat.find(offset))
        {
            String econf = mat.group(1);
            LOG.debug("Found Extension Response: {}",econf);

            ExtensionConfig config = ExtensionConfig.parse(econf);
            Extension ext = extensionRegistry.newInstance(config);
            extensions.add(ext);

            offset = mat.end(1);
        }
        return extensions;
    }

    public URI getHttpURI()
    {
        return destHttpURI;
    }

    public String getProtocols()
    {
        return protocols;
    }

    public int getVersion()
    {
        return version;
    }

    public URI getWebsocketURI()
    {
        return destWebsocketURI;
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
            LOG.info("Client parsed {} frames",count);
        }
        WebSocketFrame copy = new WebSocketFrame(frame);
        incomingFrames.incoming(copy);
    }

    public boolean isConnected()
    {
        return (socket != null) && (socket.isConnected());
    }

    public void lookFor(String string) throws IOException
    {
        String orig = string;
        Utf8StringBuilder scanned = new Utf8StringBuilder();
        try
        {
            while (true)
            {
                int b = in.read();
                if (b < 0)
                {
                    throw new EOFException();
                }
                scanned.append((byte)b);
                assertEquals("looking for\"" + orig + "\" in '" + scanned + "'",string.charAt(0),b);
                if (string.length() == 1)
                {
                    break;
                }
                string = string.substring(1);
            }
        }
        catch (IOException e)
        {
            System.err.println("IOE while looking for \"" + orig + "\" in '" + scanned + "'");
            throw e;
        }
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

        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
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

    public String readResponseHeader() throws IOException
    {
        InputStreamReader isr = new InputStreamReader(in);
        BufferedReader reader = new BufferedReader(isr);
        StringBuilder header = new StringBuilder();
        // Read the response header
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertThat(line,startsWith("HTTP/1.1 "));
        header.append(line).append("\r\n");
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
            {
                break;
            }
            header.append(line).append("\r\n");
        }
        return header.toString();
    }

    public void sendStandardRequest() throws IOException
    {
        StringBuilder req = new StringBuilder();
        req.append("GET /chat HTTP/1.1\r\n");
        req.append("Host: ").append(destHttpURI.getHost());
        if (destHttpURI.getPort() > 0)
        {
            req.append(':').append(destHttpURI.getPort());
        }
        req.append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
        req.append("Sec-WebSocket-Origin: ").append(destWebsocketURI.toASCIIString()).append("\r\n");
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
        LOG.debug("write(Frame->{}) to {}",frame,outgoing);
        frame.setMask(clientmask);
        // frame.setMask(new byte[] { 0x00, 0x00, 0x00, 0x00 });
        outgoing.output(null,null,frame);
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
        out.write(StringUtil.getBytes(str,StringUtil.__ISO_8859_1));
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
