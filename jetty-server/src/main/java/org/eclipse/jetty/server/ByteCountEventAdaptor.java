//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

public class ByteCountEventAdaptor implements ByteCountEvent
{
    private final Request request;

    public ByteCountEventAdaptor(Request request)
    {
        this.request = request;
        this.requestCounts = new HttpByteCountAdaptor();
        this.responseCounts = new HttpByteCountAdaptor();
    }

    public void onComplete(long bytesIn, long bytesOut)
    {
        requestCounts.connectionCount = bytesIn;
        responseCounts.connectionCount = bytesOut;
    }

    class HttpByteCountAdaptor implements HttpByteCount
    {
        // The connectionCount
        private long connectionCount;
        // The failure condition / cause
        private Throwable failure;
        // The location when the failure occurred in connectionCount
        private long failureLoc = -1;
        // Start of headers in connectionCount;
        private long headerStart;
        // End of headers in connectionCount;
        private long headerEnd = -1;
        // Start of body in connectionCount;
        private long bodyStart;
        // End of body in connectionCount;
        private long bodyEnd = -1;
        // Start of trailer in connectionCount;
        private long trailerStart;
        // End of trailer in connectionCount;
        private long trailerEnd = -1;
        // Reported Streaming API bytes used via Servlet API
        private long apiCount = -1;

        @Override
        public long getHeaderCount()
        {
            if (headerEnd >= headerStart)
                return headerEnd - headerStart;
            else
                return -1;
        }

        @Override
        public long getBodyCount()
        {
            if (bodyEnd >= bodyStart)
                return bodyEnd - bodyStart;
            else
                return -1;
        }

        @Override
        public long getStreamAPICount()
        {
            return apiCount;
        }

        @Override
        public long getTrailerCount()
        {
            if (trailerEnd >= trailerStart)
                return trailerEnd = trailerStart;
            else
                return -1;
        }

        void onHeadersStart(long connectionCount)
        {
            this.connectionCount = connectionCount;
            this.headerStart = connectionCount;
        }

        void onHeadersEnd(long connectionCount)
        {
            this.connectionCount = connectionCount;
            this.headerEnd = connectionCount;
        }

        void onBodyStart(long connectionCount)
        {
            this.connectionCount = connectionCount;
            this.bodyStart = connectionCount;
        }

        void onBodyEnd(long connectionCount, long byteCountAPI)
        {
            this.connectionCount = connectionCount;
            this.bodyEnd = connectionCount;
            this.apiCount = byteCountAPI;
            this.trailerStart = connectionCount;
        }

        void onTrailerEnd(long connectionCount)
        {
            this.trailerEnd = connectionCount;
        }

        public void onFailure(long connectionCount, Throwable failure)
        {
            this.connectionCount = connectionCount;
            this.failure = failure;
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder("HttpByteCount[");
            sb.append("connectionCount=").append(connectionCount);
            sb.append(", failure=").append(failure);
            sb.append(", failureLoc=").append(failureLoc);
            sb.append(", headerStart=").append(headerStart);
            sb.append(", headerEnd=").append(headerEnd);
            sb.append(", bodyStart=").append(bodyStart);
            sb.append(", bodyEnd=").append(bodyEnd);
            sb.append(", trailerStart=").append(trailerStart);
            sb.append(", trailerEnd=").append(trailerEnd);
            sb.append(", apiCount=").append(apiCount);
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Request Counts. (including HttpConnection.bytesIn)
     */
    private HttpByteCountAdaptor requestCounts;
    /**
     * Response Counts. (including HttpConnection.bytesOut)
     */
    private HttpByteCountAdaptor responseCounts;

    @Override
    public Request getRequest()
    {
        return request;
    }

    @Override
    public HttpByteCountAdaptor getRequestCount()
    {
        return requestCounts;
    }

    @Override
    public HttpByteCountAdaptor getResponseCount()
    {
        return responseCounts;
    }

    @Override
    public Response getResponse()
    {
        return request.getResponse();
    }

    @Override
    public boolean hasFailure()
    {
        return (requestCounts.failure != null) || (responseCounts.failure != null);
    }

    @Override
    public Throwable getRequestFailure()
    {
        return requestCounts.failure;
    }

    @Override
    public Throwable getResponseFailure()
    {
        return responseCounts.failure;
    }
}
