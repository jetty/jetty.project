package org.eclipse.jetty.server.ssl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Test;

public class SslRenegotiateTest
{
    private static final Logger LOG = Log.getLogger(SslRenegotiateTest.class);

    private static final TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager()
    {
        public java.security.cert.X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }

        public void checkClientTrusted( java.security.cert.X509Certificate[] certs, String authType )
        {
        }

        public void checkServerTrusted( java.security.cert.X509Certificate[] certs, String authType )
        {
        }
    } };

    private ByteBuffer _outAppB;
    private ByteBuffer _outPacketB;
    private ByteBuffer _inAppB;
    private ByteBuffer _inPacketB;
    private SocketChannel _socket;
    private SSLEngine _engine;

    @Test
    public void testRenegNIO() throws Exception
    {
        // TODO This test breaks on JVMs with the fix
//        doRequests(new SslSelectChannelConnector(),true);
    }

    @Test
    public void testNoRenegNIO() throws Exception
    {
        doRequests(new SslSelectChannelConnector(),false);
    }

    @Test
    public void testRenegBIO() throws Exception
    {
        // TODO - this test is too non deterministic due to call back timing
//        doRequests(new SslSocketConnector(),true);
    }

    @Test
    public void testNoRenegBIO() throws Exception
    {
        // TODO - this test is too non deterministic due to call back timing
//        doRequests(new SslSocketConnector(),false);
    }

    private void doRequests(SslConnector connector, boolean reneg) throws Exception
    {
        Server server=new Server();
        try
        {
            String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
            connector.setPort(0);
            SslContextFactory cf = connector.getSslContextFactory();
            cf.setKeyStorePath(keystore);
            cf.setKeyStorePassword("storepwd");
            cf.setKeyManagerPassword("keypwd");
            cf.setAllowRenegotiate(reneg);

            server.setConnectors(new Connector[] { connector });
            server.setHandler(new HelloWorldHandler());

            server.start();

            SocketAddress addr = new InetSocketAddress("localhost",connector.getLocalPort());
            _socket = SocketChannel.open(addr);
            _socket.configureBlocking(true);

            SSLContext context=SSLContext.getInstance("SSL");
            context.init( null, trustAllCerts, new java.security.SecureRandom() );

            _engine = context.createSSLEngine();
            _engine.setUseClientMode(true);
            SSLSession session=_engine.getSession();

            _outAppB = ByteBuffer.allocate(session.getApplicationBufferSize());
            _outPacketB = ByteBuffer.allocate(session.getPacketBufferSize());
            _inAppB = ByteBuffer.allocate(session.getApplicationBufferSize());
            _inPacketB = ByteBuffer.allocate(session.getPacketBufferSize());


            _outAppB.put("GET /1 HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StringUtil.__ISO_8859_1));
            _outAppB.flip();

            _engine.beginHandshake();

            runHandshake();

            doWrap();
            doUnwrap();
            _inAppB.flip();
            String response=new IndirectNIOBuffer(_inAppB,true).toString();
            // System.err.println(response);
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));

            if (response.indexOf("HELLO WORLD")<0)
            {
                _inAppB.clear();
                doUnwrap();
                _inAppB.flip();
                response=new IndirectNIOBuffer(_inAppB,true).toString();
            }

            assertTrue(response.indexOf("HELLO WORLD")>=0);

            _inAppB.clear();
            _outAppB.clear();
            _outAppB.put("GET /2 HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StringUtil.__ISO_8859_1));
            _outAppB.flip();

            try
            {
                session.invalidate();
                _engine.beginHandshake();
                runHandshake();

                doWrap();
                doUnwrap();
                _inAppB.flip();
                response=new IndirectNIOBuffer(_inAppB,true).toString();
                assertTrue(response.startsWith("HTTP/1.1 200 OK"));
                assertTrue(response.indexOf("HELLO WORLD")>0);

                assertTrue(reneg);
            }
            catch(IOException e)
            {
                if (!(e instanceof SSLProtocolException))
                {
                    if (reneg)
                        LOG.warn(e);
                    assertFalse(reneg);
                }
            }
        }
        finally
        {
            server.stop();
            server.join();
        }
    }

    void runHandshake() throws Exception
    {
        while (true)
        {
            switch(_engine.getHandshakeStatus())
            {
                case NEED_TASK:
                {
                    //System.err.println("running task");
                    _engine.getDelegatedTask().run();
                    break;
                }

                case NEED_WRAP:
                {
                    doWrap();
                    break;
                }

                case NEED_UNWRAP:
                {
                    doUnwrap();
                    break;
                }

                default:
                    return;
            }
        }
    }

    private void doWrap() throws Exception
    {
        _engine.wrap(_outAppB,_outPacketB);
//        System.err.println("wrapped "+result.bytesConsumed()+" to "+result.bytesProduced());
        _outPacketB.flip();
        while (_outPacketB.hasRemaining())
        {
            int p = _outPacketB.remaining();
            int l =_socket.write(_outPacketB);
            // System.err.println("wrote "+l+" of "+p);
        }
        _outPacketB.clear();
    }

    private void doUnwrap() throws Exception
    {
        _inPacketB.clear();
        int l=_socket.read(_inPacketB);
        // System.err.println("read "+l);
        if (l<0)
            throw new IOException("EOF");

        _inPacketB.flip();

        SSLEngineResult result;
        do
        {
            result =_engine.unwrap(_inPacketB,_inAppB);
//            System.err.println("unwrapped "+result.bytesConsumed()+" to "+result.bytesProduced()+" "+_engine.getHandshakeStatus());

        }
        while(result.bytesConsumed()>0 &&
              _inPacketB.remaining()>0 &&
              (_engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP || _engine.getHandshakeStatus()==HandshakeStatus.NOT_HANDSHAKING));
    }

    private static class HelloWorldHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            //System.err.println("HELLO WORLD HANDLING");

//            System.err.println("hello "+baseRequest.getUri());
            byte[] b=("HELLO WORLD "+baseRequest.getUri()).getBytes(StringUtil.__UTF8);
            response.setContentLength(b.length);
            response.getOutputStream().write(b);
            response.getOutputStream().flush();
        }
    }
}
