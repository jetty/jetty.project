// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.ssl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.http.ssl.SslSelectChannelEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ThreadLocalBuffers;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/**
 * SslSelectChannelConnector.
 * 
 * @org.apache.xbean.XBean element="sslConnector" description="Creates an NIO ssl connector"
 *
 * 
 * 
 */
public class SslSelectChannelConnector extends SelectChannelConnector
{
    /**
     * The name of the SSLSession attribute that will contain any cached
     * information.
     */
    static final String CACHED_INFO_ATTR=CachedInfo.class.getName();

    /** Default value for the keystore location path. */
    public static final String DEFAULT_KEYSTORE=System.getProperty("user.home")+File.separator+".keystore";

    /** String name of key password property. */
    public static final String KEYPASSWORD_PROPERTY="jetty.ssl.keypassword";

    /** String name of keystore password property. */
    public static final String PASSWORD_PROPERTY="jetty.ssl.password";

    /** Default value for the cipher Suites. */
    private String _excludeCipherSuites[]=null;

    /** Default value for the keystore location path. */
    private String _keystore=DEFAULT_KEYSTORE;
    private String _keystoreType="JKS"; // type of the key store

    /** Set to true if we require client certificate authentication. */
    private boolean _needClientAuth=false;
    private boolean _wantClientAuth=false;

    private transient Password _password;
    private transient Password _keyPassword;
    private transient Password _trustPassword;
    private String _protocol="TLS";
    private String _algorithm="SunX509"; // cert algorithm
    private String _provider;
    private String _secureRandomAlgorithm; // cert algorithm
    private String _sslKeyManagerFactoryAlgorithm=(Security.getProperty("ssl.KeyManagerFactory.algorithm")==null?"SunX509":Security
            .getProperty("ssl.KeyManagerFactory.algorithm")); // cert
                                                                // algorithm

    private String _sslTrustManagerFactoryAlgorithm=(Security.getProperty("ssl.TrustManagerFactory.algorithm")==null?"SunX509":Security
            .getProperty("ssl.TrustManagerFactory.algorithm")); // cert
                                                                // algorithm

    private String _truststore;
    private String _truststoreType="JKS"; // type of the key store
    private SSLContext _context;
    Buffers _sslBuffers;


    /**
     * Return the chain of X509 certificates used to negotiate the SSL Session.
     * <p>
     * Note: in order to do this we must convert a
     * javax.security.cert.X509Certificate[], as used by JSSE to a
     * java.security.cert.X509Certificate[],as required by the Servlet specs.
     * 
     * @param sslSession
     *                the javax.net.ssl.SSLSession to use as the source of the
     *                cert chain.
     * @return the chain of java.security.cert.X509Certificates used to
     *         negotiate the SSL connection. <br>
     *         Will be null if the chain is missing or empty.
     */
    private static X509Certificate[] getCertChain(SSLSession sslSession)
    {
        try
        {
            javax.security.cert.X509Certificate javaxCerts[]=sslSession.getPeerCertificateChain();
            if (javaxCerts==null||javaxCerts.length==0)
                return null;

            int length=javaxCerts.length;
            X509Certificate[] javaCerts=new X509Certificate[length];

            java.security.cert.CertificateFactory cf=java.security.cert.CertificateFactory.getInstance("X.509");
            for (int i=0; i<length; i++)
            {
                byte bytes[]=javaxCerts[i].getEncoded();
                ByteArrayInputStream stream=new ByteArrayInputStream(bytes);
                javaCerts[i]=(X509Certificate)cf.generateCertificate(stream);
            }

            return javaCerts;
        }
        catch (SSLPeerUnverifiedException pue)
        {
            return null;
        }
        catch (Exception e)
        {
            Log.warn(Log.EXCEPTION,e);
            return null;
        }
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
        super.customize(endpoint,request);
        request.setScheme(HttpSchemes.HTTPS);
        
        SslSelectChannelEndPoint sslHttpChannelEndpoint=(SslSelectChannelEndPoint)endpoint;
        
        SSLEngine sslEngine=sslHttpChannelEndpoint.getSSLEngine();

        try
        {
            SSLSession sslSession=sslEngine.getSession();
            String cipherSuite=sslSession.getCipherSuite();
            Integer keySize;
            X509Certificate[] certs;
            String idStr;

            CachedInfo cachedInfo=(CachedInfo)sslSession.getValue(CACHED_INFO_ATTR);
            if (cachedInfo!=null)
            {
                keySize=cachedInfo.getKeySize();
                certs=cachedInfo.getCerts();
                idStr=cachedInfo.getIdStr();
            }
            else
            {
                keySize=new Integer(ServletSSL.deduceKeyLength(cipherSuite));
                certs=getCertChain(sslSession);
                byte[] bytes = sslSession.getId();
                idStr = TypeUtil.toHexString(bytes);
                cachedInfo=new CachedInfo(keySize,certs,idStr);
                sslSession.putValue(CACHED_INFO_ATTR,cachedInfo);
            }

            if (certs!=null)
                request.setAttribute("javax.servlet.request.X509Certificate",certs);

            request.setAttribute("javax.servlet.request.cipher_suite",cipherSuite);
            request.setAttribute("javax.servlet.request.key_size",keySize);
            request.setAttribute("javax.servlet.request.ssl_session_id", idStr);
        }
        catch (Exception e)
        {
            Log.warn(Log.EXCEPTION,e);
        }
    }

