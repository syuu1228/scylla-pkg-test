import io
import configparser
import os
import re
from pathlib import PurePath

class jenkins_configparser:
    def __load(self):
        f = io.StringIO(f'[global]\n{self._data}')
        self._cfg = configparser.ConfigParser()
        self._cfg.optionxform = str
        self._cfg.read_file(f)

    def __add(self, key, val):
        self._data += f'{key}{self.spacer_left}{self.delimiter}{self.spacer_right}{val}\n'
        self.__load()

    def __init__(self, filename, spacer_left, delimiter, spacer_right, new_file=False):
        self.spacer_left = spacer_left
        self.delimiter = delimiter
        self.spacer_right= spacer_right
        if isinstance(filename, PurePath):
            self._filename = str(filename)
        else:
            self._filename = filename
        if new_file and not os.path.exists(filename):
            open(filename, 'a').close()
        with open(filename) as f:
            self._data = f.read()
        self.__load()

    def get(self, key):
        return self._cfg.get('global', key)

    def has_option(self, key):
        return self._cfg.has_option('global', key)

    def set(self, key, val):
        if not self.has_option(key):
            return self.__add(key, val)
        self._data = re.sub(f'^{key}{self.spacer_left}{self.delimiter}{self.spacer_right}[^\n]*$', f'{key}{self.spacer_left}{self.delimiter}{self.spacer_right}{val}', self._data, flags=re.MULTILINE)
        self.__load()

    def commit(self):
        with open(self._filename, 'w') as f:
            f.write(self._data)

class properties_parser(jenkins_configparser):
    def __init__(self, filename, new_file=False):
        super().__init__(filename, '', '=', '', new_file)

class build_metadata_parser(jenkins_configparser):
    def __init__(self, filename, new_file=False):
        super().__init__(filename, '', ':', ' ', new_file)
