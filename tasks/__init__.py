from invoke import Collection

import tasks.ami
import tasks.scylla

ns = Collection(tasks.ami)
ns = Collection(tasks.scylla)
