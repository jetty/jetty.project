package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.Handler;


/* ------------------------------------------------------------ */
/** ProxyHandler.
 * <p>This class has been renamed to ConnectHandler, as it only implements
 * the CONNECT method (and a ProxyServlet must be used for full proxy handling).
 * @deprecated Use {@link ConnectHandler}
 */
public class ProxyHandler extends ConnectHandler
{
    public ProxyHandler()
    {
        super();
    }

    public ProxyHandler(Handler handler, String[] white, String[] black)
    {
        super(handler,white,black);
    }

    public ProxyHandler(Handler handler)
    {
        super(handler);
    }

    public ProxyHandler(String[] white, String[] black)
    {
        super(white,black);
    }
}
