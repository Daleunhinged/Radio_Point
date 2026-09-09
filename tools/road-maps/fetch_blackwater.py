import urllib.request,urllib.parse,json
from pathlib import Path
p=Path(__file__).parent;els={};timestamps=[]
for i,(w,e) in enumerate([(-123.7,-123.025),(-123.025,-122.35)]):
 f=p/f'Blackwater-part{i}.json'
 if f.exists():j=json.loads(f.read_text())
 else:
  b=f'52.95,{w},53.65,{e}';q=f'[out:json][timeout:50];(way["highway"]({b});way["waterway"="river"]({b});way["natural"="water"]({b});node["place"~"^(city|town|village|hamlet|locality)$"]({b}););out geom;'
  u='https://overpass-api.de/api/interpreter?'+urllib.parse.urlencode({'data':q})
  raw=urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':'RadioPoint-map-preparation/1.0'}),timeout=65).read();j=json.loads(raw)
  if j.get('remark'):raise RuntimeError(j['remark'])
  f.write_bytes(raw)
 for el in j['elements']:els[(el['type'],el['id'])]=el
 timestamps.append(j['osm3s']['timestamp_osm_base']);print(i,len(j['elements']),flush=True)
(p/'Blackwater.json').write_text(json.dumps({'elements':list(els.values()),'osm3s':{'timestamp_osm_base':min(timestamps)}}));print('Blackwater',len(els),flush=True)
