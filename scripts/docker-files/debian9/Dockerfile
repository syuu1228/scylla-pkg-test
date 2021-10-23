FROM docker.io/debian:9.0

ARG SCYLLA_PRODUCT_NAME
ARG SCYLLA_GPG_KEY
ARG SCYLLA_REPO_URL
ARG KEY_SERVER
ARG FETCH_KEYS_URL

ARG OS_VERSION_NAME=stretch
ARG OS_NAME_NUMBER=Debian_9.0

RUN apt-get clean
RUN apt-get update -y -qq
RUN apt-get install -o Acquire::Retries=3 software-properties-common apt-transport-https wget gnupg2 dirmngr curl -y -qq
RUN apt-key adv --fetch-keys ${FETCH_KEYS_URL}-${OS_VERSION_NAME}/$OS_NAME_NUMBER/Release.key
RUN apt-get update -y -qq
RUN apt-key adv --keyserver $KEY_SERVER --recv-keys $SCYLLA_GPG_KEY
RUN curl -o /etc/apt/sources.list.d/scylla.list -L ${SCYLLA_REPO_URL}

RUN apt-get update -y -qq
RUN apt-get install $SCYLLA_PRODUCT_NAME -y -qq
