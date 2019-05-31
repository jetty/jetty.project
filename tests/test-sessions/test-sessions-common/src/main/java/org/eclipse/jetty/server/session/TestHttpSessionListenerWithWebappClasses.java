package org.eclipse.jetty.server.session;

import javax.servlet.http.HttpSessionEvent;

/**
 * TestHttpSessionListenerWithWebappClasses
 * 
 * A session listener class that checks that sessionDestroyed
 * events can reference classes known only to the webapp, ie
 * that the calling thread has been correctly annointed with 
 * the webapp loader.
 *
 */
public class TestHttpSessionListenerWithWebappClasses extends TestHttpSessionListener
{
    public TestHttpSessionListenerWithWebappClasses()
    {
        super();
    }

    public TestHttpSessionListenerWithWebappClasses(boolean access)
    {
        super(access);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se)
    {
        //try loading a class that is known only to the webapp
        //to test that the calling thread has been properly
        //annointed with the webapp's classloader
        try
        {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass("Foo");
        }
        catch (Exception cnfe)
        {
            ex=cnfe;
        }
        super.sessionDestroyed(se);
    }

}
