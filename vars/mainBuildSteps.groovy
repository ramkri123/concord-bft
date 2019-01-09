import groovy.json.*

def call(){
  pipeline {
    agent any
    tools {
        nodejs 'Node 8.9.1'
    }
    parameters {
      booleanParam defaultValue: false, description: "Whether to deploy the docker images for production. REQUIRES A VERSION NUMBER IN THE 'version_param' FIELD.", name: "deploy"
      string defaultValue: "",
             description: "The version number for releases. Used as a tag in DockerHub and GitHub.  REQUIRED IF THE 'deploy' CHECKBOX IS CHECKED.",
             name: "version_param"

      string defaultValue: "",
             description: "Blockchain commit or branch to use.  Providing a branch name will pull the branch's latest commit.",
             name: "blockchain_branch_or_commit"
      string defaultValue: "",
             description: "Shared Jenkins lib branch to use.",
             name: "shared_lib_branch"
    }
    stages {
      stage("Check parameters"){
        steps{
          script{
            errString = "Parameter check error: "

            if (params.deploy && (!params.version_param || !params.version_param.trim())){
              throw new Exception (errString + "A version number must be entered when the 'deploy' checkbox is checked.")
            }
          }
        }
      }

      stage("Clean") {
        steps {
          cleanWs()
        }
      }

      stage('Fetch source code') {
        parallel {
          stage("Fetch blockchain repo source") {
            steps {
              sh 'mkdir blockchain'
              dir('blockchain') {
                script {
                  env.commit = getRepoCode("git@github.com:vmwathena/blockchain.git", params.blockchain_branch_or_commit)
                }
              }
            }
          }
        }
      }

      stage("Copy dependencies") {
        parallel {
          stage("Copy googletest") {
            steps() {
              sh 'mkdir googletest'
              dir('googletest') {
                sh 'cp -ar /var/jenkins/workspace/googletest/* .'
              }
            }
          }
          stage("Copy evmjit") {
            steps() {
              sh 'mkdir evmjit'
              dir('evmjit') {
                sh 'cp -ar /var/jenkins/workspace/evmjit/* .'
              }
            }
          }
          stage("Copy etherium tests") {
            steps() {
              sh 'mkdir ethereum_tests'
              dir('ethereum_tests') {
                sh 'cp -ar /var/jenkins/workspace/ethereum_tests/* .'
              }
            }
          }
        }
      }

      stage('Write version for GUI') {
        steps() {
          dir('blockchain') {
            script {
              version = env.version_param ? env.version_param : env.commit
              env.version_json = createVersionInfo(version, env.commit)
            }
            // The groovy calls to create directories and files fail, only in Jenkins,
            // so do those in a shell block.  Jenkins has some quirky ideas of security?
            sh '''
              dir=ui/src/static/data
              mkdir -p ${dir}
              echo ${version_json} > ${dir}/version.json
            '''
          }
        }
      }

      stage('Build product prereqs') {
        parallel {
          stage('Build Communication') {
            steps {
              dir ('blockchain/communication') {
                sh 'mvn clean install'
              }
            }
          }
        }
      }

      stage('Build products') {
        parallel {
          stage('Build Concord') {
            steps {
              dir('blockchain/concord') {
                sh '''currentDir=`pwd`
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/concord1.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/concord2.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/concord3.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/concord4.config

                git submodule init
                git submodule update --recursive
                mkdir -p build
                cd build
                cmake ..
                make'''
              }
            }
          }
          stage("Build Helen") {
            steps {
              dir('blockchain/helen') {
                sh 'mvn clean install'
              }
            }
          }
          stage("Build EthRPC") {
            steps {
              dir('blockchain/ethrpc') {
                sh 'mvn clean install'
              }
            }
          }
        }
      }

      stage("Configure docker and git") {
        steps {
          // Docker will fail to launch unless we fix up this DNS stuff.  It will try to use Google's
          // DNS servers by default, and here in VMware's network, we can't do that.
          // Also, since this will run on a VM which may have been deployed anywhere in the world,
          // do not hard code the DNS values.  Always probe the current environment and write
          // this file.
          // Reference: https://development.robinwinslow.uk/2016/06/23/fix-docker-networking-dns/
          withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
            sh '''
              DNS_JSON_STRING=$(echo {\\"dns\\": [\\"`nmcli dev show | grep 'IP4.DNS' | cut --delimiter=':' --fields=2 | xargs | sed 's/\\s/", "/g'`\\"]})
              echo "${PASSWORD}" | sudo -S ls > /dev/null
              echo $DNS_JSON_STRING | sudo tee -a /etc/docker/daemon.json
              sudo service docker restart
            '''
          }

          withCredentials([string(credentialsId: 'ATHENA_DEPLOYER_ARTIFACTORY_PASSWORD', variable: 'ARTIFACTORY_PASSWORD')]) {
            sh '''
              docker login -u athena-deployer -p "${ARTIFACTORY_PASSWORD}" athena-docker-local.artifactory.eng.vmware.com
            '''
          }

          // To invoke "git tag" and commit that change, git wants to know who we are.
          // This will be set up in template VM version 5, at which point these commands can
          // be removed.
          sh '''
            git config --global user.email "vmwathenabot@vmware.com"
            git config --global user.name "build system"
          '''

          // These are constants which mirror the DockerHub repos.  DockerHub is only used for publishing releases.
          script {
            env.concord_repo = 'vmwblockchain/concord-core'
            env.helen_repo = 'vmwblockchain/concord-ui'
            env.ethrpc_repo = 'vmwblockchain/ethrpc'
            env.fluentd_repo = 'vmwblockchain/fluentd'
            env.ui_repo = 'vmwblockchain/ui'
          }

          // These are constants related to labels.
          script {
            env.version_label = 'com.vmware.blockchain.version'
            env.commit_label = 'com.vmware.blockchain.commit'
          }
        }
      }

      stage("Build docker images") {
        parallel {
          stage("Build helen docker image") {
            steps {
              script {
                dir('blockchain/helen') {

                 script {
                    env.helen_docker_tag = env.version_param ? env.version_param : env.commit
                  }

                  withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                    sh '''
                      docker build . -t "${helen_repo}:${helen_docker_tag}" --label ${version_label}=${helen_docker_tag} --label ${commit_label}=${actual_blockchain_fetched}
                    '''
                  }
                }
              }
            }
          }

          stage("Build ethrpc docker image") {
            steps {
              script {
                dir('blockchain/ethrpc') {

                 script {
                    env.ethrpc_docker_tag = env.version_param ? env.version_param : env.commit
                  }

                  withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                    sh '''
                      docker build . -t "${ethrpc_repo}:${ethrpc_docker_tag}" --label ${version_label}=${ethrpc_docker_tag} --label ${commit_label}=${actual_blockchain_fetched}
                    '''
                  }
                }
              }
            }
          }

          stage('Build concord docker image') {
            steps {
              script {
                dir('blockchain/concord') {
                  script {
                    env.concord_docker_tag = env.version_param ? env.version_param : env.commit
                  }
                  withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                    sh '''
                      docker build .. -f Dockerfile -t "${concord_repo}:${concord_docker_tag}" --label ${version_label}=${concord_docker_tag} --label ${commit_label}=${actual_blockchain_fetched}
                    '''
                  }
                }
              }
            }
          }

          stage('Build fluentd docker image') {
            steps {
              script {
                dir('blockchain/concord/docker') {
                  script {
                    env.fluentd_tag = env.version_param ? env.version_param : env.commit
                  }
                  withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                    sh '''
                      docker-compose build fluentd
                    '''
                  }
                }
              }
            }
          }

          stage('Build ui docker image') {
            steps {
              script {
                dir('blockchain/concord/docker') {
                  script {
                    env.ui_docker_tag = env.version_param ? env.version_param : env.commit
                  }
                  withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                    sh '''
                      docker-compose build ui
                    '''
                  }
                }
              }
            }
          }
        }
      }

      stage("Run tests in containers") {
        steps {
          dir('blockchain/hermes'){
            withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
              script {
                env.test_log_root = new File(env.WORKSPACE, "testLogs").toString()
                env.core_vm_test_logs = new File(env.test_log_root, "CoreVM").toString()
                env.helen_api_test_logs = new File(env.test_log_root, "HelenAPI").toString()
                env.extended_rpc_test_logs = new File(env.test_log_root, "ExtendedRPC").toString()
                env.regression_test_logs = new File(env.test_log_root, "Regression").toString()
                env.statetransfer_test_logs = new File(env.test_log_root, "StateTransfer").toString()
              }

              sh '''
                echo "${PASSWORD}" | sudo -S ls
                sudo cat >.env <<EOF
concord_repo=${concord_repo}
concord_tag=${concord_docker_tag}
helen_repo=${helen_repo}
helen_tag=${helen_docker_tag}
ethrpc_repo=${ethrpc_repo}
ethrpc_tag=${ethrpc_docker_tag}
fluentd_repo=${fluentd_repo}
fluentd_tag=${fluentd_tag}
ui_repo=${ui_repo}
ui_tag=${ui_docker_tag}
EOF
              '''

              sh '''
                echo "${PASSWORD}" | sudo -S ./main.py CoreVMTests --dockerComposeFile ../concord/docker/docker-compose.yml --resultsDir "${core_vm_test_logs}"
                echo "${PASSWORD}" | sudo -S ./main.py HelenAPITests --dockerComposeFile ../concord/docker/docker-compose.yml --resultsDir "${helen_api_test_logs}"
                echo "${PASSWORD}" | sudo -S ./main.py ExtendedRPCTests --dockerComposeFile ../concord/docker/docker-compose.yml --resultsDir "${extended_rpc_test_logs}"
                echo "${PASSWORD}" | sudo -S ./main.py RegressionTests --dockerComposeFile ../concord/docker/docker-compose.yml --resultsDir "${regression_test_logs}"
                echo "${PASSWORD}" | sudo -S ./main.py SimpleStateTransferTest --dockerComposeFile ../concord/docker/docker-compose.yml --resultsDir "${statetransfer_test_logs}"
              '''
            }
          }
        }
      }

      stage("Push to docker repository") {
        when {
          environment name: 'deploy', value: 'true'
        }
        steps {
          dir('blockchain') {
            createAndPushTag(env.version_param)
          }

          withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
            sh '''
              docker logout
              docker login -u blockchainrepositorywriter -p "${DOCKERHUB_PASSWORD}"

              # Keep these echo lines for test runs.
              # echo Would run docker push ${concord_repo}:${version_param}
              # echo Would run docker tag ${concord_repo}:${version_param} ${concord_repo}:latest
              # echo Would run docker push ${concord_repo}:latest
              docker push ${concord_repo}:${version_param}
              docker tag ${concord_repo}:${version_param} ${concord_repo}:latest
              docker push ${concord_repo}:latest

              # echo Would run docker push ${helen_repo}:${version_param}
              # echo Would run docker tag ${helen_repo}:${version_param} ${helen_repo}:latest
              # echo Would run docker push ${helen_repo}:latest
              docker push ${helen_repo}:${version_param}
              docker tag ${helen_repo}:${version_param} ${helen_repo}:latest
              docker push ${helen_repo}:latest

              # echo Would run docker push ${ethrpc_repo}:${version_param}
              # echo Would run docker tag ${ethrpc_repo}:${version_param} ${ethrpc_repo}:latest
              # echo Would run docker push ${ethrpc_repo}:latest
              docker push ${ethrpc_repo}:${version_param}
              docker tag ${ethrpc_repo}:${version_param} ${ethrpc_repo}:latest
              docker push ${ethrpc_repo}:latest

              # echo Would run docker push ${fluentd_repo}:${version_param}
              # echo Would run docker tag ${fluentd_repo}:${version_param} ${fluentd_repo}:latest
              # echo Would run docker push ${fluentd_repo}:latest
              docker push ${fluentd_repo}:${version_param}
              docker tag ${fluentd_repo}:${version_param} ${fluentd_repo}:latest
              docker push ${fluentd_repo}:latest

              # echo Would run docker push ${ui_repo}:${version_param}
              # echo Would run docker tag ${ui_repo}:${version_param} ${ui_repo}:latest
              # echo Would run docker push ${ui_repo}:latest
              docker push ${ui_repo}:${version_param}
              docker tag ${ui_repo}:${version_param} ${ui_repo}:latest
              docker push ${ui_repo}:latest

              docker logout
            '''
          }

          dir('blockchain/vars') {
            script {
              release_notification_address_file = "release_notification_recipients.txt"

              if (fileExists(release_notification_address_file)) {
                release_notification_recipients = readFile(release_notification_address_file).replaceAll("\n", " ")
                emailext body: "Changes: \n" + getChangesSinceLastTag(),
                     to: release_notification_recipients,
                     subject: "[Build] Concord version " + env.version_param + " has been pushed to DockerHub."
              }
            }
          }
        }
      }
    }// End stages

    post {
      always {
        // Files created by the docker run belong to root because they were created by the docker process.
        // That will make the subsequent run unable to clean the workspace.  Just make the entire workspace dir
        // belong to builder to catch any future files.
        dir(env.WORKSPACE){
          withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
            sh '''
              echo "${PASSWORD}" | sudo -S chown -R builder:builder .
            '''
          }
        }

        archiveArtifacts artifacts: "**/*.log", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/*.json", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/*.html", allowEmptyArchive: true

        echo 'Sending email notification...'
        emailext body: "${currentBuild.currentResult}: Job ${env.JOB_NAME} build ${env.BUILD_NUMBER}\n More info at: ${env.BUILD_URL}",
        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']],
        subject: "Jenkins Build ${currentBuild.currentResult}: Job ${env.JOB_NAME}"

      }
    }
  }
}

// The user's parameter is top priority, and if it fails, let an exception be thrown.
// First, tries to fetch at branch_or_commit.
// Next, get master.
// Next, try to get BRANCH_NAME.  If getting BRANCH_NAME fails, we are probably testing
// a branch that is in only in one or two of the repos.  That's fine.
// Returns the short form commit hash.
String getRepoCode(repo_url, branch_or_commit){
  refPrefix = "refs/heads/"

  if (branch_or_commit && branch_or_commit.trim()){
    // We don't know if this was a branch or a commit, so don't add the refPrefix.
    checkoutRepo(repo_url, branch_or_commit)
  }else if (env.BRANCH_NAME && env.BRANCH_NAME.trim()){
    // When launched via the multibranch pipeline plugin, there is a BRANCH_NAME
    // environment variable.
    checkoutRepo(repo_url, refPrefix + env.BRANCH_NAME)
  }else{
    // This was launched some other way. Just get latest.
    checkoutRepo(repo_url, "master")
  }

  return sh (
    script: 'git rev-parse --short HEAD',
    returnStdout: true
  ).trim()
}

// All that varies for each repo is the branch, so wrap this very large call.
void checkoutRepo(repo_url, branch_or_commit){
  checkout([$class: 'GitSCM', branches: [[name: branch_or_commit]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: '27bbd815-703c-4647-909b-836919db98ef', url: repo_url]]])
}

// Creates a git tag and commits it. Must be called when the pwd is the
// source git directory.
void createAndPushTag(tag){
  sh (
    script: "git tag -a ${tag} -m 'Version tag created by the build system'",
    returnStdout: false
  )

  sh (
    script: "git push origin ${tag}",
    returnStdout: false
  )
}

// Returns all changes since the last git tag.
String getChangesSinceLastTag(){
  return sh (
    script: 'git log `git tag -l --sort=-v:refname | head -n 1`..HEAD',
    returnStdout: true
  ).trim()
}

// Use groovy to create and return json for the version and commit
// for this run.
void createVersionInfo(version, commit){
  versionObject = [:]
  versionObject.version = version
  versionObject.commit = commit
  return new JsonOutput().toJson(versionObject)
}
