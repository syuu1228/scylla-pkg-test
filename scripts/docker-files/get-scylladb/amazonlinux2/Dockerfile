FROM public.ecr.aws/amazonlinux/amazonlinux:2

ARG SCYLLA_VERSION
ARG SCYLLA_PRODUCT_NAME
ARG SERVER_TEST_BASE_URL
ARG DRY_RUN
ARG SCYLLA_BASE_URL

RUN yum clean all
RUN yum install -y curl
RUN curl -sSf $SERVER_TEST_BASE_URL/server > server.sh
RUN chmod +x server.sh
RUN ls -la server.sh
RUN bash -x server.sh --scylla-product $SCYLLA_PRODUCT_NAME --scylla-version $SCYLLA_VERSION $DRY_RUN $SCYLLA_BASE_URL --verbose
