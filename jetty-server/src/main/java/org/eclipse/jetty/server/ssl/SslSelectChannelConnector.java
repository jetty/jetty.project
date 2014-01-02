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
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.io.BuffersFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/* ------------------------------------------------------------ */
/**
 * SslSelectChannelConnector.
 *
 * @org.apache.xbean.XBean element="sslConnector" description="Creates an NIO ssl connector"
 */
public class SslSelectChannelConnector extends SelectChannelConnector implements SslConnector
{
    private final SslContextFactory _sslContextFactory;
    private Buffers _sslBuffers;

    /* ------------------------------------------------------------ */
    public SslSelectChannelConnector()
    {
        this(new SslContextFactory(SslContextFactory.DEFAULT_KEYSTORE_PATH));
        setSoLingerTime(30000);
    }

    /* ------------------------------------------------------------ */
    /** Construct with explicit SslContextFactory.
     * The SslContextFactory passed is added via {@link #addBean(Object)} so that 
     * it's lifecycle may be managed with {@link AggregateLifeCycle}.
     * @param sslContextFactory
     */
    public SslSelectChannelConnector(SslContextFactory sslContextFactory)
    {
        _sslContextFactory = sslContextFactory;
        addBean(_sslContextFactory);
        setUseDirectBuffers(false);
        setSoLingerTime(30000);
    }

