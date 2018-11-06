#!groovy

def mainJdk = "jdk8"
def jdks = [mainJdk, "jdk11"]
def oss = ["linux"]
def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os+"_"+jdk] = getFullBuild( jdk, os, mainJdk == jdk )
  }
}

parallel builds

def getFullBuild(jdk, os, mainJdk) {
  return {
    node(os) {
      // System Dependent Locations
      def mvnName = 'maven3.5'
      def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}" // ".repository" //
      def settingsName = 'oss-settings.xml'
      def mavenOpts = '-Xms1g -Xmx4g -Djava.awt.headless=true'

      stage("Build / Test - $jdk") {
        timeout(time: 120, unit: 'MINUTES') {
          // Checkout
          checkout scm
          withMaven(
                  maven: mvnName,
                  jdk: "$jdk",
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mavenOpts,
                  mavenLocalRepo: localRepo) {
            // Testing
            sh "mvn -V -B install -Dmaven.test.failure.ignore=true -T5 -e -Djetty.testtracker.log=true -Pmongodb -Dunix.socket.tmp=" + env.JENKINS_HOME
            // Javadoc only
            sh "mvn -V -B javadoc:javadoc -T6 -e -Dmaven.test.failure.ignore=false"
          }
        }

        // Report failures in the jenkins UI
        junit testResults: '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
        consoleParsers = [[parserName: 'JavaDoc'],
                          [parserName: 'JavaC']]

        if (mainJdk) {
          // Collect up the jacoco execution results
          def jacocoExcludes =
                  // build tools
                  "**/org/eclipse/jetty/ant/**" + ",**/org/eclipse/jetty/maven/**" +
                          ",**/org/eclipse/jetty/jspc/**" +
                          // example code / documentation
                          ",**/org/eclipse/jetty/embedded/**" + ",**/org/eclipse/jetty/asyncrest/**" +
                          ",**/org/eclipse/jetty/demo/**" +
                          // special environments / late integrations
                          ",**/org/eclipse/jetty/gcloud/**" + ",**/org/eclipse/jetty/infinispan/**" +
                          ",**/org/eclipse/jetty/osgi/**" + ",**/org/eclipse/jetty/spring/**" +
                          ",**/org/eclipse/jetty/http/spi/**" +
                          // test classes
                          ",**/org/eclipse/jetty/tests/**" + ",**/org/eclipse/jetty/test/**"
          jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                 exclusionPattern: jacocoExcludes,
                 execPattern: '**/target/jacoco.exec',
                 classPattern: '**/target/classes',
                 sourcePattern: '**/src/main/java'
          consoleParsers = [[parserName: 'Maven'],
                            [parserName: 'JavaDoc'],
                            [parserName: 'JavaC']]

          step([$class: 'MavenInvokerRecorder', reportsFilenamePattern: "**/target/invoker-reports/BUILD*.xml",
                invokerBuildDir: "**/target/its"])
        }

        // Report on Maven and Javadoc warnings
        step([$class        : 'WarningsPublisher',
              consoleParsers: consoleParsers])
      }

      stage ("Compact3 - ${jdk}") {
        withMaven(
            maven: mvnName,
            jdk: "$jdk",
            publisherStrategy: 'EXPLICIT',
            globalMavenSettingsConfig: settingsName,
            mavenOpts: mavenOpts,
            mavenLocalRepo: localRepo) {
          sh "mvn -f aggregates/jetty-all-compact3 -V -B -Pcompact3 clean install -T6"
        }
      }
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy