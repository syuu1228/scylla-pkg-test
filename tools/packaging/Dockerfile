FROM docker.io/fedora:33

ADD ./tools/packaging/install-dependencies.sh ./
ADD ./tools/packaging/system-auth ./

RUN dnf -y update && dnf -y install 'dnf-command(copr)' \
    && ./install-dependencies.sh && dnf -y update && dnf clean all \
    && echo 'ALL ALL=(ALL:ALL) NOPASSWD: ALL' >> /etc/sudoers \
    && cp system-auth /etc/pam.d \
    && echo 'Defaults !requiretty' >> /etc/sudoers
RUN wget https://github.com/aptly-dev/aptly/releases/download/v1.4.0/aptly_1.4.0_linux_amd64.tar.gz && \
    tar xvpf aptly_1.4.0_linux_amd64.tar.gz && \
    cp aptly_1.4.0_linux_amd64/aptly /usr/bin/aptly && \
    wget http://cdn-fastly.deb.debian.org/debian/pool/main/d/dpkg-sig/dpkg-sig_0.13.1+nmu2.tar.gz && \
    tar xvpf dpkg-sig_0.13.1+nmu2.tar.gz && \
    cp dpkg-sig-0.13.1+nmu2/dpkg-sig /usr/bin/dpkg-sig && \
    chmod a+rx /usr/bin/dpkg-sig && \
    rm -rf dpkg-sig* && \
    echo yes | cpan install Config::File
CMD /bin/bash
