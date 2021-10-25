from invoke import Collection

import tasks.ami
import tasks.scylla
from scylla_arms.persisted_dicts import FilePersistedDotDict

ns = Collection(tasks.ami, tasks.scylla)
ns.configure({'persisted': FilePersistedDotDict("persisted_params.json")})
