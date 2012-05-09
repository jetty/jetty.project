package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.io.EndPoint;

public interface HttpConnector extends Connector
{
    int getRequestHeaderSize();
    int getRequestBufferSize();
    int getResponseHeaderSize();
    int getResponseBufferSize();
    
    /* ------------------------------------------------------------ */
    /**
     * @return The port to use when redirecting a request if a data constraint of integral is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    int getIntegralPort();

    /* ------------------------------------------------------------ */
    /**
     * @return The schema to use when redirecting a request if a data constraint of integral is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    String getIntegralScheme();

    /* ------------------------------------------------------------ */
    /**
     * @param request A request
     * @return true if the request is integral. This normally means the https schema has been used.
     */
    boolean isIntegral(Request request);

    /* ------------------------------------------------------------ */
    /**
     * @return The port to use when redirecting a request if a data constraint of confidential is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    int getConfidentialPort();
    
    /* ------------------------------------------------------------ */
    /**
     * @return The schema to use when redirecting a request if a data constraint of confidential is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    String getConfidentialScheme();
    
    /* ------------------------------------------------------------ */
    /**
     * @param request A request
     * @return true if the request is confidential. This normally means the https schema has been used.
     */
    boolean isConfidential(Request request);

    /* ------------------------------------------------------------ */
    /** Customize a request for an endpoint.
     * Called on every request to allow customization of the request for
     * the particular endpoint (eg security properties from a SSL connection).
     * @param request
     * @throws IOException
     */
    void customize(Request request) throws IOException;

    
}
