//import org.codehaus.groovy.util.ReleaseInfo

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    
    // TODO: Add project name and branch name
    def MavenContainerName = "MyMavenContainer_" + env.BUILD_ID
    def BuildFilesFolder = "BuildResult_" + env.BUILD_ID
    def MavenPwd = ""

    def tasks = [:]
    def doDeploy = false
    def doRelease = false
    def releaseVersion = "0.1"
    def doPostProcessing = false
    def postProcessingFinished = false
    
    tasks["Jenkins_Container"] = {
        while (!doPostProcessing) {
            sleep(5)
        }
        if(doDeploy) {
            postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, MavenPwd, doRelease, releaseVersion)
        }
        postProcessingFinished = true
    }

    tasks["Maven_Container"] = {
        pipeline {
            agent any

            parameters {
                booleanParam (name: 'RELEASE', defaultValue: false, description: 'Set true for Release')
            }

            environment {
                GIT_COMMIT_EMAIL = """${sh(returnStdout: true,script: 'git --no-pager show -s --format=\'%ae\'')}""".trim()
            }

            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            stages {
                stage('Build_Master') {
                    agent {
                        docker {
                            image 'custom_maven:latest'
                            args "-v /media/data/empty_maven_folder/:/root/.m2:ro -v /media/data/m2-cache/:/home/jenkinsbuild/tmp_cache -m 4G --storage-opt size=20G --network proxy --name $MavenContainerName"
                        }
                    }
                    when {
                        expression {
                            if (env.CHANGE_TARGET) {    // check pull request
                                return false
                            }
                            return env.GIT_BRANCH == 'master'
                        }
                    }
                    stages {
                        stage('load_cache') {
                            steps {
                                sh 'mkdir /home/jenkinsbuild/.m2/'
                                sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
                            }
                        }
                        stage('build') {
                            steps {
                                sh 'mvn clean verify'
                                script {
                                    MavenPwd = sh (
                                        script: 'pwd',
                                        returnStdout: true
                                    ).trim()
                                    doRelease = params.RELEASE
                                    doDeploy = true
                                    doPostProcessing = true
                                    while (!postProcessingFinished) {
                                        sleep(5)
                                    }
                                }
                            }
                        }
                        stage('save_cache') {
                            steps {
                                sh 'cp -r /home/jenkinsbuild/.m2/. /home/jenkinsbuild/tmp_cache/'
                            }
                        }
                    }
                }

                stage('Build_Slave') {
                    agent {
                        docker {
                            image 'custom_maven:latest'
                            args '-v /media/data/empty_maven_folder/:/root/.m2:ro -v /media/data/m2-cache/:/home/jenkinsbuild/tmp_cache:ro -m 4G --storage-opt size=20G --network proxy'
                        }
                    }
                    when {
                        expression {
                            if (env.CHANGE_TARGET) {
                                return true
                            }
                            return !(env.GIT_BRANCH == 'master')
                        }
                    }
                    stages {
                        stage('load_cache') {
                            steps {
                                sh 'mkdir /home/jenkinsbuild/.m2/'
                                sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
                            }
                        }
                        stage('build') {
                            steps {
                                sh 'mvn clean verify'
                                script {
                                    MavenPwd = sh (
                                        script: 'pwd',
                                        returnStdout: true
                                    ).trim()
                                    doPostProcessing = true
                                    while (!postProcessingFinished) {
                                        sleep(5)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            post {
                always {
                    // in case of a failure during the build
                    script {
                        doDeploy = false
                        doPostProcessing = true
                    }
                }
            }
        }
    }

    parallel tasks
}

def postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, MavenPwd, doRelease, releaseVersion) {
    node {
        def mandatoryParameters = ['sshConfigName', 'absoluteWebserverDir', 'webserverDir', 'updateSiteLocation']
        for (mandatoryParameter in mandatoryParameters) {
            if (!config.containsKey(mandatoryParameter) || config.get(mandatoryParameter).toString().trim().isEmpty()) {
                error "Missing mandatory parameter $mandatoryParameter"
            }
        }

        boolean skipCodeQuality = config.containsKey('skipCodeQuality') && config.get('skipCodeQuality').toString().trim().toBoolean()
        boolean skipNotification = config.containsKey('skipNotification') && config.get('skipNotification').toString().trim().toBoolean()

        sh "echo ${config.sshConfigName}"
        sh "echo ${config.absoluteWebserverDir}"
        sh "echo ${config.webserverDir}"
        sh "echo ${config.updateSiteLocation}"

        // TODO: move /$updateSiteLocation to 'docker cp'
        String usl = "$BuildFilesFolder/${config.updateSiteLocation}"

        sh "echo $usl"
        MavenPwd = MavenPwd + "/."
        sh "mkdir $BuildFilesFolder"
        sh "docker cp $MavenContainerName:$MavenPwd $BuildFilesFolder"

        try {
            // deploy:
            sshPublisher(
                failOnError: true,
                publishers: [
                    sshPublisherDesc(
                        configName: "${config.sshConfigName}",
                        transfers: [
                            sshTransfer(
                                sourceFiles: "$usl/**/*",
                                cleanRemote: true,
                                removePrefix: "$usl",
                                remoteDirectory: "${config.webserverDir}/nightly"
                            )
                        ]
                    )
                ]
            )
            if (doReleaseBuild) {
                sshPublisher(
                    failOnError: true,
                    publishers: [
                        sshPublisherDesc(
                            configName: "${config.sshConfigName}",
                            transfers: [
                                sshTransfer(
                                    execCommand:
                                    "rm -rf $absoluteWebserverDir/${config.webserverDir}/releases/latest &&" +
                                    "rm -rf $absoluteWebserverDir/${config.webserverDir}/releases/$releaseVersion &&" +
                                    "mkdir -p $absoluteWebserverDir/${config.webserverDir}/releases/$releaseVersion &&" +
                                    "cp -a $absoluteWebserverDir/${config.webserverDir}/nightly/* $absoluteWebserverDir/releases/$releaseVersion/ &&" +
                                    "ln -s $absoluteWebserverDir/${config.webserverDir}/releases/$releaseVersion $absoluteWebserverDir/${config.webserverDir}/releases/latest"
                                )
                            ]
                        )
                    ]
                )
            }

            // archive:
            archiveArtifacts "$usl/**/*"

            if (!skipCodeQuality) {
                sh "echo 'Publish JavaDoc'"
                publishHTML([
                    allowMissing: false,
                    alwaysLinkToLastBuild: false,
                    keepAll: false,
                    reportDir: "$usl/javadoc",
                    reportFiles: 'overview-summary.html',
                    reportName: 'JavaDoc',
                    reportTitles: ''
                ])

                checkstyle([
                    pattern: '**/target/checkstyle-result.xml'
                ])
                junit([
                    testResults: '**/surefire-reports/*.xml',
                    allowEmptyResults: true
                ])
                jacoco([
                    execPattern: '**/target/*.exec',
                    classPattern: '**/target/classes',
                    sourcePattern: '**/src,**/src-gen,**/xtend-gen',
                    inclusionPattern: '**/*.class',
                    exclusionPattern: '**/*Test*.class'
                ])
            }
            else {
                sh "echo 'Skip JavaDoc and CodeQuality'"
            }

        } catch (err) {
            sh "echo 'An error occured!'"
            String errMsg = err.getMessage()
            sh "echo $errMsg"
            currentBuild.result = 'FAILURE'
            if (err instanceof hudson.AbortException && err.getMessage().contains('script returned exit code 143')) {
                currentBuild.result = 'ABORTED'
            }
            if (err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException && err.causes.size() == 0) {
                currentBuild.result = 'ABORTED'
            }
            throw err
        }

        sh "rm -rf $BuildFilesFolder"
    }
}
