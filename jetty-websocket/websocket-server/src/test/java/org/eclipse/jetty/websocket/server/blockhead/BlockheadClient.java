package org.eclipse.jetty.websocket.server.blockhead;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

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
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;

/**
 * A simple websocket client for performing unit tests with.
 * <p>
 * This client will use {@link HttpURLConnection} and {@link HttpsURLConnection} with standard blocking calls to perform websocket requests.
 * <p>
 * This client is <u>NOT</u> intended to be performant or follow the websocket spec religiously. In fact, being able to deviate from the websocket spec at will
 * is desired for this client to operate properly for the unit testing within this module.
 */
public class BlockheadClient implements Parser.Listener
{
    private static final Logger LOG = Log.getLogger(BlockheadClient.class);
    /** Set to true to disable timeouts (for debugging reasons) */
    private static final boolean DEBUG = false;
    private final URI destHttpURI;
    private final URI destWebsocketURI;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final Generator generator;
    private final Parser parser;
    private final LinkedBlockingDeque<BaseFrame> incomingFrameQueue;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int version = 13; // default to RFC-6455
    private String protocols;
    private String extensions;
    private byte[] clientmask = new byte[]
    { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };

    public BlockheadClient(URI destWebsocketURI) throws URISyntaxException
    {
        Assert.assertThat("Websocket URI scheme",destWebsocketURI.getScheme(),anyOf(is("ws"),is("wss")));
        this.destWebsocketURI = destWebsocketURI;
        String scheme = "http";
        if (destWebsocketURI.getScheme().equals("wss"))
        {
            scheme = "https";
        }
        this.destHttpURI = new URI(scheme,destWebsocketURI.getSchemeSpecificPart(),destWebsocketURI.getFragment());

        policy = WebSocketPolicy.newClientPolicy();
        bufferPool = new StandardByteBufferPool(policy.getBufferSize());
        generator = new Generator(policy);
        parser = new Parser(policy);
        parser.addListener(this);

        incomingFrameQueue = new LinkedBlockingDeque<>();
    }

    public void close()
    {
        IO.close(in);
        IO.close(out);
        try
        {
            socket.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public void connect() throws IOException
    {
        InetAddress destAddr = InetAddress.getByName(destHttpURI.getHost());
        int port = destHttpURI.getPort();
        socket = new Socket(destAddr,port);

        out = socket.getOutputStream();
        // socket.setSoTimeout(1000);
        in = socket.getInputStream();
    }

    public String expectUpgradeResponse() throws IOException
    {
        String respHeader = readResponseHeader();
        Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 101 Switching Protocols"));
        Assert.assertThat("Response Header Upgrade",respHeader,containsString("Upgrade: WebSocket\r\n"));
        Assert.assertThat("Response Header Connection",respHeader,containsString("Connection: Upgrade\r\n"));
        return respHeader;
    }

    public String getExtensions()
    {
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
    public void onFrame(BaseFrame frame)
    {
        LOG.debug("onFrame({})",frame);
        if (!incomingFrameQueue.offerLast(frame))
        {
            throw new RuntimeException("Unable to queue incoming frame: " + frame);
        }
    }

    @Override
    public void onWebSocketException(WebSocketException e)
    {
        LOG.warn(e);
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

    public Queue<BaseFrame> readFrames(int expectedCount, TimeUnit timeoutUnit, int timeoutDuration) throws IOException, TimeoutException
    {
        int startCount = incomingFrameQueue.size();

        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clearToFill(buf);
        try
        {
            long now = System.currentTimeMillis();
            long expireOn = now + TimeUnit.MILLISECONDS.convert(timeoutDuration,timeoutUnit);
            LOG.debug("Now: {} - expireOn: {}",now,expireOn);

            int len = 0;
            while (incomingFrameQueue.size() < (startCount + expectedCount))
            {
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
                if (!DEBUG && (System.currentTimeMillis() > expireOn))
                {
                    throw new TimeoutException("Timeout reading all of the desired frames");
                }
            }
        }
        finally
        {
            bufferPool.release(buf);
        }

        return incomingFrameQueue;
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
        if (StringUtil.isNotBlank(extensions))
        {
            req.append("Sec-WebSocket-Extensions: ").append(extensions).append("\r\n");
        }
        req.append("Sec-WebSocket-Version: ").append(version).append("\r\n");
        req.append("\r\n");
        writeRaw(req.toString());
    }

    public void setExtensions(String extensions)
    {
        this.extensions = extensions;
    }

    public void setProtocols(String protocols)
    {
        this.protocols = protocols;
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

    public void write(BaseFrame frame) throws IOException
    {
        LOG.debug("write(BaseFrame->{})",frame);
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            frame.setMask(clientmask);
            BufferUtil.flipToFill(buf);
            generator.generate(buf,frame);
            BufferUtil.flipToFlush(buf,0);
            BufferUtil.writeTo(buf,out);
        }
        finally
        {
            bufferPool.release(buf);
        }
    }

    public void writeRaw(ByteBuffer buf) throws IOException
    {
        LOG.debug("write(ByteBuffer->{})",BufferUtil.toDetailString(buf));
        BufferUtil.writeTo(buf,out);
    }

    public void writeRaw(String str) throws IOException
    {
        LOG.debug("write(String->{})",str);
        out.write(StringUtil.getBytes(str,StringUtil.__ISO_8859_1));
    }
}
