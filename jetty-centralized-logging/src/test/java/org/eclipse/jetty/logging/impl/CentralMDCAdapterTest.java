package org.eclipse.jetty.logging.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import junit.framework.TestCase;

import org.eclipse.jetty.logging.impl.TestAppender.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.impl.StaticLoggerBinder;

public class CentralMDCAdapterTest extends TestCase
{
    public void testMDCInfo() throws Exception
    {
        // Setup Logger Config
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");
        props.setProperty("root.appenders","test");
        props.setProperty("appender.test.class",TestAppender.class.getName());

        CentralLoggerConfig root = CentralLoggerConfig.load(props);
        StaticLoggerBinder.getSingleton().setRoot(root);

        // Generate a few logging events.
        Logger logroot = LoggerFactory.getLogger("test.root");
        logroot.info("The Phoenix and the Turtle");
        logroot.info("Let the bird of loudest lay");

        MDC.put("mood","sad");
        MDC.put("animal","bird");
        Logger logtree = LoggerFactory.getLogger("test.root.tree");
        logtree.info("On the sole Arabian tree,");
        logtree.info("Herald sad and trumpet be,");

        MDC.put("mood","soaring");
        Logger logwings = LoggerFactory.getLogger("test.root.wings");
        logwings.info("To whose sound chaste wings obey.");
        logwings.info("But thou shrieking harbinger,");
        logwings.info("Foul precurrer of the fiend,");

        MDC.remove("animal");
        Logger logend = LoggerFactory.getLogger("test.root.end");
        logend.info("Augur of the fever's end,");

        MDC.clear();
        logend.info("To this troop come thou not near.");

        // Assert Events
        TestAppender testappender = (TestAppender)root.findAppender(TestAppender.class);
        List<LogEvent> captured = testappender.getEvents();

        List<String> expectedMessages = new ArrayList<String>();
        expectedMessages.add("The Phoenix and the Turtle");
        expectedMessages.add("Let the bird of loudest lay");
        expectedMessages.add("On the sole Arabian tree,");
        expectedMessages.add("Herald sad and trumpet be,");
        expectedMessages.add("To whose sound chaste wings obey.");
        expectedMessages.add("But thou shrieking harbinger,");
        expectedMessages.add("Foul precurrer of the fiend,");
        expectedMessages.add("Augur of the fever's end,");
        expectedMessages.add("To this troop come thou not near.");

        assertEquals("Captured Messages size",expectedMessages.size(),captured.size());

        List<String> expectedMdc = new ArrayList<String>();
        expectedMdc.add("");
        expectedMdc.add("");
        expectedMdc.add("animal=bird, mood=sad");
        expectedMdc.add("animal=bird, mood=sad");
        expectedMdc.add("animal=bird, mood=soaring");
        expectedMdc.add("animal=bird, mood=soaring");
        expectedMdc.add("animal=bird, mood=soaring");
        expectedMdc.add("mood=soaring");
        expectedMdc.add("");

        assertEquals("Captured MDC events size",expectedMdc.size(),captured.size());

        for(int i=0, n=expectedMessages.size(); i<n; i++) {
            assertEquals("Message[" + i + "]", expectedMessages.get(i), captured.get(i).message);
            assertEquals("MDC[" + i + "]", expectedMdc.get(i), captured.get(i).mdc);
        }
    }
}
