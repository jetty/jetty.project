package org.eclipse.jetty.test;

import javax.enterprise.inject.Produces;
import javax.inject.Named;

public class OldGreetings
{
    @Produces
    @Named("old")
    public Greetings getGreeting()
    {
        return () -> "Salutations!";
    }
}
