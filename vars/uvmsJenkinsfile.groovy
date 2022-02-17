def call(body) {
  
  // START evaluate the body block, and collect configuration into the object
  def inParams = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = inParams
  body()
  //END evaluate

  def MAVEN_PROFILES       = (inParams.MAVEN_PROFILES)      ?: ''
  def MAVEN_PROFILES_MAIN  = (inParams.MAVEN_PROFILES_MAIN) ?: ''
  def MAVEN_OPTS_MAIN      = (inParams.MAVEN_OPTS_MAIN)     ?: '-DskipTests'
  def Boolean DOCKER       = (inParams.DOCKER)              ?: false

  def LOCK_RESOURCE        = (DOCKER) ? 'Docker' : ''
  def version

  node {
    checkout scm
    def currentVersion = sh script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true
    version = currentVersion.replace("-SNAPSHOT", "")
  }

  pipeline {
    agent any
    options {
      lock resource: LOCK_RESOURCE
    }
    parameters {
      booleanParam(defaultValue: false, name: 'RELEASE', description: 'Create a release (develop branch only)')
      string(name: 'VERSION', defaultValue: version, description: 'Release version')
    }
    tools {
      maven 'Maven3'
      jdk 'JDK11'
    }
    triggers {
      issueCommentTrigger('.*test me.*')
    }
    stages {
      stage('Remove docker containers') {
        when {
          allOf {
            expression { DOCKER }
            expression { !params.RELEASE }
          }
        }
        steps {
          sh 'docker ps -a -q|xargs -r docker rm -f'
        }
      }
      stage('Build') {
        when {
          allOf {
            not { branch 'main' }
            expression { !params.RELEASE }
          }
        }
        steps {
          sh "mvn clean deploy $MAVEN_PROFILES -U"
        }
      }
      stage('Build main') {
        when {
          allOf {
            branch 'main'
            expression { !params.RELEASE }
          }
        }
        steps {
          sh "mvn clean deploy $MAVEN_OPTS_MAIN $MAVEN_PROFILES_MAIN -U"
        }
      }
      stage('SonarQube analysis') {
        when {
          allOf {
            branch 'develop'
            expression { !params.RELEASE }
          }
        }
        steps{ 
          withSonarQubeEnv('Sonarqube.com') {
            sh "mvn $SONAR_MAVEN_GOAL -Dsonar.dynamicAnalysis=reuseReports -Dsonar.host.url=$SONAR_HOST_URL -Dsonar.login=$SONAR_AUTH_TOKEN $SONAR_EXTRA_PROPS"
          }
        }
      }
      stage('Release') {
        when {
          allOf {
            branch 'develop'
            expression { params.RELEASE }
          }
        }
        steps {
          git branch: 'main', url: "$GIT_URL"
          git branch: 'develop', url: "$GIT_URL"
          sh "mvn -B gitflow:release -DskipTestProject -DreleaseVersion=${VERSION}"
        }
      }
    }
    post {
      always {
        deleteDir()
      }
    }
  }
}