    /* ------------------------------------------------------------ */
    /**
     * Allow the Listener a chance to customise the request. before the server
     * does its stuff. <br>
     * This allows the required attributes to be set for SSL requests. <br>
     * The requirements of the Servlet specs are:
     * <ul>
     * <li> an attribute named "javax.servlet.request.ssl_session_id" of type
     * String (since Servlet Spec 3.0).</li>
     * <li> an attribute named "javax.servlet.request.cipher_suite" of type
     * String.</li>
     * <li> an attribute named "javax.servlet.request.key_size" of type Integer.</li>
     * <li> an attribute named "javax.servlet.request.X509Certificate" of type
     * java.security.cert.X509Certificate[]. This is an array of objects of type
     * X509Certificate, the order of this array is defined as being in ascending
     * order of trust. The first certificate in the chain is the one set by the
     * client, the next is the one used to authenticate the first, and so on.
     * </li>
     * </ul>
     *
     * @param endpoint
     *                The Socket the request arrived on. This should be a
     *                {@link SocketEndPoint} wrapping a {@link SSLSocket}.
     * @param request
     *                HttpRequest to be customised.
     */
    @Override
    public void customize(EndPoint endpoint, Request request) throws IOException
    {
        request.setScheme(HttpSchemes.HTTPS);
        super.customize(endpoint,request);

        SslConnection.SslEndPoint sslEndpoint=(SslConnection.SslEndPoint)endpoint;
        SSLEngine sslEngine=sslEndpoint.getSslEngine();
        SSLSession sslSession=sslEngine.getSession();

        SslCertificates.customize(sslSession,endpoint,request);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     * @deprecated
     */
    @Deprecated
    public boolean isAllowRenegotiate()
    {
        return _sslContextFactory.isAllowRenegotiate();
    }

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should
     * not be allowed.  CVE-2009-3555 was fixed in Sun java 1.6 with a ban
     * of renegotiate in u19 and with RFC5746 in u22.
     * @param allowRenegotiate true if re-negotiation is allowed (default false)
     * @deprecated
     */
    @Deprecated
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        _sslContextFactory.setAllowRenegotiate(allowRenegotiate);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#getExcludeCipherSuites()
     * @deprecated
     */
    @Deprecated
    public String[] getExcludeCipherSuites()
    {
        return _sslContextFactory.getExcludeCipherSuites();
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#getExcludeCipherSuites()
     * @deprecated
     */
    @Deprecated
    public String[] getIncludeCipherSuites()
    {
        return _sslContextFactory.getIncludeCipherSuites();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setExcludeCipherSuites(java.lang.String[])
     * @deprecated
     */
    @Deprecated
    public void setIncludeCipherSuites(String[] cipherSuites)
    {
        _sslContextFactory.setIncludeCipherSuites(cipherSuites);
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#setKeystore(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setKeystore(String keystore)
    {
        _sslContextFactory.setKeyStorePath(keystore);
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#setNeedClientAuth(boolean)
     * @deprecated
     */
    @Deprecated
    public void setNeedClientAuth(boolean needClientAuth)
    {
        _sslContextFactory.setNeedClientAuth(needClientAuth);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.ssl.SslConnector#setWantClientAuth(boolean)
     * @deprecated
     */
    @Deprecated
    public void setWantClientAuth(boolean wantClientAuth)
    {
        _sslContextFactory.setWantClientAuth(wantClientAuth);
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#getProvider()
     * @deprecated
     */
    @Deprecated
    public String getProvider()
    {
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#setProvider(java.lang.String)
     * @deprecated
     */
    @Deprecated
    public void setProvider(String provider)
    {
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
     * @see org.eclipse.jetty.server.ssl.SslConnector#getSslContextFactory()
     */
    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    /* ------------------------------------------------------------ */
    /**
     * By default, we're confidential, given we speak SSL. But, if we've been
     * told about an confidential port, and said port is not our port, then
     * we're not. This allows separation of listeners providing INTEGRAL versus
     * CONFIDENTIAL constraints, such as one SSL listener configured to require
     * client certs providing CONFIDENTIAL, whereas another SSL listener not
     * requiring client certs providing mere INTEGRAL constraints.
     */
    @Override
    public boolean isConfidential(Request request)
    {
        final int confidentialPort=getConfidentialPort();
        return confidentialPort==0||confidentialPort==request.getServerPort();
    }

    /* ------------------------------------------------------------ */
    /**
     * By default, we're integral, given we speak SSL. But, if we've been told
     * about an integral port, and said port is not our port, then we're not.
     * This allows separation of listeners providing INTEGRAL versus
     * CONFIDENTIAL constraints, such as one SSL listener configured to require
     * client certs providing CONFIDENTIAL, whereas another SSL listener not
     * requiring client certs providing mere INTEGRAL constraints.
     */
    @Override
    public boolean isIntegral(Request request)
    {
        final int integralPort=getIntegralPort();
        return integralPort==0||integralPort==request.getServerPort();
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
    {
        try
        {
            SSLEngine engine = createSSLEngine(channel);
            SslConnection connection = newSslConnection(endpoint, engine);
            AsyncConnection delegate = newPlainConnection(channel, connection.getSslEndPoint());
            connection.getSslEndPoint().setConnection(delegate);
            connection.setAllowRenegotiate(_sslContextFactory.isAllowRenegotiate());
            return connection;
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    protected AsyncConnection newPlainConnection(SocketChannel channel, AsyncEndPoint endPoint)
    {
        return super.newConnection(channel, endPoint);
    }

    protected SslConnection newSslConnection(AsyncEndPoint endpoint, SSLEngine engine)
    {
        return new SslConnection(engine, endpoint);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param channel A channel which if passed is used as to extract remote
     * host and port for the purposes of SSL session caching
     * @return A SSLEngine for a new or cached SSL Session
     * @throws IOException if the SSLEngine cannot be created
     */
    protected SSLEngine createSSLEngine(SocketChannel channel) throws IOException
    {
        SSLEngine engine;
        if (channel != null)
        {
            String peerHost = channel.socket().getInetAddress().getHostAddress();
            int peerPort = channel.socket().getPort();
            engine = _sslContextFactory.newSslEngine(peerHost, peerPort);
        }
        else
        {
            engine = _sslContextFactory.newSslEngine();
        }

        engine.setUseClientMode(false);
        return engine;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.nio.SelectChannelConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _sslContextFactory.checkKeyStore();
        _sslContextFactory.start();

        SSLEngine sslEngine = _sslContextFactory.newSslEngine();

        sslEngine.setUseClientMode(false);

        SSLSession sslSession = sslEngine.getSession();

        _sslBuffers = BuffersFactory.newBuffers(
                getUseDirectBuffers()?Type.DIRECT:Type.INDIRECT,sslSession.getApplicationBufferSize(),
                getUseDirectBuffers()?Type.DIRECT:Type.INDIRECT,sslSession.getApplicationBufferSize(),
                getUseDirectBuffers()?Type.DIRECT:Type.INDIRECT,getMaxBuffers()
        );

        if (getRequestHeaderSize()<sslSession.getApplicationBufferSize())
            setRequestHeaderSize(sslSession.getApplicationBufferSize());
        if (getRequestBufferSize()<sslSession.getApplicationBufferSize())
            setRequestBufferSize(sslSession.getApplicationBufferSize());

        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.nio.SelectChannelConnector#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _sslBuffers=null;
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return SSL buffers
     */
    public Buffers getSslBuffers()
    {
        return _sslBuffers;
    }
}
