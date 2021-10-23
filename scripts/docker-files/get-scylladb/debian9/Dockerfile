FROM docker.io/debian:9

ARG SCYLLA_VERSION
ARG SCYLLA_PRODUCT_NAME
ARG SERVER_TEST_BASE_URL
ARG DRY_RUN
ARG SCYLLA_BASE_URL

RUN apt-get clean
RUN apt-get update -y -qq
RUN apt-get install -o Acquire::Retries=3 curl -y -qq
RUN curl -sSf $SERVER_TEST_BASE_URL/server > server.sh
RUN chmod +x server.sh
RUN ls -la server.sh
RUN --env VERSION_CODENAME=stretch bash -x server.sh --scylla-product $SCYLLA_PRODUCT_NAME --scylla-version $SCYLLA_VERSION $DRY_RUN $SCYLLA_BASE_URL --verbose
