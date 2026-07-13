module org.eclipse.jetty.opentelemetry.server {
    exports org.eclipse.jetty.opentelemetry.server;

    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires io.opentelemetry.semconv;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.server;

}