//import org.codehaus.groovy.util.ReleaseInfo

def BuildFilesFolder = "/tmp/deleteThisFolder"

def call() {
    node {
        def outp = sh (
            script: 'printenv',
            returnStdout: true
        ).trim()
        echo outp
    }
    
    def MavenContainerName = "MyMavenContainer_" + env.BUILD_ID
    BuildFilesFolder = env.WORKSPACE + "/BuildResult_" + env.BUILD_ID

    def tasks = [:]
    def doPostProcessing = false
    def deployFinished = false
    
    tasks["Jenkins_Container"] = {
        while (!doPostProcessing) {
            sleep(1)
        }
        deploy()
        deployFinished = true
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
                                sh 'pwd'
                                sh 'printenv'
                                script {
                                    doPostProcessing = true
                                    while (!deployFinished) {
                                        sleep(1)
                                    }
                                }
                                sh 'mkdir /home/jenkinsbuild/.m2/'
                                sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
                            }
                        }
                        stage('build') {
                            steps {
                                sh 'mvn clean verify'
                                /*script {
                                    doPostProcessing = true
                                    while (!deployFinished) {
                                        sleep(1)
                                    }
                                }*/
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

def deploy() {
    node {
        sh "echo $BuildFilesFolder"
        sh "mkdir $BuildFilesFolder"
        sh "docker cp $MavenContainerName:/ jenkins:$BuildFilesFolder"
        sh "du -h $BuildFilesFolder"
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
