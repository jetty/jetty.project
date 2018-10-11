#!groovy

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

      try {
        stage("Build ${jdk}/${os}") {
          timeout(time: 90, unit: 'MINUTES') {
            // Run test phase / ignore test failures
            checkout scm
            withMaven(
                    maven: mvnName,
                    jdk: "$jdk",
                    publisherStrategy: 'EXPLICIT',
                    globalMavenSettingsConfig: settingsName,
                    //options: [invokerPublisher(disabled: false)],
                    mavenOpts: mavenOpts,
                    mavenLocalRepo: localRepo) {
              sh "mvn -V -B install -Dmaven.test.failure.ignore=true -e -Pmongodb -T3 -Djetty.testtracker.log=true -Dunix.socket.tmp="+env.JENKINS_HOME
              sh "mvn -V -B javadoc:javadoc -T6 -e"
            }
            // withMaven doesn't label..
            // Report failures in the jenkins UI
            junit testResults:'**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
            consoleParsers = [[parserName: 'JavaDoc'],
                              [parserName: 'JavaC']];
            if (isMainBuild( jdk )) {
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
                     execPattern     : '**/target/jacoco.exec',
                     classPattern    : '**/target/classes',
                     sourcePattern   : '**/src/main/java'
              consoleParsers = [[parserName: 'Maven'],
                                [parserName: 'JavaDoc'],
                                [parserName: 'JavaC']];
              step([$class: 'MavenInvokerRecorder', reportsFilenamePattern: "**/target/invoker-reports/BUILD*.xml",
                    invokerBuildDir: "**/target/its"])
            }

            // Report on Maven and Javadoc warnings
            step( [$class        : 'WarningsPublisher',
                   consoleParsers: consoleParsers] )
          }
          if(isUnstable()) {
            notifyBuild("Unstable / Test Errors", jdk)
          }
        }
      } catch(Exception e) {
        notifyBuild("Test Failure", jdk)
        throw e
      }

      try
      {
        stage ("Compact3 - ${jdk}") {
>>>>>>> jetty-9.4.x
          withMaven(
                  maven: mvnName,
                  jdk: "$jdk",
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mavenOpts,
                  mavenLocalRepo: localRepo) {
            // Compile only
            sh "mvn -V -B clean install -DskipTests -T6 -e -Dmaven.test.failure.ignore=false"
            // Javadoc only
            sh "mvn -V -B javadoc:javadoc -T6 -e -Dmaven.test.failure.ignore=false"
            // Testing
            sh "mvn -V -B install -Dmaven.test.failure.ignore=true -T5 -e -Djetty.testtracker.log=true -Pmongodb -Dunix.socket.tmp=" + env.JENKINS_HOME

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
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
