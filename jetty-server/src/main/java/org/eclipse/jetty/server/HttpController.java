package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface HttpController
{
    public int write(ByteBuffer content,boolean volatileContent) throws IOException;
        
    void sendError(int status, String reason, String content, boolean close)  throws IOException;
    
    void send1xx(int processing102);
    
    int getContentBufferSize();

    void increaseContentBufferSize(int size);

    void resetBuffer();

    public boolean isResponseCommitted();

    public boolean isPersistent();
    
    public void setPersistent(boolean persistent);

    public void flushResponse() throws IOException;

    public void completeResponse();
    
    Connector getConnector();
    
    void persist();

    void customize(Request request);
    
}
