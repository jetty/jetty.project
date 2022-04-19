# Run this before committing to ensure you have not broken a module that should already be compiling.
# Remove the string from here when a modules should be compiling.

# Some of these exclusions are for modules which are skipped by maven because a
# dependant module has failed, but it may not have any compile errors itself.

ee9_allowed_failures=(
	"Jetty EE9 :: Handler"
	"EE9 :: Jetty :: Security"
	"EE9 :: Jetty :: Servlet Handling"
	"EE9 :: Jetty :: Webapp Application Support"
	"EE9 :: Jetty :: Plus"
	"EE9 :: Jetty :: Servlet Annotations"
	"EE9 :: Jetty :: Quick Start"
	"EE9 :: Jetty :: Extra Utility Servlets and Filters"
	"EE9 :: Jetty :: Websocket :: Servlet"
	"EE9 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Server"
	"EE9 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Client"
	"EE9 :: Jetty :: Websocket :: jakarta.websocket :: Server"
	"EE9 :: Jetty :: Apache JSP Implementation"
	"EE9 :: Glassfish :: JSTL module"
	"EE9 :: Jetty :: Proxy"
	"EE9 :: Jetty :: CDI"
	"EE9 :: Jetty :: JAAS"
	"EE9 :: Jetty :: OpenID"
	"EE9 :: Jetty :: JASPI Security"
	"EE9 :: Jetty Demo :: Async Rest :: Server"
	"EE9 :: Jetty Demo :: Jetty :: WebApp"
	"EE9 :: Jetty Demo :: Proxy :: Webapp"
	"EE9 :: Jetty Demo :: Embedded Jetty"
	"EE9 :: Jetty :: Ant Plugin"
	"EE9 :: Jetty :: FastCGI :: Proxy"
	"EE9 :: Jetty :: Http Service Provider Interface"
	"EE9 :: Jetty :: Jetty JSPC Maven Plugin"
	"EE9 :: Jetty :: Jetty Maven Plugin"
	"EE9 :: Jetty :: OSGi :: Boot"
	"EE9 :: Jetty :: OSGi :: Boot JSP"
	"EE9 :: Jetty :: OSGi :: HttpService"
	"EE9 :: Jetty :: OSGi :: Test WebApp"
	"EE9 :: Jetty :: OSGi :: Test Context"
	"EE9 :: Jetty :: OSGi :: Server"
	"EE9 :: Jetty :: OSGi :: Test"
	"EE9 :: Jetty :: Runner"
	"EE9 :: Jetty :: Websocket :: jakarta.websocket :: Tests"
	"EE9 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Tests"
	"EE9 :: Jetty Test :: CDI"
	"EE9 :: Jetty Test :: HTTP Client Transports"
	"EE9 :: Jetty Test :: Integrations"
	"EE9 :: Jetty Test :: JMX :: WebApp Integration Tests"
	"EE9 :: Jetty Test :: Login Service"
	"EE9 :: Jetty Test :: Jetty Quick Start"
	"EE9 :: Jetty Test :: Jetty Websocket Simple Webapp with WebSocketClient"
	"EE9 :: Jetty Test :: Jetty Websocket Simple Webapp with WebSocketClient"
)

