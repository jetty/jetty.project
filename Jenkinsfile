#!groovy

node("linux") {
  // System Dependent Locations
  def jdk = 'jdk7'
  def mvntool = tool name: 'maven3.5', type: 'hudson.tasks.Maven$MavenInstallation'
  def jdktool = tool name: $jdk, type: 'hudson.model.JDK'
  def mvnName = 'maven3.5'
  def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}" // ".repository" //
  def settingsName = 'oss-settings.xml'
  def mavenOpts = '-Xms1g -Xmx4g -XX:MaxPermSize=512m -Djava.awt.headless=true -Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2'

  // Environment
  List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}/", "MAVEN_HOME=${mvntool}"]
  mvnEnv.add("MAVEN_OPTS=$mavenOpts")

  stage('Checkout') {
    checkout scm
  }

  stage('Compile') {
    withEnv(mvnEnv) {
      timeout(time: 15, unit: 'MINUTES') {
        withmaven(
            maven: mvnName,
            jdk: "$jdk",
            publisherStrategy: 'EXPLICIT',
            globalMavenSettingsConfig: settingsName,
            mavenOpts: mavenOpts,
            mavenLocalRepo: localRepo) {
          sh "mvn -B clean install -Dtest=None"
        }
      }
    }
  }

  stage('Javadoc') {
    withEnv(mvnEnv) {
      timeout(time: 15, unit: 'MINUTES') {
        withmaven(
            maven: mvnName,
            jdk: "$jdk",
            publisherStrategy: 'EXPLICIT',
            globalMavenSettingsConfig: settingsName,
            mavenOpts: mavenOpts,
            mavenLocalRepo: localRepo) {
          sh "mvn -B javadoc:javadoc"
        }
      }
    }
  }

  stage('Test') {
    withEnv(mvnEnv) {
      timeout(time: 60, unit: 'MINUTES') {
        withmaven(
            maven: mvnName,
            jdk: "$jdk",
            publisherStrategy: 'EXPLICIT',
            globalMavenSettingsConfig: settingsName,
            mavenOpts: mavenOpts,
            mavenLocalRepo: localRepo) {
          // Run test phase / ignore test failures
          sh "mvn -B install -Dmaven.test.failure.ignore=true"
          // Report failures in the jenkins UI
          step([$class     : 'JUnitResultArchiver',
                testResults: '**/target/surefire-reports/TEST-*.xml'])
          // Collect up the jacoco execution results
          def jacocoExcludes =
              // build tools
              "**/org/eclipse/jetty/ant/**" +
              ",**/org/eclipse/jetty/maven/**" +
              ",**/org/eclipse/jetty/jspc/**" +
              // example code / documentation
              ",**/org/eclipse/jetty/embedded/**" +
              ",**/org/eclipse/jetty/asyncrest/**" +
              ",**/org/eclipse/jetty/demo/**" +
              // special environments / late integrations
              ",**/org/eclipse/jetty/gcloud/**" +
              ",**/org/eclipse/jetty/infinispan/**" +
              ",**/org/eclipse/jetty/osgi/**" +
              ",**/org/eclipse/jetty/spring/**" +
              ",**/org/eclipse/jetty/http/spi/**" +
              // test classes
              ",**/org/eclipse/jetty/tests/**" +
              ",**/org/eclipse/jetty/test/**";
          step([$class: 'JacocoPublisher',
                inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                exclusionPattern: jacocoExcludes,
                execPattern: '**/target/jacoco.exec',
                classPattern: '**/target/classes',
                sourcePattern: '**/src/main/java'])
          // Report on Maven and Javadoc warnings
          step([$class: 'WarningsPublisher',
                consoleParsers: [
                  [parserName: 'Maven'],
                  [parserName: 'JavaDoc'],
                  [parserName: 'JavaC']
              ]])
          }
      }
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
