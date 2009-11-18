// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.ajp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

/**
 * Connection implementation of the Ajp13 protocol. <p/> XXX Refactor to remove
 * duplication of HttpConnection
 * 
 * 
 * 
 */
public class Ajp13Connection extends HttpConnection
{
    public Ajp13Connection(Connector connector, EndPoint endPoint, Server server)
    {
        super(connector, endPoint, server,
                new Ajp13Parser(connector.getRequestBuffers(), endPoint),
                new Ajp13Generator(connector.getResponseBuffers(), endPoint),
                new Ajp13Request()
                );
        
        ((Ajp13Parser)_parser).setEventHandler(new RequestHandler());
        ((Ajp13Parser)_parser).setGenerator((Ajp13Generator)_generator);
        ((Ajp13Request)_request).setConnection(this);
    }

    @Override
    public boolean isConfidential(Request request)
    {
        return ((Ajp13Request) request).isSslSecure();
    }

    @Override
    public boolean isIntegral(Request request)
    {
        return ((Ajp13Request) request).isSslSecure();
    }

    @Override
    public ServletInputStream getInputStream()
    {
        if (_in == null)
            _in = new Ajp13Parser.Input((Ajp13Parser) _parser, _connector.getMaxIdleTime());
        return _in;
    }

    private class RequestHandler implements Ajp13Parser.EventHandler
    {
        boolean _delayedHandling = false;

        public void startForwardRequest() throws IOException
        {
            _delayedHandling = false;
            _uri.clear();
	    
            ((Ajp13Request) _request).setSslSecure(false);
            _request.setTimeStamp(System.currentTimeMillis());
            _request.setUri(_uri);
            
        }

        public void parsedAuthorizationType(Buffer authType) throws IOException
        {
            //TODO JASPI this doesn't appear to make sense yet... how does ajp auth fit into jetty auth?
//            _request.setAuthType(authType.toString());
        }

        public void parsedRemoteUser(Buffer remoteUser) throws IOException
        {
            ((Ajp13Request)_request).setRemoteUser(remoteUser.toString());
        }

        public void parsedServletPath(Buffer servletPath) throws IOException
        {
            _request.setServletPath(servletPath.toString());
        }

        public void parsedContextPath(Buffer context) throws IOException
        {
            _request.setContextPath(context.toString());
        }

        public void parsedSslCert(Buffer sslCert) throws IOException
        {
            try 
            {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                ByteArrayInputStream bis = new ByteArrayInputStream(sslCert.toString().getBytes());

                Collection<? extends java.security.cert.Certificate> certCollection = cf.generateCertificates(bis);
                X509Certificate[] certificates = new X509Certificate[certCollection.size()];

                int i=0;
                for (Object aCertCollection : certCollection)
                {
                    certificates[i++] = (X509Certificate) aCertCollection;
                }

                _request.setAttribute("javax.servlet.request.X509Certificate", certificates);
            } 
            catch (Exception e) 
            {
                org.eclipse.jetty.util.log.Log.warn(e.toString());
                org.eclipse.jetty.util.log.Log.ignore(e);
                if (sslCert!=null)
                    _request.setAttribute("javax.servlet.request.X509Certificate", sslCert.toString());
            }
        }

        public void parsedSslCipher(Buffer sslCipher) throws IOException
        {
            _request.setAttribute("javax.servlet.request.cipher_suite", sslCipher.toString());
        }

        public void parsedSslSession(Buffer sslSession) throws IOException
        {
            _request.setAttribute("javax.servlet.request.ssl_session", sslSession.toString());
        }

        public void parsedSslKeySize(int keySize) throws IOException
        {
           _request.setAttribute("javax.servlet.request.key_size", new Integer(keySize));
        }

        public void parsedMethod(Buffer method) throws IOException
        {
            if (method == null)
                throw new HttpException(HttpServletResponse.SC_BAD_REQUEST);
            _request.setMethod(method.toString());
        }

        public void parsedUri(Buffer uri) throws IOException
        {
            _uri.parse(uri.toString());
        }

        public void parsedProtocol(Buffer protocol) throws IOException
        {
            if (protocol != null && protocol.length()>0)
            {
                _request.setProtocol(protocol.toString());
            }
        }

        public void parsedRemoteAddr(Buffer addr) throws IOException
        {
            if (addr != null && addr.length()>0)
            {
                _request.setRemoteAddr(addr.toString());
            }
        }

        public void parsedRemoteHost(Buffer name) throws IOException
        {
            if (name != null && name.length()>0)
            {
                _request.setRemoteHost(name.toString());
            }
        }

        public void parsedServerName(Buffer name) throws IOException
        {
            if (name != null && name.length()>0)
            {
                _request.setServerName(name.toString());
            }
        }

        public void parsedServerPort(int port) throws IOException
        {
            _request.setServerPort(port);
        }

        public void parsedSslSecure(boolean secure) throws IOException
        {
            ((Ajp13Request) _request).setSslSecure(secure);
        }

        public void parsedQueryString(Buffer value) throws IOException
        {
            String u = _uri + "?" + value;
            _uri.parse(u);
        }

        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            _requestFields.add(name, value);
        }

        public void parsedRequestAttribute(String key, Buffer value) throws IOException
        {
            _request.setAttribute(key, value.toString());
        }
        
        public void parsedRequestAttribute(String key, int value) throws IOException
        {
            _request.setAttribute(key, Integer.toString(value));
        }

        public void headerComplete() throws IOException
        {
            if (((Ajp13Parser) _parser).getContentLength() <= 0)
            {
                handleRequest();
            }
            else
            {
                _delayedHandling = true;
            }
        }

        public void messageComplete(long contextLength) throws IOException
        {
        }

        public void content(Buffer ref) throws IOException
        {
            if (_delayedHandling)
            {
                _delayedHandling = false;
                handleRequest();
            }
        }

    }

}