ee10_allowed_failures=(
	"EE10 :: Jetty :: Webapp Application Support"
	"EE10 :: Jetty :: Proxy"
	"EE10 :: Jetty :: JAAS"
	"EE10 :: Jetty :: Apache JSP Implementation"
	"EE10 :: Jetty :: Http Service Provider Interface"
	"EE10 :: Jetty :: JASPI Security"
	"EE10 :: Jetty :: OpenID"
	"EE10 :: Jetty :: OSGi :: HttpService"
	"EE10 :: Jetty Test :: HTTP Client Transports"
	"EE10 :: Jetty :: Extra Utility Servlets and Filters"
	"EE10 :: Jetty :: Plus"
	"EE10 :: Jetty :: Servlet Annotations"
	"EE10 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Server"
	"EE10 :: Jetty :: Websocket :: jakarta.websocket :: Server"
	"EE10 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Client"
	"EE10 :: Jetty Test :: Jetty Websocket Simple Webapp with WebSocketClient"
	"EE10 :: Jetty Test :: Jetty Websocket Simple Webapp with WebSocketClient"
	"EE10 :: Jetty :: Websocket :: jakarta.websocket :: Tests"
	"EE10 :: Jetty :: Websocket :: org.eclipse.jetty.websocket :: Tests"
	"EE10 :: Jetty Demo :: Jetty :: WebApp"
	"EE10 :: Jetty Demo :: Proxy :: Webapp"
	"EE10 :: Jetty :: Quick Start"
	"EE10 :: Glassfish :: JSTL module"
	"EE10 :: Jetty :: Jetty Maven Plugin"
	"EE10 :: Jetty Demo :: Async Rest :: Server"
	"EE10 :: Jetty Demo :: Embedded Jetty"
	"EE10 :: Jetty :: Ant Plugin"
	"EE10 :: Jetty :: CDI"
	"Jetty EE10 :: Deployer"
	"EE10 :: Jetty :: Jetty JSPC Maven Plugin"
	"EE10 :: Jetty :: OSGi :: Boot"
	"EE10 :: Jetty :: OSGi :: Boot JSP"
	"EE10 :: Jetty :: OSGi :: Test WebApp"
	"EE10 :: Jetty :: OSGi :: Server"
	"EE10 :: Jetty :: OSGi :: Test"
	"EE10 :: Jetty :: Runner"
	"EE10 :: Jetty Test :: CDI"
	"EE10 :: Jetty Test :: Integrations"
	"EE10 :: Jetty Test :: JMX :: WebApp Integration Tests"
	"EE10 :: Jetty Test :: Login Service"
	"EE10 :: Jetty Test :: Jetty Quick Start"
)

misc_allowed_failures=(
	"Jetty :: Infinispan Session Manager Embedded with Querying"
	"Jetty :: Infinispan Session Manager Remote with Querying"
	"Jetty Examples"
	"Jetty :: Hazelcast Session Manager"
	"Jetty :: Memcached :: Sessions"
	"Jetty :: Home Assembly"
	"test-distribution"
	"Jetty :: Documentation :: AsciiDoctor Extensions"
	"Jetty :: Documentation"
	"Test :: Jetty HTTP2 Webapp"
	"Jetty Tests :: WebApp :: RFC2616"
	"Test :: Jetty HTTP2 Webapp"
	"Jetty Tests :: Sessions :: Common"
	"Jetty Tests :: Sessions :: File"
	"Jetty Tests :: Sessions :: JDBC"
	"Jetty Tests :: Sessions :: Mongo"
	"Jetty Tests :: Sessions :: Infinispan"
	"Jetty Tests :: Sessions :: GCloud"
	"Jetty Tests :: Sessions :: Memcached"
	"Jetty Tests :: Sessions :: Hazelcast"
	"BUILD FAILURE"
)

declare -a allowed_failures
declare -a failures

isAllowedFailure ()
{
	failure=$1
	for allowedFailure in "${allowedFailures[@]}"; do
		if [[ $failure =~ $allowedFailure ]]; then
			return 0
		fi
	done
	return 1
}

reportFailures()
{
		for failure in "${failures[@]}"; do
			if ! isAllowedFailure $failure; then
				echo "$failure"
			fi
		done
}

output=$( mvn clean install -DskipTests -T 1C -fae 2>&1 )
results=$( echo "$output" | egrep '^\[INFO\] .* (SUCCESS|FAILURE|SKIPPED)' )
if [ "$(echo "$results" | wc -l)" -lt "10" ]; then
	echo "Unknown Error"
	exit 1
fi

## Jetty Core.
IFS=$'\n' failures=( `echo "$results" | egrep 'Core' | egrep -v 'SUCCESS'` )
allowedFailures=()
reportFailures

## EE9
IFS=$'\n' failures=( `echo "$results" | egrep 'EE9' | egrep -v 'SUCCESS'` )
allowedFailures=( ${ee9_allowed_failures[@]} )
reportFailures

## EE10
IFS=$'\n' failures=( `echo "$results" | egrep 'EE10' | egrep -v 'SUCCESS'` )
allowedFailures=( ${ee10_allowed_failures[@]} )
reportFailures

### Misc Failures
IFS=$'\n' failures=( `echo "$results" | egrep -v '(Core|EE9|EE10)' | egrep -v 'SUCCESS'` )
allowedFailures=( ${misc_allowed_failures[@]} )
reportFailures