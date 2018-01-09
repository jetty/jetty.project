//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * <p>Main class that starts the gateway server.</p>
 * <p>This class supports the following arguments:</p>
 * <ul>
 * <li>--port=&lt;port&gt; specifies the port on which the gateway server listens to, by default 8080</li>
 * <li>--retriever=&lt;retriever&gt; specifies the
 * {@link GatewayServer#setTargetIdRetriever(TargetIdRetriever) target id retriever}</li>
 * <li>--resources=&lt;resources file path&gt; specifies the resource file path for the gateway</li>
 * </ul>
 * <p>Examples</p>
 * <p> <tt>java --port=8080</tt> </p>
 * <p> <tt>java --port=8080 --resources=/tmp/gateway-resources</tt> </p>
 * <p> <tt>java --port=8080 --retriever=standard</tt> </p>
 * <p> <tt>java --port=8080 --retriever=host,.rhttp.example.com</tt> </p>
 * <p>The latter example specifies the {@link HostTargetIdRetriever} with a suffix of <tt>.rhttp.example.com</tt></p>
 *
 * @see GatewayServer
 * @version $Revision$ $Date$
 */
public class Main
{
    private static final String PORT_ARG = "port";
    private static final String RESOURCES_ARG = "resources";
    private static final String RETRIEVER_ARG = "retriever";

    public static void main(String[] args) throws Exception
    {
        Map<String, Object> arguments = parse(args);

        int port = 8080;
        if (arguments.containsKey(PORT_ARG))
            port = (Integer)arguments.get(PORT_ARG);

        String resources = null;
        if (arguments.containsKey(RESOURCES_ARG))
            resources = (String)arguments.get(RESOURCES_ARG);

        TargetIdRetriever retriever = null;
        if (arguments.containsKey(RETRIEVER_ARG))
            retriever = (TargetIdRetriever)arguments.get(RETRIEVER_ARG);

        GatewayServer server = new GatewayServer();

        Connector connector = new SelectChannelConnector();
        connector.setPort(port);
        server.addConnector(connector);

        if (resources != null)
        {
            server.getContext().setResourceBase(resources);
            ServletHolder resourcesServletHolder = server.getContext().addServlet(DefaultServlet.class,"__r/*");
            resourcesServletHolder.setInitParameter("dirAllowed", "true");
        }

        if (retriever != null)
            server.setTargetIdRetriever(retriever);

        server.start();
    }

    private static Map<String, Object> parse(String[] args)
    {
        Map<String, Object> result = new HashMap<String, Object>();

        Pattern pattern = Pattern.compile("--([^=]+)=(.+)");
        for (String arg : args)
        {
            Matcher matcher = pattern.matcher(arg);
            if (matcher.matches())
            {
                String argName = matcher.group(1);
                if (PORT_ARG.equals(argName))
                {
                    result.put(PORT_ARG, Integer.parseInt(matcher.group(2)));
                }
                else if (RESOURCES_ARG.equals(argName))
                {
                    String argValue = matcher.group(2);
                    result.put(RESOURCES_ARG, argValue);
                }
                else if (RETRIEVER_ARG.equals(argName))
                {
                    String argValue = matcher.group(2);
                    if (argValue.startsWith("host,"))
                    {
                        String[] typeAndSuffix = StringUtil.split(argValue);
                        if (typeAndSuffix.length != 2)
                            throw new IllegalArgumentException("Invalid option " + arg + ", must be of the form --" + RETRIEVER_ARG + "=host,suffix");

                        result.put(RETRIEVER_ARG, new HostTargetIdRetriever(typeAndSuffix[1]));
                    }
                }
            }
        }

        return result;
    }
}
