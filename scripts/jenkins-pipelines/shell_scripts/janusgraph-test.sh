#!/bin/bash

set -e
PROGRAM=$(basename $0)
echo "Running $PROGRAM"
echo "=================== env ==================="
env
echo "==========================================="


DOCKER_VERSION_TO_INSTALL=${DOCKER_VERSION_TO_INSTALL:-19.03}
GET_DOCKER_URL=${GET_DOCKER_URL:-https://get.docker.com}

SCRIPT_TO_RUN="\
export VERSION=${DOCKER_VERSION_TO_INSTALL} ;\
curl -fSL ${GET_DOCKER_URL} -o /bin/get-docker.sh ;\
bash /bin/get-docker.sh ;\
mvn clean install --no-transfer-progress --projects janusgraph-cql --also-make -DskipTests=true ;\
mvn clean install --no-transfer-progress -pl janusgraph-cql -Pscylladb -Dcassandra.docker.image=${SCYLLA_DOCKER_IMAGE} -Dcassandra.docker.version=${SCYLLA_DOCKER_VERSION} -Dtest.cql.excludes=MEMORY_TESTS,PERFORMANCE_TESTS,BRITTLE_TESTS \
"

DOCKER_CMD="\
    docker run --rm --detach=true \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v `pwd`:/janusgraph -w /janusgraph  maven:3-jdk-8 \
    bash -c \"${SCRIPT_TO_RUN}\" \
"

echo "Running Docker: ${DOCKER_CMD}"
container=$(eval ${DOCKER_CMD})

docker logs "$container" -f

if [[ -n "$container" ]]; then
    exitcode="$(docker wait "$container")"
else
    exitcode=99
fi

echo "Docker exitcode: $exitcode"

trap - SIGTERM SIGINT SIGHUP EXIT

# after "docker kill", docker wait will not print anything
[[ -z "$exitcode" ]] && exitcode=1

echo "changing ownership of files"
# To Avoid leaving root owned files, created by docker. Jenkins can't clean these files.
docker run --rm -v `pwd`:/janusgraph -w /janusgraph busybox sh -c "find . -user root -name '*' | xargs chmod ugo+rw"

exit "$exitcode"
