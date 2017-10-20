package org.eclipse.jetty.websocket.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;

public class NegotiateMessage
{
    protected final Map<String, List<String>> headers = new HashMap<>();
    
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    public void setHeader(String name, String value)
    {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name,list);
    }
    
    public void addHeader(String name, String value)
    {
        headers.computeIfAbsent(name,k->new ArrayList<>()).add(value);
    }
    
    public void deleteHeader(String name)
    {
        headers.remove(name);
    }
    
    public String getHeader(String name)
    {
        List<String> list = headers.get(name);
        if (list!=null && !list.isEmpty())
            return list.get(0);
        return null;
    }
    
    public List<String> getHeaders(String name)
    {
        return headers.get(name);
    }

    public void clearHeaders()
    {
        headers.clear();
    }
    
    
    public static class Request extends NegotiateMessage
    {
        final String method;
        final String uri;
        final String protocol;
        
        public Request(String method, String uri, String protocol)
        {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
        }     
        
        public List<ExtensionConfig> getOfferedExtensions()
        {
            List<String> list = headers.get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString());
            if (list==null || list.isEmpty())
                return Collections.emptyList();
            
            QuotedCSV csv = new QuotedCSV();
            list.forEach(csv::addValue);
            
            if (csv.isEmpty())
                return Collections.emptyList();
                
            return csv.getValues().stream().map(ExtensionConfig::parse).collect(Collectors.toList());
        }
        
        public List<String> getOfferedSubprotocols()
        {
            List<String> offered = headers.get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString());
            if (offered==null)
                return Collections.emptyList();
            return offered;
        }

    }
    
    public static class Response extends NegotiateMessage
    {
        private ExtensionStack extensionStack;
        private int errorCode;
        private String errorReason;

        public String getSubprotocol()
        {
            return getHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString());
        }
        
        public void setSubprotocol(String subprotocol)
        {
            setHeader(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(),subprotocol);
        }

        public ExtensionStack getExtensionStack()
        {
            return extensionStack;
        }

        public void setExtensionStack(ExtensionStack extensionStack)
        {
            this.extensionStack = extensionStack;
            if (extensionStack.hasNegotiatedExtensions())
                setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),
                        ExtensionConfig.toHeaderValue(extensionStack.getNegotiatedExtensions()));
            else
                deleteHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString());
        }
        
        public void sendError(int code, String reason)
        {            
            errorCode = code;
            errorReason = reason;
        }

        public boolean isError()
        {
            return errorCode>0;
        }

        public int getErrorCode()
        {
            return errorCode;
        }

        public String getErrorReason()
        {
            return errorReason;
        }

    }
}
