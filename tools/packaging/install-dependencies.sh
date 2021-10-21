#!/bin/bash -e
#
. /etc/os-release


os_arch() {
    declare -A darch
    darch=(["x86_64"]=amd64 ["aarch64"]=arm64)
    echo "${darch[$(arch)]}"
}

packer_installation() {
  if [ $(os_arch) = "amd64" ]; then
    if [ "$ID" = "ubuntu" ] || [ "$ID" = "debian" ]; then
        curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo apt-key add -
        echo "deb [arch=$(os_arch)] https://apt.releases.hashicorp.com $VERSION_CODENAME main" | sudo tee -a /etc/apt/sources.list.d/packer.list
        sudo apt-get update && sudo apt-get install -y packer
    elif [ "$ID" = "fedora" ]; then
        dnf install -y dnf-plugins-core
        dnf config-manager --add-repo https://rpm.releases.hashicorp.com/fedora/hashicorp.repo
        dnf -y install packer
    fi
  fi

  if [ $(os_arch) = "arm64" ]; then
    PACKER_VERSION=1.7.0
    export EXPECTED="821a1549e35e55f9c470a7d4675241abd396caeb99044ca6c9fae9ede8e67c60 packer_linux_$(os_arch).zip"
    wget -nv https://releases.hashicorp.com/packer/${PACKER_VERSION}/packer_${PACKER_VERSION}_linux_$(os_arch).zip -O packer_linux_$(os_arch).zip
    echo $EXPECTED | sha256sum --check
    unzip -x packer_linux_$(os_arch).zip -d /usr/bin
    rm packer_linux_$(os_arch).zip
  fi
}

google_cloud_sdk_installation() {
  if [ "$ID" = "ubuntu" ] || [ "$ID" = "debian" ]; then
    apt-get install -y apt-transport-https ca-certificates gnupg
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key --keyring /usr/share/keyrings/cloud.google.gpg add -
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | sudo tee -a /etc/apt/sources.list.d/google-cloud-sdk.list
    apt-get update
    apt-get install -y google-cloud-sdk
  elif [ "$ID" = "fedora" ]; then
    tee -a /etc/yum.repos.d/google-cloud-sdk.repo << EOM
[google-cloud-sdk]
name=Google Cloud SDK
baseurl=https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg
       https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOM
  dnf install -y google-cloud-sdk
fi
}

debian_base_packages=(
    curl
    sudo
    wget
    unzip
    git
    findutils
    gnupg2
    dpkg-dev
    python3
    apt-utils
    python3-pip
    python3-boto3
    python3-dateutil
    python3-github
    python3-git
    awscli

)

fedora_packages=(
    sudo
    git
    wget
    curl
    unzip
    yum-utils
    python3
    perl-CPAN
    perl-tests
    createrepo
    awscli
    xz
    bzip2
    gnupg2
    distribution-gpg-keys
    libunwind-devel
    gettext
    openssh-clients
    jq
    mysql
)

fedora_python3_packages=(
    python3-pip
    python3-boto3
    python3-dateutil
    python3-PyGithub
    python3-GitPython
    python3-requests
    python3-kobo-rpmlib
)

install_pip_packages=(
    jenkinsapi
    cfn-lint==0.23.4
    boto3==1.9.216
    pytest==5.1.1
    Jinja2==2.11.3
    jinja2-cli==0.7.0
)

arch_packages=(
    curl
    sudo
    wget
    unzip
    git
    findutils
    gnupg2
    dpkg-dev
    python3
    apt-utils
)

print_usage() {
    echo "Usage: install-dependencies.sh [OPTION]..."
    echo ""
    echo "  --print-python3-runtime-packages Print required python3 packages for Scylla"
    echo "  --print-node-exporter-filename Print node_exporter filename"
    exit 1
}

if [ "$ID" = "ubuntu" ] || [ "$ID" = "debian" ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get -y install "${debian_base_packages[@]}"
    pip install "${install_pip_packages[@]}"
    google_cloud_sdk_installation
    curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

elif [ "$ID" = "fedora" ]; then
    if rpm -q --quiet yum-utils; then
        echo
        echo "This script will install dnf-utils package, witch will conflict with currently installed package: yum-utils"
        echo "Please remove the package and try to run this script again."
        exit 1
    fi
    yum install -y "${fedora_packages[@]}" "${fedora_python3_packages[@]}"
    pip install "${install_pip_packages[@]}"
    google_cloud_sdk_installation

elif [ "$ID" == "arch" ]; then
    # main
    if [ "$EUID" -eq "0" ]; then
        pacman -Sy --needed --noconfirm "${arch_packages[@]}"
    else
        echo "scylla: You now ran $0 as non-root. Run it again as root to execute the pacman part of the installation." 1>&2
    fi
fi
packer_installation
