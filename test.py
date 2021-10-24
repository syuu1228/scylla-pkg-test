import requests

with open('/var/tmp/takuya-api-token.txt') as f:
    token = f.read().strip()
r = requests.get('https://jenkins.scylladb.com/view/master/job/scylla-master/job/releng-testing/job/centos-rpm/lastSuccessfulBuild/artifact/00-Build.txt', auth=('syuu1228',token))
print(r.text)


