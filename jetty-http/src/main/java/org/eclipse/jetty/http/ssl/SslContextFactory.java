package org.eclipse.jetty.http.ssl;


/* ------------------------------------------------------------ */
/**
 * @deprecated Use org.eclipse.jetty.util.ssl.SslContextFactory
 */
public class SslContextFactory extends org.eclipse.jetty.util.ssl.SslContextFactory
{
    public SslContextFactory()
    {
        super();
    }

    public SslContextFactory(boolean trustAll)
    {
        super(trustAll);
    }

    public SslContextFactory(String keyStorePath)
    {
        super(keyStorePath);
    }
}
