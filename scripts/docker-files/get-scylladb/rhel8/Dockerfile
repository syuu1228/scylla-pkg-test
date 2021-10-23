FROM registry.access.redhat.com/ubi8/ubi-minimal

ARG SCYLLA_VERSION
ARG SCYLLA_PRODUCT_NAME
ARG SERVER_TEST_BASE_URL
ARG DRY_RUN
ARG SCYLLA_BASE_URL

RUN microdnf clean all
RUN microdnf install -y curl
RUN curl -sSf $SERVER_TEST_BASE_URL/server > server.sh
RUN chmod +x server.sh
RUN ls -la server.sh
RUN bash -x server.sh --scylla-product $SCYLLA_PRODUCT_NAME --scylla-version $SCYLLA_VERSION $DRY_RUN $SCYLLA_BASE_URL --verbose --debug-run