    /* ------------------------------------------------------------ */
    public SslSelectChannelConnector()
    {
    }

    /**
     * 
     * @deprecated As of Java Servlet API 2.0, with no replacement.
     * 
     */
    public String[] getCipherSuites()
    {
        return getExcludeCipherSuites();
    }

    public String[] getExcludeCipherSuites()
    {
        return _excludeCipherSuites;
    }

    /**
     * 
     * @deprecated As of Java Servlet API 2.0, with no replacement.
     * 
     * 
     */
    public void setCipherSuites(String[] cipherSuites)
    {
        setExcludeCipherSuites(cipherSuites);
    }

    public void setExcludeCipherSuites(String[] cipherSuites)
    {
        this._excludeCipherSuites=cipherSuites;
    }

    /* ------------------------------------------------------------ */
    public void setPassword(String password)
    {
        _password=Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    public void setTrustPassword(String password)
    {
        _trustPassword=Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    public void setKeyPassword(String password)
    {
        _keyPassword=Password.getPassword(KEYPASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    public String getAlgorithm()
    {
        return (this._algorithm);
    }

    /* ------------------------------------------------------------ */
    public void setAlgorithm(String algorithm)
    {
        this._algorithm=algorithm;
    }

    /* ------------------------------------------------------------ */
    public String getProtocol()
    {
        return _protocol;
    }

    /* ------------------------------------------------------------ */
    public void setProtocol(String protocol)
    {
        _protocol=protocol;
    }

    /* ------------------------------------------------------------ */
    public void setKeystore(String keystore)
    {
        _keystore=keystore;
    }

    /* ------------------------------------------------------------ */
    public String getKeystore()
    {
        return _keystore;
    }

    /* ------------------------------------------------------------ */
    public String getKeystoreType()
    {
        return (_keystoreType);
    }

    /* ------------------------------------------------------------ */
    public boolean getNeedClientAuth()
    {
        return _needClientAuth;
    }

    /* ------------------------------------------------------------ */
    public boolean getWantClientAuth()
    {
        return _wantClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the value of the needClientAuth property
     * 
     * @param needClientAuth
     *                true iff we require client certificate authentication.
     */
    public void setNeedClientAuth(boolean needClientAuth)
    {
        _needClientAuth=needClientAuth;
    }

    public void setWantClientAuth(boolean wantClientAuth)
    {
        _wantClientAuth=wantClientAuth;
    }

    /* ------------------------------------------------------------ */
    public void setKeystoreType(String keystoreType)
    {
        _keystoreType=keystoreType;
    }

    /* ------------------------------------------------------------ */
    public String getProvider()
    {
        return _provider;
    }

    public String getSecureRandomAlgorithm()
    {
        return (this._secureRandomAlgorithm);
    }

    /* ------------------------------------------------------------ */
    public String getSslKeyManagerFactoryAlgorithm()
    {
        return (this._sslKeyManagerFactoryAlgorithm);
    }

    /* ------------------------------------------------------------ */
    public String getSslTrustManagerFactoryAlgorithm()
    {
        return (this._sslTrustManagerFactoryAlgorithm);
    }

    /* ------------------------------------------------------------ */
    public String getTruststore()
    {
        return _truststore;
    }

    /* ------------------------------------------------------------ */
    public String getTruststoreType()
    {
        return _truststoreType;
    }

    /* ------------------------------------------------------------ */
    public void setProvider(String _provider)
    {
        this._provider=_provider;
    }

    /* ------------------------------------------------------------ */
    public void setSecureRandomAlgorithm(String algorithm)
    {
        this._secureRandomAlgorithm=algorithm;
    }

    /* ------------------------------------------------------------ */
    public void setSslKeyManagerFactoryAlgorithm(String algorithm)
    {
        this._sslKeyManagerFactoryAlgorithm=algorithm;
    }

    /* ------------------------------------------------------------ */
    public void setSslTrustManagerFactoryAlgorithm(String algorithm)
    {
        this._sslTrustManagerFactoryAlgorithm=algorithm;
    }

    public void setTruststore(String truststore)
    {
        _truststore=truststore;
    }

    public void setTruststoreType(String truststoreType)
    {
        _truststoreType=truststoreType;
    }
    
    public void setSslContext(SSLContext sslContext) {
		this._context = sslContext;
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
    public boolean isIntegral(Request request)
    {
        final int integralPort=getIntegralPort();
        return integralPort==0||integralPort==request.getServerPort();
    }

    /* ------------------------------------------------------------------------------- */
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
    {
        return new SslSelectChannelEndPoint(_sslBuffers,channel,selectSet,key,createSSLEngine());
    }

    /* ------------------------------------------------------------------------------- */
    protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
    {
        HttpConnection connection=(HttpConnection)super.newConnection(channel,endpoint);
        ((HttpParser)connection.getParser()).setForceContentBuffer(true);
        return connection;
    }

    /* ------------------------------------------------------------ */
    protected SSLEngine createSSLEngine() throws IOException
    {
        SSLEngine engine=null;
        try
        {
            engine=_context.createSSLEngine();
            engine.setUseClientMode(false);
            
            if (_wantClientAuth)
                engine.setWantClientAuth(_wantClientAuth);
            if (_needClientAuth)
                engine.setNeedClientAuth(_needClientAuth);
            
            if (_excludeCipherSuites!=null&&_excludeCipherSuites.length>0)
            {
                List<String> excludedCSList=Arrays.asList(_excludeCipherSuites);
                String[] enabledCipherSuites=engine.getEnabledCipherSuites();
                List<String> enabledCSList=new ArrayList<String>(Arrays.asList(enabledCipherSuites));

                for (String cipherName : excludedCSList)
                {
                    if (enabledCSList.contains(cipherName))
                    {
                        enabledCSList.remove(cipherName);
                    }
                }
                enabledCipherSuites=enabledCSList.toArray(new String[enabledCSList.size()]);

                engine.setEnabledCipherSuites(enabledCipherSuites);
            }

        }
        catch (Exception e)
        {
            Log.warn("Error creating sslEngine -- closing this connector",e);
            close();
            throw new IllegalStateException(e);
        }
        return engine;
    }

   
    protected void doStart() throws Exception
    {
    	if (_context == null) {
           _context=createSSLContext();
    	}
        
        SSLEngine engine=createSSLEngine();
        SSLSession ssl_session=engine.getSession();
        
        ThreadLocalBuffers buffers = new ThreadLocalBuffers()
        {
            @Override
            protected Buffer newBuffer(int size)
            {
                // TODO indirect?
                return new DirectNIOBuffer(size);
            }
            @Override
            protected Buffer newHeader(int size)
            {
                // TODO indirect?
                return new DirectNIOBuffer(size);
            }
            @Override
            protected boolean isHeader(Buffer buffer)
            {
                return true;
            }
        };
        buffers.setBufferSize(ssl_session.getApplicationBufferSize());
        buffers.setHeaderSize(ssl_session.getPacketBufferSize());
        _sslBuffers=buffers;
        
        if (getRequestHeaderSize()<ssl_session.getApplicationBufferSize())
            setRequestHeaderSize(ssl_session.getApplicationBufferSize());
        if (getRequestBufferSize()<ssl_session.getApplicationBufferSize())
            setRequestBufferSize(ssl_session.getApplicationBufferSize());
        
        super.doStart();
    }

    protected SSLContext createSSLContext() throws Exception
    {
        if (_truststore==null)
        {
            _truststore=_keystore;
            _truststoreType=_keystoreType;
        }

        InputStream keystoreInputStream = null;

        KeyManager[] keyManagers=null;
        KeyStore keyStore = null;
        try
        {
            if (_keystore!=null)
            {
                keystoreInputStream=Resource.newResource(_keystore).getInputStream();
                keyStore = KeyStore.getInstance(_keystoreType);
                keyStore.load(keystoreInputStream,_password==null?null:_password.toString().toCharArray());
            }
        }
        finally
        {
            if (keystoreInputStream != null)
                keystoreInputStream.close();
        }

        KeyManagerFactory keyManagerFactory=KeyManagerFactory.getInstance(_sslKeyManagerFactoryAlgorithm);
        keyManagerFactory.init(keyStore,_keyPassword==null?(_password==null?null:_password.toString().toCharArray()):_keyPassword.toString().toCharArray());
        keyManagers=keyManagerFactory.getKeyManagers();


        TrustManager[] trustManagers=null;
        InputStream truststoreInputStream = null;
        KeyStore trustStore = null;
        try
        {
            if (_truststore!=null)
            {
                truststoreInputStream = Resource.newResource(_truststore).getInputStream();
                trustStore=KeyStore.getInstance(_truststoreType);
                trustStore.load(truststoreInputStream,_trustPassword==null?null:_trustPassword.toString().toCharArray());
            }
        }
        finally
        {
            if (truststoreInputStream != null)
                truststoreInputStream.close();
        }


        TrustManagerFactory trustManagerFactory=TrustManagerFactory.getInstance(_sslTrustManagerFactoryAlgorithm);
        trustManagerFactory.init(trustStore);
        trustManagers=trustManagerFactory.getTrustManagers();

        SecureRandom secureRandom=_secureRandomAlgorithm==null?null:SecureRandom.getInstance(_secureRandomAlgorithm);
        SSLContext context=_provider==null?SSLContext.getInstance(_protocol):SSLContext.getInstance(_protocol,_provider);
        context.init(keyManagers,trustManagers,secureRandom);
        return context;
    }

    /**
     * Simple bundle of information that is cached in the SSLSession. Stores the
     * effective keySize and the client certificate chain.
     */
    private class CachedInfo
    {
        private final X509Certificate[] _certs;
        private final Integer _keySize;
        private final String _idStr;

        CachedInfo(Integer keySize, X509Certificate[] certs,String idStr)
        {
            this._keySize=keySize;
            this._certs=certs;
            this._idStr=idStr;
        }

        X509Certificate[] getCerts()
        {
            return _certs;
        }

        Integer getKeySize()
        {
            return _keySize;
        }
        
        String getIdStr()
        {
            return _idStr;
        }
    }

}
