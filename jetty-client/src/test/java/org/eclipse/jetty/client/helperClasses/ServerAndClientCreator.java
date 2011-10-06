package org.eclipse.jetty.client.helperClasses;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;

public interface ServerAndClientCreator
{
    Server createServer() throws Exception;
    
    HttpClient createClient(long idleTimeout, long timeout, int connectTimeout) throws Exception;
}
