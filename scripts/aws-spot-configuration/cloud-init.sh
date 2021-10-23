#!/usr/bin/env bash
set -x
set -o errexit
set -o pipefail
set -o nounset

dnf install -y curl \
  wget \
  git \
  ccache \
  awscli \
  gnupg2 \
  unzip \
  yum-utils \
  python3 \
  perl-CPAN \
  perl-tests \
  createrepo \
  python3-kobo-rpmlib \
  xz \
  bzip2 \
  distribution-gpg-keys \
  vim \
  java-1.8.0-openjdk \
  logrotate \
  lvm2 \
  buildah

dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo

dnf -y install docker-ce docker-ce-cli containerd.io podman

pip3 install --user python-dateutil boto3 github-cli GitPython

sed -i 's/^SELINUX=.*/SELINUX=disabled/' /etc/selinux/config
setenforce 0

useradd -c "Cloud User" -u 1001 -m -s /bin/bash jenkins
usermod -aG jenkins,wheel,ccache,docker jenkins

mkdir /home/jenkins/.ssh
cp /home/fedora/.ssh/authorized_keys /home/jenkins/.ssh/authorized_keys
chmod 700 /home/jenkins/.ssh
chown jenkins:jenkins -R /home/jenkins/.ssh

sed -i 's/^# %wheel/%wheel/' /etc/sudoers

echo "fs.aio-max-nr = 30000000" > /etc/sysctl.d/aio-max-nr.conf
sysctl -p /etc/sysctl.d/aio-max-nr.conf

mkdir -p /jenkins/slave
chown jenkins:jenkins -R /jenkins
setfacl -d -m g::rwx /jenkins

echo "* hard nofile 40000" >> /etc/security/limits.conf
echo "* soft nofile 40000" >> /etc/security/limits.conf

systemctl enable docker
systemctl start docker

num=$(fdisk -l | grep "^Disk /dev/nvme[1-9]" | wc -l)
for (( c=1; c<=$num; c++)) ; do
  echo -e "o\nn\np\n1\n\n\nt\n8e\nw" | fdisk `fdisk -l | grep "^Disk /dev/nvme[1-9]" | awk NR==$c | awk '{print $2}' | tr -d ':'`
done

part_list=$(fdisk -l | grep "Linux LVM" | awk '{print $1}' ORS=' ')
pvcreate -y $part_list
vgcreate -y vg_jenkins $part_list
lvcreate -y -n vol_data -l 100%FREE vg_jenkins
mkfs.xfs /dev/vg_jenkins/vol_data
mount /dev/vg_jenkins/vol_data /jenkins
chown jenkins:jenkins -R /jenkins
mkdir -p /jenkins/var/cache/ccache
mkdir -p /jenkins/tmp
mount --bind /jenkins/var/cache/ccache/ /var/cache/ccache
mount --bind /jenkins/tmp /tmp
chmod 1777 /tmp
echo "max_size = 95G" > /var/cache/ccache/ccache.conf
chown jenkins:ccache -R /jenkins/var/cache/ccache
