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


package org.eclipse.jetty.http;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;

public interface Generator
{
    public static final boolean LAST=true;
    public static final boolean MORE=false;

    /* ------------------------------------------------------------ */
    /**
     * Add content.
     * 
     * @param content
     * @param last
     * @throws IllegalArgumentException if <code>content</code> is {@link Buffer#isImmutable immutable}.
     * @throws IllegalStateException If the request is not expecting any more content,
     *   or if the buffers are full and cannot be flushed.
     * @throws IOException if there is a problem flushing the buffers.
     */
    void addContent(Buffer content, boolean last) throws IOException;

    void complete() throws IOException;

    void completeHeader(HttpFields responseFields, boolean last) throws IOException;

    int flushBuffer() throws IOException;

    int getContentBufferSize();

    long getContentWritten();

    boolean isWritten();
    
    boolean isAllContentWritten();

    void increaseContentBufferSize(int size);
    
    boolean isBufferFull();

    boolean isCommitted();

    boolean isComplete();

    boolean isPersistent();

    void reset();

    void resetBuffer();
    
    void returnBuffers();

    void sendError(int code, String reason, String content, boolean close) throws IOException;
    
    void setHead(boolean head);

    void setRequest(String method, String uri);

    void setResponse(int status, String reason);


    void setSendServerVersion(boolean sendServerVersion);
 
    void setVersion(int version);

    boolean isIdle();

    void setContentLength(long length);
    
    void setPersistent(boolean persistent);

    void setDate(Buffer timeStampBuffer);
    

}
