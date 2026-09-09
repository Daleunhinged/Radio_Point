"""Validate built map archives; run after build_maps.py."""
import hashlib,io,json,sqlite3
from pathlib import Path
from PIL import Image
p=Path(__file__).parent/'Quesnel-Road-Maps'
for rec in json.loads((p/'manifest.json').read_text()):
 f=p/(rec['area']+'.mbtiles')
 assert hashlib.sha256(f.read_bytes()).hexdigest()==rec['sha256'],f
 with sqlite3.connect(f) as db:
  assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  meta=dict(db.execute('SELECT name,value FROM metadata'))
  assert meta['format']=='png' and meta['scheme']=='tms'
  assert meta['minzoom']=='8' and meta['maxzoom']=='14'
  count=0
  for z,x,y,b in db.execute('SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles'):
   assert 8<=z<=14 and 0<=x<2**z and 0<=y<2**z
   im=Image.open(io.BytesIO(b));assert im.size==(256,256);im.verify();count+=1
  assert count==rec['tiles']
 print(rec['area'],count,'valid PNG tiles; hash and SQLite integrity OK')
