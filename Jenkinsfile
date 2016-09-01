node {
  // System Dependent Locations
  def mvntool = tool name: 'maven3.0.5', type: 'hudson.tasks.Maven$MavenInstallation'
<<<<<<< HEAD
  def jdktool = tool name: 'jdk6', type: 'hudson.model.JDK'
=======
  def jdktool = tool name: 'jdk5', type: 'hudson.model.JDK'
>>>>>>> jetty-7

  // Environment
  List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}/", "MAVEN_HOME=${mvntool}"]
  mvnEnv.add("MAVEN_OPTS=-Xms256m -Xmx1024m -XX:MaxPermSize=512m -Djava.awt.headless=true")

  stage 'Checkout'

  checkout scm

  stage 'Build & Test'

  withEnv(mvnEnv) {
    sh "mvn -B clean install -Dmaven.test.failure.ignore=true"
    // Report failures in the jenkins UI
    step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
  }

  stage 'Javadoc'

  withEnv(mvnEnv) {
    sh "mvn -B javadoc:javadoc"
  }
}
