#!/usr/bin/env python3
"""Build the private-download Core 2.4.2 compatibility sketch ZIP."""
import argparse
from pathlib import Path
from pathlib import PurePosixPath
import re
import zipfile

root = Path(__file__).resolve().parents[1]
version = (root / 'legacy-tls10/VERSION').read_text(encoding='ascii').strip()
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise SystemExit('Firmware VERSION must use MAJOR.MINOR.PATCH format')

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path,
                    default=Path.cwd() / f'esp8266-core-2.4.2-tls10-v{version}.zip')
parser.add_argument('--base-zip', type=Path, default=root.parents[1] / 'site/doc/files/printer-tunnel-arduino-win7.zip')
args = parser.parse_args()
if args.output.name != f'esp8266-core-2.4.2-tls10-v{version}.zip':
    raise SystemExit(f'Output filename must include current version: esp8266-core-2.4.2-tls10-v{version}.zip')

source = root / 'legacy-tls10/printer_tunnel_esp8266_tls10/printer_tunnel_esp8266_tls10.ino'
readme = root / 'legacy-tls10/README.md'
version_file = root / 'legacy-tls10/VERSION'
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.base_zip) as base, zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as output:
    for item in base.infolist():
        if item.filename == 'printer_tunnel_esp8266/printer_tunnel_esp8266.ino':
            continue
        if item.filename.startswith('printer_tunnel_esp8266/ArduinoJson'):
            relative = PurePosixPath(item.filename).relative_to('printer_tunnel_esp8266')
            output.writestr(str(PurePosixPath('printer_tunnel_esp8266_tls10') / relative), base.read(item.filename))
        else:
            output.writestr(item, base.read(item.filename))
    output.write(source, 'printer_tunnel_esp8266_tls10/printer_tunnel_esp8266_tls10.ino')
    output.write(readme, 'printer_tunnel_esp8266_tls10/README.md')
    output.write(version_file, 'printer_tunnel_esp8266_tls10/VERSION')
print(f'Created private-download ZIP: {args.output}')
