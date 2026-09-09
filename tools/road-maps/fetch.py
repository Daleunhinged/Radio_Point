import urllib.request,urllib.parse,json
from pathlib import Path
p=Path(__file__).parent
areas={'Nazko':[-124.0,52.65,-122.4,53.2],'Blackwater':[-123.7,52.95,-122.35,53.65],'Quesnel-Barkerville':[-122.65,52.85,-121.3,53.3]}
(p/'areas.json').write_text(json.dumps(areas))
for name,(w,s,e,n) in areas.items():
 f=p/(name+'.json')
 if f.exists():continue
 b=f'{s},{w},{n},{e}'
 q=f'[out:json][timeout:50];(way["highway"]({b});way["waterway"="river"]({b});way["natural"="water"]({b});node["place"~"^(city|town|village|hamlet|locality)$"]({b}););out geom;'
 u='https://overpass-api.de/api/interpreter?'+urllib.parse.urlencode({'data':q})
 try:
  raw=urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':'RadioPoint-map-preparation/1.0'}),timeout=65).read();j=json.loads(raw)
  if j.get('remark'):raise RuntimeError(j['remark'])
  f.write_bytes(raw);print(name,len(j['elements']),len(raw),flush=True)
 except Exception as ex:print(name,str(ex),flush=True)
