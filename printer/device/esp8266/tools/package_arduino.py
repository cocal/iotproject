#!/usr/bin/env python3
"""Produce an Arduino sketch ZIP; dependencies are bundled, credentials never printed."""
import argparse
from pathlib import Path
import shutil
import tempfile
import zipfile
import os
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--config', type=Path, help='Private config.h; omitted uses public placeholder example')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--redact-host', action='store_true', help='Replace the endpoint with a placeholder in a private source package')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
config = args.config or root / 'include/config.example.h'
library = root / '.pio/libdeps/nodemcuv2/ArduinoJson'
if not (library / 'src/ArduinoJson.h').exists():
    parser.error('Run pio run first to obtain the pinned ArduinoJson dependency')
with tempfile.TemporaryDirectory() as temporary:
    sketch = Path(temporary) / 'printer_tunnel_esp8266'
    sketch.mkdir()
    source = (root / 'src/main.cpp').read_text().replace('#include <ArduinoJson.h>', '#include "ArduinoJson.h"')
    configuration = config.read_text().replace('#pragma once\n', '')
    if args.redact_host:
        configuration = re.sub(r'^#define TUNNEL_HOST .*$', '#define TUNNEL_HOST "YOUR_TUNNEL_HOST"', configuration, flags=re.M)
    source = source.replace('#include "config.h"', configuration)
    (sketch / 'printer_tunnel_esp8266.ino').write_text(source)
    for item in (library / 'src').iterdir():
        if item.is_dir():
            shutil.copytree(item, sketch / item.name)
        else:
            shutil.copy2(item, sketch / item.name)
    shutil.copy2(library / 'LICENSE.txt', sketch / 'ArduinoJson-LICENSE.txt')
    # Arduino 1.x does not add the sketch root to angle-bracket include paths.
    # Keep the vendored headers local and resolve their internal paths relatively.
    for header in list(sketch.rglob('*.h')) + list(sketch.rglob('*.hpp')):
        source = header.read_text()
        source = re.sub(r'#\s*include\s*<((?:ArduinoJson/)[^>]+)>',
                        lambda match: '#include "' + os.path.relpath(sketch / match.group(1), header.parent).replace(os.sep, '/') + '"',
                        source)
        header.write_text(source)
    shutil.copy2(root / 'ARDUINO-WIN7.md', sketch / 'README-WIN7.md')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for item in sorted(sketch.rglob('*')):
            if item.is_file():
                archive.write(item, item.relative_to(sketch.parent))
    if args.config:
        args.output.chmod(0o600)
print('Arduino sketch ZIP created:', args.output)
