//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test various {@link org.eclipse.jetty.http.ComplianceViolation} behaviors with actual requests.
 */
public class ComplianceTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    protected void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        if (serverConsumer != null)
            serverConsumer.accept(server);

        server.start();
    }

    public static Stream<Arguments> queryComplianceCases()
    {
        List<Arguments> cases = new ArrayList<>();
        for (UriCompliance compliance : List.of(UriCompliance.DEFAULT, UriCompliance.LEGACY, UriCompliance.RFC3986))
        {
            cases.add(Arguments.of(compliance, "query=blocked=%2F%2F%E6%84%9B%22",
                Map.of("query", "blocked=//愛\"")));
            cases.add(Arguments.of(compliance, "query=buster=1752874099305",
                Map.of("query", "buster=1752874099305")));
            cases.add(Arguments.of(compliance, "query=startup=1",
                Map.of("query", "startup=1")));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryComplianceCases")
    public void testQueryCompliance(UriCompliance compliance, String rawQuery, Map<String, String> expectedMap) throws Exception
    {
        Queue<ComplianceViolation.Event> events = new BlockingArrayQueue<>();

        ComplianceViolation.Listener listener = new ComplianceViolation.Listener()
        {
            @Override
            public void onComplianceViolation(ComplianceViolation.Event event)
            {
                new RuntimeException("Huh?").printStackTrace(System.err);
                events.add(event);
            }
        };

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setUriCompliance(compliance);
                    httpConfig.addComplianceViolationListener(listener);
                });

            server.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    try
                    {
                        StringBuilder body = new StringBuilder();
                        body.append("raw-path-query=").append(request.getHttpURI().getPathQuery());
                        Fields fields = Request.getParameters(request);
                        for (Fields.Field field : fields)
                        {
                            body.append("\nfield[").append(field.getName());
                            body.append("]=").append(field.getValues());
                        }
                        Content.Sink.write(response, true, body.toString(), callback);
                        return true;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            });
        });

        String rawRequest = """
            GET /test?%s HTTP/1.1
            Host: local
            Connection: close
            
            """.formatted(rawQuery);

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(rawQuery));
        expectedMap.forEach((key, value) -> assertThat(responseBody, containsString("field[%s]=[%s]".formatted(key, value))));

        // Shouldn't see any compliance violation events
        assertEquals(0, events.size(), () ->
        {
            StringBuilder msg = new StringBuilder();
            events.forEach(event -> msg.append("event:").append(event).append("\n"));
            return msg.toString();
        });
    }
}
