package org.eclipse.jetty.websocket.server.examples.echo;

import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

/**
 * Example server using WebSocket and core Jetty Handlers
 */
public class ExampleEchoServer
{
    private static final Logger LOG = Log.getLogger(ExampleEchoServer.class);

    public static void main(String... args)
    {
        try
        {
            int port = 8080;
            boolean verbose = false;
            String docroot = "src/test/webapp";

            for (int i = 0; i < args.length; i++)
            {
                String a = args[i];
                if ("-p".equals(a) || "--port".equals(a))
                {
                    port = Integer.parseInt(args[++i]);
                }
                else if ("-v".equals(a) || "--verbose".equals(a))
                {
                    verbose = true;
                }
                else if ("-d".equals(a) || "--docroot".equals(a))
                {
                    docroot = args[++i];
                }
                else if (a.startsWith("-"))
                {
                    usage();
                }
            }

            ExampleEchoServer server = new ExampleEchoServer(port);
            server.setVerbose(verbose);
            server.setResourceBase(docroot);
            server.runForever();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    private static void usage()
    {
        System.err.println("java -cp{CLASSPATH} " + ExampleEchoServer.class + " [ OPTIONS ]");
        System.err.println("  -p|--port PORT    (default 8080)");
        System.err.println("  -v|--verbose ");
        System.err.println("  -d|--docroot file (default 'src/test/webapp')");
        System.exit(1);
    }

    private Server server;

    private SelectChannelConnector _connector;
    private boolean _verbose;
    private WebSocketHandler _wsHandler;
    private ResourceHandler _rHandler;

    public ExampleEchoServer(int port)
    {
        server = new Server();
        _connector = new SelectChannelConnector();
        _connector.setPort(port);

        server.addConnector(_connector);
        _wsHandler = new WebSocketHandler()
        {
            @Override
            public void registerWebSockets(WebSocketServerFactory factory)
            {
                factory.register(BigEchoSocket.class);
                factory.setCreator(new EchoCreator());
            }
        };

        server.setHandler(_wsHandler);

        _rHandler = new ResourceHandler();
        _rHandler.setDirectoriesListed(true);
        _rHandler.setResourceBase("src/test/webapp");
        _wsHandler.setHandler(_rHandler);
    }

    public String getResourceBase()
    {
        return _rHandler.getResourceBase();
    }

    public boolean isVerbose()
    {
        return _verbose;
    }

    public void runForever() throws Exception
    {
        server.start();
        server.join();
    }

    public void setResourceBase(String dir)
    {
        _rHandler.setResourceBase(dir);
    }

    public void setVerbose(boolean verbose)
    {
        _verbose = verbose;
    }
}
