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

package org.eclipse.jetty.server.ssl;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/* ------------------------------------------------------------ */
/**
 * SSL Socket Connector.
 *
 * This specialization of SocketConnector is an abstract listener that can be used as the basis for a
 * specific JSSE listener.
 *
 * The original of this class was heavily based on the work from Court Demas, which in turn is
 * based on the work from Forge Research. Since JSSE, this class has evolved significantly from
 * that early work.
 *
 * @org.apache.xbean.XBean element="sslSocketConnector" description="Creates an ssl socket connector"
 *
 *
 */
public class SslSocketConnector extends SocketConnector  implements SslConnector
{
    private static final Logger LOG = Log.getLogger(SslSocketConnector.class);

    private final SslContextFactory _sslContextFactory;
    private int _handshakeTimeout = 0; //0 means use maxIdleTime

    /* ------------------------------------------------------------ */
    /**
     * Constructor.
     */
    public SslSocketConnector()
    {
        this(new SslContextFactory(SslContextFactory.DEFAULT_KEYSTORE_PATH));
        setSoLingerTime(30000);
    }

    /* ------------------------------------------------------------ */
    public SslSocketConnector(SslContextFactory sslContextFactory)
    {
        _sslContextFactory = sslContextFactory;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     */
    public boolean isAllowRenegotiate()
    {
        return _sslContextFactory.isAllowRenegotiate();
    }

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should
     * not be allowed.
     * @param allowRenegotiate true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        _sslContextFactory.setAllowRenegotiate(allowRenegotiate);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void accept(int acceptorID)
        throws IOException, InterruptedException
    {
        Socket socket = _serverSocket.accept();
        configure(socket);

        ConnectorEndPoint connection=new SslConnectorEndPoint(socket);
        connection.dispatch();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void configure(Socket socket)
        throws IOException
    {
        super.configure(socket);
    }

    /* ------------------------------------------------------------ */
    /**
     * Allow the Listener a chance to customise the request. before the server does its stuff. <br>
     * This allows the required attributes to be set for SSL requests. <br>
     * The requirements of the Servlet specs are:
     * <ul>
     * <li> an attribute named "javax.servlet.request.ssl_id" of type String (since Spec 3.0).</li>
     * <li> an attribute named "javax.servlet.request.cipher_suite" of type String.</li>
     * <li> an attribute named "javax.servlet.request.key_size" of type Integer.</li>
     * <li> an attribute named "javax.servlet.request.X509Certificate" of type
     * java.security.cert.X509Certificate[]. This is an array of objects of type X509Certificate,
     * the order of this array is defined as being in ascending order of trust. The first
     * certificate in the chain is the one set by the client, the next is the one used to
     * authenticate the first, and so on. </li>
     * </ul>
     *
     * @param endpoint The Socket the request arrived on.
     *        This should be a {@link SocketEndPoint} wrapping a {@link SSLSocket}.
     * @param request HttpRequest to be customised.
     */
    @Override
    public void customize(EndPoint endpoint, Request request)
        throws IOException
    {
        super.customize(endpoint, request);
        request.setScheme(HttpSchemes.HTTPS);

        SocketEndPoint socket_end_point = (SocketEndPoint)endpoint;
        SSLSocket sslSocket = (SSLSocket)socket_end_point.getTransport();
        SSLSession sslSession = sslSocket.getSession();

        SslCertificates.customize(sslSession,endpoint,request);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getExcludeCipherSuites()
     * @deprecated
     */
    @Deprecated
    public String[] getExcludeCipherSuites() {
        return _sslContextFactory.getExcludeCipherSuites();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getIncludeCipherSuites()
     * @deprecated
     */
    @Deprecated
    public String[] getIncludeCipherSuites()
    {
        return _sslContextFactory.getIncludeCipherSuites();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getKeystore()
     * @deprecated
     */
    @Deprecated
    public String getKeystore()
    {
        return _sslContextFactory.getKeyStorePath();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getKeystoreType()
     * @deprecated
     */
    @Deprecated
    public String getKeystoreType()
    {
        return _sslContextFactory.getKeyStoreType();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getNeedClientAuth()
     * @deprecated
     */
    @Deprecated
    public boolean getNeedClientAuth()
    {
        return _sslContextFactory.getNeedClientAuth();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getProtocol()
     * @deprecated
     */
    @Deprecated
    public String getProtocol()
    {
        return _sslContextFactory.getProtocol();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getProvider()
     * @deprecated
     */
    @Deprecated
    public String getProvider() {
	return _sslContextFactory.getProvider();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getSecureRandomAlgorithm()
     * @deprecated
     */
    @Deprecated
    public String getSecureRandomAlgorithm()
    {
        return _sslContextFactory.getSecureRandomAlgorithm();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getSslKeyManagerFactoryAlgorithm()
     * @deprecated
     */
    @Deprecated
    public String getSslKeyManagerFactoryAlgorithm()
    {
        return _sslContextFactory.getSslKeyManagerFactoryAlgorithm();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getSslTrustManagerFactoryAlgorithm()
     * @deprecated
     */
    @Deprecated
    public String getSslTrustManagerFactoryAlgorithm()
    {
        return _sslContextFactory.getTrustManagerFactoryAlgorithm();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getTruststore()
     * @deprecated
     */
    @Deprecated
    public String getTruststore()
    {
        return _sslContextFactory.getTrustStore();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getSslContextFactory()
     */
//    @Override
    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getTruststoreType()
     * @deprecated
     */
    @Deprecated
    public String getTruststoreType()
    {
        return _sslContextFactory.getTrustStoreType();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getWantClientAuth()
     * @deprecated
     */
    @Deprecated
    public boolean getWantClientAuth()
    {
        return _sslContextFactory.getWantClientAuth();
    }

    /* ------------------------------------------------------------ */
    /**
     * By default, we're confidential, given we speak SSL. But, if we've been told about an
     * confidential port, and said port is not our port, then we're not. This allows separation of
     * listeners providing INTEGRAL versus CONFIDENTIAL constraints, such as one SSL listener
     * configured to require client certs providing CONFIDENTIAL, whereas another SSL listener not
     * requiring client certs providing mere INTEGRAL constraints.
     */
    @Override
    public boolean isConfidential(Request request)
    {
        final int confidentialPort = getConfidentialPort();
        return confidentialPort == 0 || confidentialPort == request.getServerPort();
    }

    /* ------------------------------------------------------------ */
    /**
     * By default, we're integral, given we speak SSL. But, if we've been told about an integral
     * port, and said port is not our port, then we're not. This allows separation of listeners
     * providing INTEGRAL versus CONFIDENTIAL constraints, such as one SSL listener configured to
     * require client certs providing CONFIDENTIAL, whereas another SSL listener not requiring
     * client certs providing mere INTEGRAL constraints.
     */
    @Override
    public boolean isIntegral(Request request)
    {
        final int integralPort = getIntegralPort();
        return integralPort == 0 || integralPort == request.getServerPort();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void open() throws IOException
    {
        _sslContextFactory.checkKeyStore();
        try
        {
            _sslContextFactory.start();
        }
        catch(Exception e)
        {
            throw new RuntimeIOException(e);
        }
        super.open();
    }

    /* ------------------------------------------------------------ */
    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart() throws Exception
    {
        _sslContextFactory.checkKeyStore();
        _sslContextFactory.start();

        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.bio.SocketConnector#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _sslContextFactory.stop();

        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param host The host name that this server should listen on
     * @param port the port that this server should listen on
     * @param backlog See {@link ServerSocket#bind(java.net.SocketAddress, int)}
     * @return A new {@link ServerSocket socket object} bound to the supplied address with all other
     * settings as per the current configuration of this connector.
     * @see #setWantClientAuth(boolean)
     * @see #setNeedClientAuth(boolean)
     * @exception IOException
     */
    @Override
    protected ServerSocket newServerSocket(String host, int port,int backlog) throws IOException
    {
       return _sslContextFactory.newSslServerSocket(host,port,backlog);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setExcludeCipherSuites(java.lang.String[])
     * @deprecated
     */
    @Deprecated
    public void setExcludeCipherSuites(String[] cipherSuites)
    {
        _sslContextFactory.setExcludeCipherSuites(cipherSuites);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setIncludeCipherSuites(java.lang.String[])
     * @deprecated
     */
    @Deprecated
    public void setIncludeCipherSuites(String[] cipherSuites)
    {
        _sslContextFactory.setIncludeCipherSuites(cipherSuites);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setKeyPassword(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setKeyPassword(String password)
    {
        _sslContextFactory.setKeyManagerPassword(password);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keystore The resource path to the keystore, or null for built in keystores.
     * @deprecated
     */
    @Deprecated
    public void setKeystore(String keystore)
    {
        _sslContextFactory.setKeyStorePath(keystore);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setKeystoreType(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setKeystoreType(String keystoreType)
    {
        _sslContextFactory.setKeyStoreType(keystoreType);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the value of the needClientAuth property
     *
     * @param needClientAuth true iff we require client certificate authentication.
     * @deprecated
     */
    @Deprecated
    public void setNeedClientAuth(boolean needClientAuth)
    {
        _sslContextFactory.setNeedClientAuth(needClientAuth);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setPassword(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setPassword(String password)
    {
        _sslContextFactory.setKeyStorePassword(password);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setTrustPassword(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setTrustPassword(String password)
    {
        _sslContextFactory.setTrustStorePassword(password);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setProtocol(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setProtocol(String protocol)
    {
        _sslContextFactory.setProtocol(protocol);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setProvider(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setProvider(String provider) {
        _sslContextFactory.setProvider(provider);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setSecureRandomAlgorithm(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setSecureRandomAlgorithm(String algorithm)
    {
        _sslContextFactory.setSecureRandomAlgorithm(algorithm);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setSslKeyManagerFactoryAlgorithm(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setSslKeyManagerFactoryAlgorithm(String algorithm)
    {
        _sslContextFactory.setSslKeyManagerFactoryAlgorithm(algorithm);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setSslTrustManagerFactoryAlgorithm(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setSslTrustManagerFactoryAlgorithm(String algorithm)
    {
        _sslContextFactory.setTrustManagerFactoryAlgorithm(algorithm);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setTruststore(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setTruststore(String truststore)
    {
        _sslContextFactory.setTrustStore(truststore);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setTruststoreType(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setTruststoreType(String truststoreType)
    {
        _sslContextFactory.setTrustStoreType(truststoreType);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setSslContext(javax.net.ssl.SSLContext)
     * @deprecated
     */
    @Deprecated
    public void setSslContext(SSLContext sslContext)
    {
        _sslContextFactory.setSslContext(sslContext);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setSslContext(javax.net.ssl.SSLContext)
     * @deprecated
     */
    @Deprecated
    public SSLContext getSslContext()
    {
        return _sslContextFactory.getSslContext();
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the value of the _wantClientAuth property. This property is used
     * internally when opening server sockets.
     *
     * @param wantClientAuth true if we want client certificate authentication.
     * @see SSLServerSocket#setWantClientAuth
     * @deprecated
     */
    @Deprecated
    public void setWantClientAuth(boolean wantClientAuth)
    {
        _sslContextFactory.setWantClientAuth(wantClientAuth);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the time in milliseconds for so_timeout during ssl handshaking
     * @param msec a non-zero value will be used to set so_timeout during
     * ssl handshakes. A zero value means the maxIdleTime is used instead.
     */
    public void setHandshakeTimeout (int msec)
    {
        _handshakeTimeout = msec;
    }


    /* ------------------------------------------------------------ */
    public int getHandshakeTimeout ()
    {
        return _handshakeTimeout;
    }

    /* ------------------------------------------------------------ */
    public class SslConnectorEndPoint extends ConnectorEndPoint
    {
        public SslConnectorEndPoint(Socket socket) throws IOException
        {
            super(socket);
        }

        @Override
        public void shutdownOutput() throws IOException
        {
            close();
        }

        @Override
        public void shutdownInput() throws IOException
        {
            close();
        }

        @Override
        public void run()
        {
            try
            {
                int handshakeTimeout = getHandshakeTimeout();
                int oldTimeout = _socket.getSoTimeout();
                if (handshakeTimeout > 0)
                    _socket.setSoTimeout(handshakeTimeout);

                final SSLSocket ssl=(SSLSocket)_socket;
                ssl.addHandshakeCompletedListener(new HandshakeCompletedListener()
                {
                    boolean handshook=false;
                    public void handshakeCompleted(HandshakeCompletedEvent event)
                    {
                        if (handshook)
                        {
                            if (!_sslContextFactory.isAllowRenegotiate())
                            {
                                LOG.warn("SSL renegotiate denied: "+ssl);
                                try{ssl.close();}catch(IOException e){LOG.warn(e);}
                            }
                        }
                        else
                            handshook=true;
                    }
                });
                ssl.startHandshake();

                if (handshakeTimeout>0)
                    _socket.setSoTimeout(oldTimeout);

                super.run();
            }
            catch (SSLException e)
            {
                LOG.debug(e);
                try{close();}
                catch(IOException e2){LOG.ignore(e2);}
            }
            catch (IOException e)
            {
                LOG.debug(e);
                try{close();}
                catch(IOException e2){LOG.ignore(e2);}
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Unsupported.
     *
     * TODO: we should remove this as it is no longer an overridden method from SslConnector (like it was in the past)
     * @deprecated
     */
    @Deprecated
    public String getAlgorithm()
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Unsupported.
     *
     * TODO: we should remove this as it is no longer an overridden method from SslConnector (like it was in the past)
     * @deprecated
     */
    @Deprecated
    public void setAlgorithm(String algorithm)
    {
        throw new UnsupportedOperationException();
    }
}
