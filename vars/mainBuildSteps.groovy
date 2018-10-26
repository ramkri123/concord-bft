def call(){
  pipeline {
    agent any
    tools {
        nodejs 'Node 8.9.1'
    }
    parameters {
      booleanParam defaultValue: false, description: 'If tests pass, deploy the docker images for production', name: 'deploy'
      string defaultValue: '',
             description: 'The version number for releases. Used as a tag in DockerHub and GitHub.',
             name: 'version_param'

      string defaultValue: '',
             description: 'Athena commit or branch to use.  Providing a branch name will pull the branch\'s latest commit.',
             name: 'athena_branch_or_commit'
      string defaultValue: '',
             description: 'Helen commit or branch to use.  Providing a branch name will pull the branch\'s latest commit.',
             name: 'helen_branch_or_commit'
      string defaultValue: '',
             description: 'Hermes commit or branch to use.  Providing a branch name will pull the branch\'s latest commit.',
             name: 'hermes_branch_or_commit'
      string defaultValue: '',
             description: 'Shared Jenkins lib commit or branch to use.  Providing a branch name will pull the branch\'s latest commit.',
             name: 'shared_lib_branch_or_commit'
    }
    stages {
      stage('Clean') {
        steps {
          cleanWs()
        }
      }
      stage('Fetch all source code') {
        parallel {
          stage('Fetch Athena source') {
            steps {
              echo "Athena branch/commit: ${params.athena_branch_or_commit}"
              sh 'mkdir athena'
              dir('athena') {
                script {
                  env.actual_athena_fetched = getRepoCode("git@github.com:vmwathena/athena.git", params.athena_branch_or_commit)
                }
              }
            }
          }
          stage('Fetch Helen source') {
            steps {
              echo "Helen branch/commit: ${params.helen_branch_or_commit}"
              sh 'mkdir helen'
              dir('helen') {
                script {
                  env.actual_helen_fetched = getRepoCode("git@github.com:vmwathena/helen.git", params.helen_branch_or_commit)
                }
              }
            }
          }
          stage('Fetch Hermes source') {
            steps {
              echo "Hermes branch/commit: ${params.hermes_branch_or_commit}"
              sh 'mkdir hermes'
              dir('hermes') {
                script {
                  env.actual_hermes_fetched = getRepoCode("git@github.com:vmwathena/hermes.git", params.hermes_branch_or_commit)
                }
              }
            }
          }
        }
      }

      stage('Copy dependencies') {
        parallel {
          stage('Copy googletest') {
            steps() {
              sh 'mkdir googletest'
              dir('googletest') {
                sh 'cp -ar /var/jenkins/workspace/googletest/* .'
              }
            }
          }
          stage('Copy evmjit') {
            steps() {
              sh 'mkdir evmjit'
              dir('evmjit') {
                sh 'cp -ar /var/jenkins/workspace/evmjit/* .'
              }
            }
          }
          stage('Copy etherium tests') {
            steps() {
              sh 'mkdir ethereum_tests'
              dir('ethereum_tests') {
                sh 'cp -ar /var/jenkins/workspace/ethereum_tests/* .'
              }
            }
          }
        }
      }

      // stage('test email') {
      //   steps() {
      //     script {
      //       emailext (
      //         subject: "STARTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
      //         body: """<p>STARTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
      //           <p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>""",
      //         recipientProviders: [[$class: 'DevelopersRecipientProvider']],
      //         to: "rvollmar@vmware.com"
      //       )
      //     }
      //   }
      // }

      stage('Build products') {
        parallel {
          stage('Build Athena') {
            steps {
              dir('athena') {
                sh '''currentDir=`pwd`
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/athena1.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/athena2.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/athena3.config
                sed -i\'\' "s?/tmp/genesis.json?${currentDir}/test/resources/genesis.json?g" resources/athena4.config

                git submodule init
                git submodule update --recursive
                mkdir -p build
                cd build
                cmake ..
                make'''
              }
            }
          }
          stage('Build Helen') {
            steps {
              dir('helen') {
                sh 'mvn clean install package'
                //  Maybe add those tests here at some point. Need investigation and config.
                //              dir('webapps') {
                //                sh 'npm run e2e'
                //              }
              }
            }
          }
        }
      }
      stage('Run tests') {
        steps {
          dir('hermes') {
            configFileProvider([configFile(fileId: 'aa8c3633-2505-4522-a242-4276a0796aec', targetLocation: 'resources/user_config.json')]) {}
            // sh './main.py CoreVMTests'
            sh './main.py HelenAPITests'
            sh './main.py ExtendedRPCTests'
            sh './main.py RegressionTests'
          }
        }
      }

      stage('Configure docker and git') {
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

          // Log into docker.  Does it expire?  Try doing it once, here, and see what happens.
          withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {

            sh '''
              echo "${DOCKERHUB_PASSWORD}" | docker login -u blockchainrepositorywriter --password-stdin
            '''
          }

          // To invoke "git tag" and commit that change, git wants to know who we are.
          // This will be set up in template VM version 5, at which point these commands can
          // be removed.
          sh '''
            git config --global user.email "vmwathenabot@vmware.com"
            git config --global user.name "build system"
          '''

          // These are constants which mirror the DockerHub repos.
          script {
            env.athena_repo = 'vmwblockchain/concord-core'
            env.helen_repo = 'vmwblockchain/concord-ui'
            env.andes_repo = 'vmwblockchain/concord-ds-api'
          }
        }
      }

      stage('Build docker images') {
        parallel {
          stage('Build helen docker image') {
            steps {
              script {
                dir('helen') {

                  script {
                    env.helen_docker_tag = env.version_param ? env.version_param : env.actual_helen_fetched
                  }

                  withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                    sh '''
                      docker build . -t "${helen_repo}:${helen_docker_tag}"
                    '''
                  }
                }
              }
            }
          }


          stage('Build athena docker image') {
            steps {
              script {
                dir('athena') {

                  script {
                    env.athena_docker_tag = env.version_param ? env.version_param : env.actual_athena_fetched
                  }
                  withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                    sh '''
                      ./docker-build.sh "${athena_repo}" "${athena_docker_tag}"
                    '''
                  }
                }
              }
            }
          }
        }
      }
      // stage('Prepare docker compose') {
      //   steps {
      //     sh 'mkdir docker'
      //     dir('docker') {
      //       configFileProvider([configFile(fileId: '4bcb682e-fe63-4f12-944b-a8d1f93b81eb', targetLocation: 'docker-compose.yml')]) {
      //       }
      //       sh "sed -i'' 's/{{tag}}/${env.BRANCH_NAME}/g' docker-compose.yml"
      //     }
      //   }
      // }
      // RV 2018/09/20: When this runs, the docker/cockroachdb directory has files which Jenkins cannot delete.
      //                Need to investigate.
      // stage('Run the product in containers along with the tests') {
      //   steps {
      //     dir('hermes') {
      //       configFileProvider([configFile(fileId: 'd3dd9fd0-f578-4fae-a0dd-5a9ef81698cc', targetLocation: 'resources/user_config.json')]) {
      //       }
      //       sh "sed -i'' 's/{{build_root}}/..\\/docker\\//g' resources/user_config.json"
      //       sh './main.py CoreVMTests'

      //       // RV 2018/09/20: HelenAPITests in a Docker fail.
      //       //                Need toinvestigate.
      //       // sh './main.py HelenAPITests'

      //       sh './main.py ExtendedRPCTests'
      //       sh './main.py RegressionTests'
      //     }
      //   }
      // }
      // stage('Clean containers') {
      //   steps {
      //     dir('docker') {
      //       sh "docker-compose down"
      //     }
      //   }
      // }

      stage('Push to docker repository') {
        when {
          environment name: 'deploy', value: 'true'
        }
        steps {
          dir('athena') {
            createAndPushTag(env.version_param)
          }
          dir('helen') {
            createAndPushTag(env.version_param)
          }
          dir('hermes') {
            createAndPushTag(env.version_param)
          }
          // dir('andes') {
          //   createAndPushTag(env.version_param)
          // }
          withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
            sh '''
              docker push ${athena_repo}:${version_param}
              docker tag ${athena_repo}:${version_param} ${athena_repo}:latest
              docker push ${athena_repo}:latest

              docker push ${helen_repo}:${version_param}
              docker tag ${helen_repo}:${version_param} ${helen_repo}:latest
              docker push ${helen_repo}:latest

              # docker push ${andes_repo}:${version_param}
              # docker tag ${andes_repo}:${version_param} ${andes_repo}:latest
              # docker push ${andes_repo}:latest
            '''
          }
        }
      }
    }// End stages

    post {
      always {
        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
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
  if (branch_or_commit.trim()){
    checkoutRepo(repo_url, branch_or_commit)
  }else{
    checkoutRepo(repo_url, "master")

    // When launched via the multibranch pipeline plugin, there is a BRANCH_NAME
    // environment variable.
    if (env.BRANCH_NAME && env.BRANCH_NAME.trim()){
      try {
        checkoutRepo(repo_url, "/refs/heads/${env.BRANCH_NAME}")
      } catch (Exception e) {
        echo "Branch ${env.BRANCH_NAME} for ${repo_url} not found."
      }
    }
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
