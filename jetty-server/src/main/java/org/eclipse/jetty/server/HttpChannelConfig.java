//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("HTTP Channel Configuration")
public class HttpChannelConfig
{
    private List<Customizer> _customizers=new CopyOnWriteArrayList<>();
    private int _outputBufferSize=32*1024;
    private int _requestHeaderSize=8*1024;
    private int _responseHeaderSize=8*1024;
    private int _securePort;
    private String _secureScheme = HttpScheme.HTTPS.asString();

    public interface Customizer
    {
        public void customize(Connector connector, HttpChannelConfig channelConfig, Request request);
    }
    
    public interface ConnectionFactory
    {
        HttpChannelConfig getHttpChannelConfig();
    }
    
    public void addCustomizer(Customizer customizer)
    {
        _customizers.add(customizer);
    }
    
    public List<Customizer> getCustomizers()
    {
        return _customizers;
    }
    
    public <T> T getCustomizer(Class<T> type)
    {
        for (Customizer c : _customizers)
            if (type.isAssignableFrom(c.getClass()))
                return (T)c;
        return null;
    }

    @ManagedAttribute("The size in bytes of the output buffer used to aggregate HTTP output")
    public int getOutputBufferSize()
    {
        return _outputBufferSize;
    }
    
    @ManagedAttribute("The maximum allowed size in bytes for a HTTP request header")
    public int getRequestHeaderSize()
    {
        return _requestHeaderSize;
    }
    
    @ManagedAttribute("The maximum allowed size in bytes for a HTTP response header")
    public int getResponseHeaderSize()
    {
        return _responseHeaderSize;
    }
    
    @ManagedAttribute("The port to which Integral or Confidential security constraints are redirected")
    public int getSecurePort()
    {
        return _securePort;
    }
    
    @ManagedAttribute("The scheme with which Integral or Confidential security constraints are redirected")
    public String getSecureScheme()
    {
        return _secureScheme;
    }
    
    public void setCustomizers(List<Customizer> customizers)
    {
        _customizers.clear();
        _customizers.addAll(customizers);
    }

    public void setOutputBufferSize(int responseBufferSize)
    {
        _outputBufferSize = responseBufferSize;
    }
    
    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _requestHeaderSize = requestHeaderSize;
    }

    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _responseHeaderSize = responseHeaderSize;
    }

    public void setSecurePort(int confidentialPort)
    {
        _securePort = confidentialPort;
    }

    public void setSecureScheme(String confidentialScheme)
    {
        _secureScheme = confidentialScheme;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%d,%d/%d,%s://:%d,%s}",this.getClass().getSimpleName(),hashCode(),_outputBufferSize,_requestHeaderSize,_responseHeaderSize,_secureScheme,_securePort,_customizers);
    }
}
