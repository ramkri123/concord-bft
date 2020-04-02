#!/bin/bash

# 1. You need to build the image first
#    $> docker build . -t committer-tools
# 2. Tar the image
#    $> docker save -o committer-tools.tar committer-tools
# 3. Copy the image to Photon an run this script

IMAGE="committer-tools.tar"
TOOLS_DIR="committer-tools"

if [ -f "${pwd}/${IMAGE}"  ]; then
    2>&1 echo "Tools image not found: ${IMAGE}"
    exit 1
fi

docker load -i ${IMAGE}
CONTAINER=$(docker create ${IMAGE%.*})
docker cp ${CONTAINER}:/${TOOLS_DIR} .
docker rm -v ${CONTAINER}

echo "${TOOLS_DIR} copied to $(pwd)/${TOOLS_DIR}"
