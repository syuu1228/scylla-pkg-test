FROM docker.io/centos:7

ARG SCYLLA_REPO_URL
ARG SCYLLA_PRODUCT_NAME

RUN yum clean all
RUN yum install -y epel-release curl
RUN curl -o /etc/yum.repos.d/scylla.repo -L $SCYLLA_REPO_URL
RUN yum install -y $SCYLLA_PRODUCT_NAME
