node {
  // System Dependent Locations
  def mvntool = tool name: 'maven3', type: 'hudson.tasks.Maven$MavenInstallation'
  def jdktool = tool name: 'jdk8', type: 'hudson.model.JDK'

  // Environment
  List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}/", "MAVEN_HOME=${mvntool}"]
  mvnEnv.add("MAVEN_OPTS=-Xms256m -Xmx1024m -Djava.awt.headless=true")

  stage 'Checkout'

  checkout scm

  stage 'Compile'

  withEnv(mvnEnv) {
    sh "mvn clean install -DskipTests"
  }

  stage 'Javadoc'

  withEnv(mvnEnv) {
    sh "mvn javadoc:jar"
  }

  stage 'Test'

  withEnv(mvnEnv) {
    sh "mvn test -Dmaven.test.failure.ignore=true"
  }

  stage 'Documentation'

  dir("jetty-documentation") {
    withEnv(mvnEnv) {
      sh "mvn clean install"
    }
  }

  stage 'Compact3'

  dir("aggregates/jetty-all-compact3") {
    withEnv(mvnEnv) {
      sh "${mvnHome}/bin/mvn -Pcompact3 clean install"
    }
  }
}
