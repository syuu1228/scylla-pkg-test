# pylint: disable=W,C,R
import os
import sys
import re
import shutil
from invoke import task, Collection
from rich import print  # pylint: disable=redefined-builtin
from rich.console import Console
import requests

console = Console(color_system=None)

@task()
def build(c):
    with c.cd('./scylla'):
        c.run(f'./tools/toolchain/dbuild  ./configure.py')
        c.run(f'./tools/toolchain/dbuild ninja')

ns = Collection(build)
