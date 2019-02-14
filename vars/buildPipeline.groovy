//import org.codehaus.groovy.util.ReleaseInfo

def call() {
    // TODO: Get following variables with parameters:
    String sshConfigName = "updatesites.web.mdsd.tools"
    String absoluteWebserverDir = "/home/deploy/writable"
    String webserverDir = "simulizar"
    String updateSiteLocation = "releng/org.palladiosimulator.simulizar.updatesite/target/repository"
    
    // TODO: Add project name and branch name
    def MavenContainerName = "MyMavenContainer_" + env.BUILD_ID
    def BuildFilesFolder = "BuildResult_" + env.BUILD_ID
    def MavenPwd = ""

    def tasks = [:]
    def doPostProcessing = false
    def postProcessingFinished = false
    
    tasks["Jenkins_Container"] = {
        while (!doPostProcessing) {
            sleep(5)
        }
        deploy(BuildFilesFolder, MavenContainerName, MavenPwd, absoluteWebserverDir, webserverDir, updateSiteLocation, sshConfigName)
        postProcessingFinished = true
    }
    
    tasks["Maven_Container"] = {
        pipeline {
            agent any
            environment {
                workspaceMaster = ''
                workspaceSlave = ''
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
                    post {
                        always {
                            script {
                                workspaceMaster = env.WORKSPACE	
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
                            }
                        }
                    }
                    post {
                        always {
                            script {
                                workspaceSlave = env.WORKSPACE
                            }
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        cleanWorkspace("${env.WORKSPACE}")
                        if(env.GIT_BRANCH == 'master') {
                            cleanWorkspace(workspaceMaster)
                        } else {
                            cleanWorkspace(workspaceSlave)
                        }
                    }
                }
            }
        }
    }

    parallel tasks
}

def deploy(BuildFilesFolder, MavenContainerName, MavenPwd, absoluteWebserverDir, webserverDir, updateSiteLocation, sshConfigName) {
    node {    
        // TODO: move /$updateSiteLocation to 'docker cp'
        String usl = "$BuildFilesFolder/$updateSiteLocation"

        sh "echo $usl"
        MavenPwd = MavenPwd + "/."
        sh "mkdir $BuildFilesFolder"
        sh "docker cp $MavenContainerName:$MavenPwd $BuildFilesFolder"

        try {
            sshPublisher(
                failOnError: true,
                publishers: [
                    sshPublisherDesc(
                        configName: "$sshConfigName",
                        transfers: [
                            sshTransfer(
                                execCommand:
                                "mkdir -p $absoluteWebserverDir/$webserverDir/nightly &&" +
                                "rm -rf $absoluteWebserverDir/$webserverDir/nightly/*"
                            ),
                            sshTransfer(
                                sourceFiles: "$usl/**/*",
                                cleanRemote: true,
                                removePrefix: "$usl",
                                remoteDirectory: "$webserverDir/nightly"
                            )
                        ]
                    )
                ]
            )
        } catch (err) {
            sh "echo 'An error occured!'"
            String errMsg = err.getMessage()
            sh "echo $errMsg"
            /*currentBuild.result = 'FAILURE'
            if (err instanceof hudson.AbortException && err.getMessage().contains('script returned exit code 143')) {
                currentBuild.result = 'ABORTED'
            }
            if (err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException && err.causes.size() == 0) {
                currentBuild.result = 'ABORTED'
            }
            throw err*/
        }
        
        sh "rm -rf $BuildFilesFolder"
    }
}

def cleanWorkspace(workspaceDir) {
	dir(workspaceDir) {
	  deleteDir()
	}
	dir(workspaceDir + "@tmp") {
	  deleteDir()
	}
	dir(workspaceDir + "@script") {
	  deleteDir()
	}
	dir(workspaceDir + "@script@tmp") {
	  deleteDir()
	}
}
