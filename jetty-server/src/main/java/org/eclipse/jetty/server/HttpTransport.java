package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface HttpTransport
{
    public void write(ByteBuffer wrap,boolean volatileContent) throws IOException;
    
    public long getContentWritten();
    
    void sendError(int status, String reason, String content, boolean close)  throws IOException;
    
    void send1xx(int processing102);
    
    boolean isAllContentWritten();

    int getContentBufferSize();

    void increaseContentBufferSize(int size);

    void resetBuffer();

    public boolean isResponseCommitted();

    public boolean isPersistent();
    
    public void setPersistent(boolean persistent);

    public InetSocketAddress getLocalAddress();

    public InetSocketAddress getRemoteAddress();

    public void flushResponse() throws IOException;

    public void completeResponse();
    
    Connector getConnector();
    
    void persist();

    void customize(Request request);

    long getMaxIdleTime();
    
}
