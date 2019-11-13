package org.eclipse.jetty.websocket.javax.common;

import java.util.List;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Extension;

public class ClientEndpointConfigWrapper extends EndpointConfigWrapper implements ClientEndpointConfig
{
    private ClientEndpointConfig _endpointConfig;

    public ClientEndpointConfigWrapper(ClientEndpointConfig endpointConfig)
    {
        super(endpointConfig);
        _endpointConfig = endpointConfig;
    }

    @Override
    public List<String> getPreferredSubprotocols()
    {
        return _endpointConfig.getPreferredSubprotocols();
    }

    @Override
    public List<Extension> getExtensions()
    {
        return _endpointConfig.getExtensions();
    }

    @Override
    public Configurator getConfigurator()
    {
        return _endpointConfig.getConfigurator();
    }
}
