#!groovy

// def jdks = ["jdk8","jdk9","jdk10","jdk11"]
def mainJdk = "jdk11"
def jdks = [mainJdk]
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
            // Compile only
            sh "mvn -V -B clean install -DskipTests -T6 -e"
            // Javadoc only
            sh "mvn -V -B javadoc:javadoc -T6 -e"
            // Testing
            sh "mvn -V -B install -Dmaven.test.failure.ignore=true -T5 -e -Djetty.testtracker.log=true -Pmongodb -Dunix.socket.tmp=" + env.JENKINS_HOME

            // Compact 3 build
            if (mainJdk) {
              sh "mvn -f aggregates/jetty-all-compact3 -V -B -Pcompact3 clean install -T5"
            }
          }
        }

        // Report failures in the jenkins UI
        junit testResults: '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
        consoleParsers = [[parserName: 'JavaDoc'],
                          [parserName: 'JavaC']];

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
                          ",**/org/eclipse/jetty/tests/**" + ",**/org/eclipse/jetty/test/**";
          jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                 exclusionPattern: jacocoExcludes,
                 execPattern: '**/target/jacoco.exec',
                 classPattern: '**/target/classes',
                 sourcePattern: '**/src/main/java'
          consoleParsers = [[parserName: 'Maven'],
                            [parserName: 'JavaDoc'],
                            [parserName: 'JavaC']];
        }

        // Report on Maven and Javadoc warnings
        step([$class        : 'WarningsPublisher',
              consoleParsers: consoleParsers])
      }
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy