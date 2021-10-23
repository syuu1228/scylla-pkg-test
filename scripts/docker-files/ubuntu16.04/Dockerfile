FROM docker.io/ubuntu:16.04

ARG SCYLLA_PRODUCT_NAME
ARG SCYLLA_GPG_KEY
ARG SCYLLA_REPO_URL
ARG KEY_SERVER

RUN apt-get clean
RUN apt-get update -y -qq
RUN apt-get install --yes curl gnupg apt-transport-https -y -qq
RUN apt-key adv --keyserver $KEY_SERVER --recv-keys $SCYLLA_GPG_KEY
RUN curl -o /etc/apt/sources.list.d/scylla.list -L ${SCYLLA_REPO_URL}
RUN apt-get update -o Acquire::Retries=3 -y -qq
RUN DEBIAN_FRONTEND=noninteractive apt-get install $SCYLLA_PRODUCT_NAME -y -qq
